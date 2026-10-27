package com.balancer.command;

import com.balancer.model.Assignment;
import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.model.Violation;

import java.util.List;

/** Remove one assignment; undo puts back exactly the same assignment (keeping its manual flag). */
public final class RemoveCommand implements EditCommand {

    private final String volunteerId;
    private final String shiftId;
    private Assignment removed;
    private String label = "";

    public RemoveCommand(String volunteerId, String shiftId) {
        this.volunteerId = volunteerId;
        this.shiftId = shiftId;
    }

    @Override
    public void execute(Schedule schedule) {
        var v = schedule.volunteer(volunteerId);
        var s = schedule.shift(shiftId);
        removed = schedule.unassign(volunteerId, shiftId).orElseThrow(() -> new EditRejectedException(List.of(
                new Violation(ConflictReason.ALREADY_ASSIGNED, volunteerId, shiftId, v.name() + " is not on " + s.activity()))));
        label = "Remove " + v.name() + " from " + s.activity();
    }

    @Override
    public void undo(Schedule schedule) {
        schedule.assign(removed);
    }

    @Override
    public String description() {
        return label;
    }

    @Override
    public String action() {
        return "REMOVE";
    }
}
