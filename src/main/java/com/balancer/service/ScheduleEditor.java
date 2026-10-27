package com.balancer.service;

import com.balancer.command.AssignCommand;
import com.balancer.command.CommandHistory;
import com.balancer.command.EditCommand;
import com.balancer.command.RemoveCommand;
import com.balancer.command.SnapshotCommand;
import com.balancer.command.SwapCommand;
import com.balancer.model.Assignment;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Violation;
import com.balancer.model.Volunteer;
import com.balancer.persistence.DataStore;
import com.balancer.strategy.FairnessStrategy;
import com.balancer.strategy.StrategyRegistry;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The admin service API. Every change goes through a Command (so it can be undone), is
 * re-validated immediately, and is persisted to SQL with an audit-log entry.
 * Methods are synchronized so the HTTP server can call them safely.
 */
public final class ScheduleEditor {

    public record LogEntry(LocalDateTime at, String action, String description) {
    }

    public record EditResult(String message, List<Violation> overridden, List<Violation> scheduleViolations) {
    }

    private final ValidationEngine validator;
    private final ShiftBalancerService balancer;
    private final DiagnosticsService diagnostics;
    private final MetricsService metrics;
    private final StrategyRegistry strategies;
    private final CommandHistory history = new CommandHistory();
    private final Deque<LogEntry> activity = new ArrayDeque<>();
    private final DataStore store; // may be null (pure in-memory mode)
    private Schedule schedule;
    private FairnessStrategy currentStrategy;
    private ShiftBalancerService.BalanceResult lastBalance;

    public ScheduleEditor(Schedule schedule, DataStore store) {
        this.schedule = schedule;
        this.store = store;
        this.validator = new ValidationEngine();
        this.balancer = new ShiftBalancerService(validator);
        this.diagnostics = new DiagnosticsService(validator);
        this.metrics = new MetricsService();
        this.strategies = new StrategyRegistry();
        this.currentStrategy = strategies.defaultStrategy();
    }

    // ---- commands ------------------------------------------------------------------------

    public synchronized EditResult balance(String strategyId) {
        FairnessStrategy strategy = strategies.get(strategyId);
        currentStrategy = strategy;
        SnapshotCommand cmd = new SnapshotCommand("BALANCE", s -> {
            lastBalance = balancer.balance(s, strategy);
            return String.format("Auto-balance (%s): %d placed, %d refinement moves, %d pinned kept",
                    strategy.displayName(), lastBalance.assignmentsMade(), lastBalance.refinementMoves(), lastBalance.keptManual());
        });
        return run(cmd, List.of());
    }

    public synchronized EditResult assign(String volunteerId, String shiftId, boolean force) {
        AssignCommand cmd = new AssignCommand(validator, volunteerId, shiftId, force);
        history.execute(cmd, schedule);
        record(cmd);
        return result(cmd.description(), cmd.overriddenViolations());
    }

    public synchronized EditResult remove(String volunteerId, String shiftId) {
        return run(new RemoveCommand(volunteerId, shiftId), List.of());
    }

    public synchronized EditResult swap(String volA, String shiftA, String volB, String shiftB) {
        return run(new SwapCommand(validator, volA, shiftA, volB, shiftB), List.of());
    }

    public synchronized EditResult clear() {
        return run(SnapshotCommand.of("CLEAR", "Cleared all assignments", Schedule::clearAll), List.of());
    }

    public synchronized EditResult undo() {
        Optional<EditCommand> c = history.undo(schedule);
        c.ifPresent(cmd -> persist("UNDO", cmd.description()));
        return result(c.map(cmd -> "Undid: " + cmd.description()).orElse("Nothing to undo"), List.of());
    }

    public synchronized EditResult redo() {
        Optional<EditCommand> c = history.redo(schedule);
        c.ifPresent(cmd -> persist("REDO", cmd.description()));
        return result(c.map(cmd -> "Redid: " + cmd.description()).orElse("Nothing to redo"), List.of());
    }

    /** Swap in a brand-new dataset (CSV/JSON import). Clears history. */
    public synchronized EditResult replaceDataset(Collection<Volunteer> vols, Collection<Shift> shifts) {
        Schedule fresh = new Schedule(vols, shifts);
        if (store != null) {
            store.replaceDataset(vols, shifts);
        }
        schedule = fresh;
        history.clear();
        activity.clear();
        persist("IMPORT", "Imported " + vols.size() + " volunteers and " + shifts.size() + " shifts");
        return result("Imported " + vols.size() + " volunteers and " + shifts.size() + " shifts", List.of());
    }

    /** Adds a volunteer entered on the dashboard; the id (V01, V02, ...) is generated here. Clears history. */
    public synchronized EditResult addVolunteer(Volunteer.Builder builder) {
        Volunteer v = builder.id(nextVolunteerId()).build();
        List<Volunteer> vols = new ArrayList<>(schedule.volunteers());
        vols.add(v);
        rebuild(vols, schedule.shifts(), schedule.allAssignments());
        if (store != null) {
            store.saveVolunteer(v);
        }
        persist("ADD_VOLUNTEER", "Added " + v.name() + " (" + v.id() + ")");
        return result("Added " + v.name(), List.of());
    }

    /** Removes a volunteer and all of their assignments. Clears history (old snapshots still reference them). */
    public synchronized EditResult removeVolunteer(String volunteerId) {
        Volunteer v = schedule.volunteer(volunteerId);
        List<Volunteer> vols = schedule.volunteers().stream().filter(x -> !x.equals(v)).toList();
        List<Assignment> keep = schedule.allAssignments().stream().filter(a -> !a.volunteerId().equals(volunteerId)).toList();
        rebuild(vols, schedule.shifts(), keep);
        if (store != null) {
            store.deleteVolunteer(volunteerId);
        }
        persist("REMOVE_VOLUNTEER", "Removed " + v.name() + " (" + v.id() + ")");
        return result("Removed " + v.name(), List.of());
    }

    /** Adds an event (shift) entered on the dashboard; the id (S01, S02, ...) is generated here. Clears history. */
    public synchronized EditResult addShift(Shift.Builder builder) {
        Shift s = builder.id(nextId("S", schedule.shifts().stream().map(Shift::id).toList())).build();
        List<Shift> shifts = new ArrayList<>(schedule.shifts());
        shifts.add(s);
        rebuild(schedule.volunteers(), shifts, schedule.allAssignments());
        if (store != null) {
            store.saveShift(s);
        }
        persist("ADD_SHIFT", "Added " + s.activity() + " (" + s.id() + ")");
        return result("Added " + s.activity(), List.of());
    }

    /** Removes an event and everyone's assignment to it. Clears history. */
    public synchronized EditResult removeShift(String shiftId) {
        Shift s = schedule.shift(shiftId);
        List<Shift> shifts = schedule.shifts().stream().filter(x -> !x.id().equals(shiftId)).toList();
        List<Assignment> keep = schedule.allAssignments().stream().filter(a -> !a.shiftId().equals(shiftId)).toList();
        rebuild(schedule.volunteers(), shifts, keep);
        if (store != null) {
            store.deleteShift(shiftId);
        }
        persist("REMOVE_SHIFT", "Removed " + s.activity() + " (" + s.id() + ")");
        return result("Removed " + s.activity(), List.of());
    }

    private void rebuild(Collection<Volunteer> vols, Collection<Shift> shifts, List<Assignment> keep) {
        Schedule fresh = new Schedule(List.copyOf(vols), List.copyOf(shifts)); // releases everyone's bookings
        keep.forEach(fresh::assign);
        schedule = fresh;
        history.clear();
    }

    private String nextVolunteerId() {
        return nextId("V", schedule.volunteers().stream().map(Volunteer::id).toList());
    }

    /** Next free id with the given prefix: V01, V02, ... */
    private static String nextId(String prefix, List<String> existing) {
        int max = existing.stream()
                .filter(id -> id.matches(prefix + "\\d+"))
                .mapToInt(id -> Integer.parseInt(id.substring(prefix.length())))
                .max().orElse(0);
        return String.format("%s%02d", prefix, max + 1);
    }

    private EditResult run(EditCommand cmd, List<Violation> overridden) {
        history.execute(cmd, schedule);
        record(cmd);
        return result(cmd.description(), overridden);
    }

    private void record(EditCommand cmd) {
        persist(cmd.action(), cmd.description());
    }

    private void persist(String action, String description) {
        activity.push(new LogEntry(LocalDateTime.now().withNano(0), action, description));
        while (activity.size() > 40) activity.removeLast();
        if (store != null) {
            store.saveAssignments(schedule);
            store.audit(action, description);
        }
    }

    private EditResult result(String msg, List<Violation> overridden) {
        return new EditResult(msg, overridden, validator.revalidate(schedule));
    }

    // ---- queries -------------------------------------------------------------------------

    public synchronized Schedule schedule() { return schedule; }
    public ValidationEngine validator() { return validator; }
    public DiagnosticsService diagnostics() { return diagnostics; }
    public MetricsService metrics() { return metrics; }
    public StrategyRegistry strategies() { return strategies; }
    public CommandHistory history() { return history; }
    public FairnessStrategy currentStrategy() { return currentStrategy; }
    public Optional<ShiftBalancerService.BalanceResult> lastBalance() { return Optional.ofNullable(lastBalance); }
    public synchronized List<LogEntry> activity() { return List.copyOf(activity); }
    public Optional<DataStore> store() { return Optional.ofNullable(store); }
}
