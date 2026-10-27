package com.balancer.web;

import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Violation;
import com.balancer.model.Volunteer;
import com.balancer.service.ScheduleEditor;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Builds the JSON-friendly view models the dashboard needs. */
final class StateMapper {

    private StateMapper() {
    }

    record ViolationView(String reason, String label, boolean overridable, String volunteerId, String shiftId, String message) {
        static ViolationView of(Violation v) {
            return new ViolationView(v.reason().name(), v.reason().label(), v.overridable(), v.volunteerId(), v.shiftId(), v.message());
        }
    }

    record AssignedView(String id, String name, boolean manual) {
    }

    record ShiftView(String id, String activity, String location, String start, String end, String label,
                     double hours, String skill, int minLevel, int needed, int priority, String status,
                     List<AssignedView> assigned) {
    }

    record SlotView(String start, String end) {
    }

    record VolunteerView(String id, String name, String email, int maxWeeklyHours, double hours, int shifts,
                         Map<String, Integer> skills, List<SlotView> availability) {
    }

    record CandidateView(String id, String name, double hours, int maxWeeklyHours, int level, boolean eligible,
                         boolean forceable, List<ViolationView> violations) {
    }

    static Map<String, Object> state(ScheduleEditor editor) {
        Schedule s = editor.schedule();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", editor.currentStrategy().id());
        out.put("strategies", editor.strategies().all().stream()
                .map(st -> Map.of("id", st.id(), "name", st.displayName(), "description", st.description())).toList());
        out.put("days", new TreeSet<LocalDate>(s.shifts().stream().map(x -> x.slot().day()).toList()).stream().map(LocalDate::toString).toList());
        out.put("skills", s.allSkills().stream().map(Object::toString).sorted().toList());
        out.put("volunteers", s.volunteers().stream().map(StateMapper::volunteer).toList());
        out.put("shifts", s.shifts().stream().sorted(Comparator.comparing(Shift::slot)).map(x -> shift(x, s)).toList());
        out.put("metrics", editor.metrics().compute(s));
        out.put("diagnostics", editor.diagnostics().diagnose(s));
        out.put("violations", editor.validator().revalidate(s).stream().map(ViolationView::of).toList());
        out.put("history", Map.of(
                "canUndo", editor.history().canUndo(),
                "canRedo", editor.history().canRedo(),
                "undoLabel", editor.history().nextUndo().orElse(""),
                "redoLabel", editor.history().nextRedo().orElse("")));
        out.put("activity", editor.activity());
        out.put("lastBalance", editor.lastBalance().orElse(null));
        return out;
    }

    static VolunteerView volunteer(Volunteer v) {
        Map<String, Integer> skills = new LinkedHashMap<>();
        v.skills().forEach((k, lvl) -> skills.put(k.name(), lvl));
        return new VolunteerView(v.id(), v.name(), v.email(), v.maxWeeklyHours(),
                Math.round(v.totalAssignedHours() * 100) / 100.0, v.bookedShiftCount(), skills,
                v.availability().stream().map(t -> new SlotView(t.start().toString(), t.end().toString())).toList());
    }

    static ShiftView shift(Shift x, Schedule s) {
        List<AssignedView> assigned = s.volunteersOn(x.id()).stream()
                .map(v -> new AssignedView(v.id(), v.name(), s.assignment(v.id(), x.id()).map(a -> a.manual()).orElse(false)))
                .sorted(Comparator.comparing(AssignedView::name))
                .toList();
        return new ShiftView(x.id(), x.activity(), x.location(), x.slot().start().toString(), x.slot().end().toString(),
                x.slot().label(), x.hours(), x.requiredSkill().name(), x.minSkillLevel(), x.volunteersNeeded(),
                x.priority(), s.status(x).name(), assigned);
    }

    static List<CandidateView> candidates(ScheduleEditor editor, String shiftId) {
        Schedule s = editor.schedule();
        Shift shift = s.shift(shiftId);
        return s.volunteers().stream()
                .filter(v -> !s.isAssigned(v.id(), shiftId))
                .map(v -> {
                    List<Violation> vs = editor.validator().check(v, shift, s).stream()
                            .filter(x -> x.reason() != ConflictReason.SHIFT_FULL).toList();
                    boolean forceable = vs.stream().allMatch(Violation::overridable);
                    return new CandidateView(v.id(), v.name(), Math.round(v.totalAssignedHours() * 100) / 100.0,
                            v.maxWeeklyHours(), v.skillLevel(shift.requiredSkill()), vs.isEmpty(), forceable,
                            vs.stream().map(ViolationView::of).toList());
                })
                .sorted(Comparator.comparing((CandidateView c) -> !c.eligible())
                        .thenComparingInt(c -> c.violations().size())
                        .thenComparingDouble(CandidateView::hours)
                        .thenComparing(CandidateView::name))
                .toList();
    }
}
