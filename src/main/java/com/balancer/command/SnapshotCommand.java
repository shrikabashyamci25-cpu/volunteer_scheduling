package com.balancer.command;

import com.balancer.model.Assignment;
import com.balancer.model.Schedule;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Generic command for bulk operations (run balancer, clear schedule). It snapshots all
 * assignments before running, so undo is a simple restore.
 */
public final class SnapshotCommand implements EditCommand {

    private final String action;
    private final Function<Schedule, String> operation;
    private List<Assignment> before = List.of();
    private String label;

    public SnapshotCommand(String action, Function<Schedule, String> operation) {
        this.action = action;
        this.operation = operation;
    }

    public static SnapshotCommand of(String action, String label, Consumer<Schedule> op) {
        return new SnapshotCommand(action, s -> {
            op.accept(s);
            return label;
        });
    }

    @Override
    public void execute(Schedule schedule) {
        before = schedule.allAssignments();
        label = operation.apply(schedule);
    }

    @Override
    public void undo(Schedule schedule) {
        schedule.restore(before);
    }

    @Override
    public String description() {
        return label;
    }

    @Override
    public String action() {
        return action;
    }
}
