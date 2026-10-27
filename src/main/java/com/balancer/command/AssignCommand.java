package com.balancer.command;

import com.balancer.model.Assignment;
import com.balancer.model.Schedule;
import com.balancer.model.Violation;
import com.balancer.service.ValidationEngine;

import java.util.List;

/**
 * Manually assign a volunteer. With {@code force = true}, overridable rules (skill, availability,
 * hour cap) are bypassed — but double-booking and over-filling a shift are always blocked.
 */
public final class AssignCommand implements EditCommand {

    private final ValidationEngine validator;
    private final String volunteerId;
    private final String shiftId;
    private final boolean force;
    private String label = "";
    private List<Violation> overridden = List.of();

    public AssignCommand(ValidationEngine validator, String volunteerId, String shiftId, boolean force) {
        this.validator = validator;
        this.volunteerId = volunteerId;
        this.shiftId = shiftId;
        this.force = force;
    }

    @Override
    public void execute(Schedule schedule) {
        var v = schedule.volunteer(volunteerId);
        var s = schedule.shift(shiftId);
        List<Violation> violations = validator.check(v, s, schedule);
        List<Violation> blocking = force ? violations.stream().filter(x -> !x.overridable()).toList() : violations;
        if (!blocking.isEmpty()) {
            throw new EditRejectedException(blocking);
        }
        overridden = violations;
        schedule.assign(Assignment.manual(volunteerId, shiftId));
        label = (force && !violations.isEmpty() ? "Force-assign " : "Assign ") + v.name() + " → " + s.activity();
    }

    @Override
    public void undo(Schedule schedule) {
        schedule.unassign(volunteerId, shiftId);
    }

    public List<Violation> overriddenViolations() {
        return overridden;
    }

    @Override
    public String description() {
        return label;
    }

    @Override
    public String action() {
        return "ASSIGN";
    }
}
