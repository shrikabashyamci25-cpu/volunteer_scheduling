package com.balancer.command;

import com.balancer.model.Assignment;
import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.model.Violation;
import com.balancer.service.ValidationEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Swap two volunteers between two shifts: A on shift X and B on shift Y become B on X and A on Y.
 * Both new placements are fully validated; if either fails, the schedule is restored.
 */
public final class SwapCommand implements EditCommand {

    private final ValidationEngine validator;
    private final String volunteerA;
    private final String shiftA;
    private final String volunteerB;
    private final String shiftB;
    private Assignment originalA;
    private Assignment originalB;
    private String label = "";

    public SwapCommand(ValidationEngine validator, String volunteerA, String shiftA, String volunteerB, String shiftB) {
        this.validator = validator;
        this.volunteerA = volunteerA;
        this.shiftA = shiftA;
        this.volunteerB = volunteerB;
        this.shiftB = shiftB;
    }

    @Override
    public void execute(Schedule schedule) {
        var vA = schedule.volunteer(volunteerA);
        var vB = schedule.volunteer(volunteerB);
        var sA = schedule.shift(shiftA);
        var sB = schedule.shift(shiftB);
        if (shiftA.equals(shiftB) || volunteerA.equals(volunteerB)) {
            throw new EditRejectedException(List.of(new Violation(ConflictReason.ALREADY_ASSIGNED, volunteerA, shiftA,
                    "Pick two different volunteers on two different shifts to swap")));
        }
        originalA = schedule.assignment(volunteerA, shiftA).orElseThrow(() -> notOn(vA.name(), sA.activity(), volunteerA, shiftA));
        originalB = schedule.assignment(volunteerB, shiftB).orElseThrow(() -> notOn(vB.name(), sB.activity(), volunteerB, shiftB));

        schedule.unassign(volunteerA, shiftA);
        schedule.unassign(volunteerB, shiftB);

        List<Violation> problems = new ArrayList<>(validator.check(vA, sB, schedule));
        if (problems.isEmpty()) {
            schedule.assign(Assignment.manual(volunteerA, shiftB));
            problems.addAll(validator.check(vB, sA, schedule));
            if (!problems.isEmpty()) {
                schedule.unassign(volunteerA, shiftB);
            }
        }
        if (!problems.isEmpty()) {
            schedule.assign(originalA);
            schedule.assign(originalB);
            throw new EditRejectedException(problems);
        }
        schedule.assign(Assignment.manual(volunteerB, shiftA));
        label = "Swap " + vA.name() + " ⇄ " + vB.name();
    }

    private static EditRejectedException notOn(String who, String what, String vid, String sid) {
        return new EditRejectedException(List.of(new Violation(ConflictReason.ALREADY_ASSIGNED, vid, sid, who + " is not on " + what)));
    }

    @Override
    public void undo(Schedule schedule) {
        schedule.unassign(volunteerA, shiftB);
        schedule.unassign(volunteerB, shiftA);
        schedule.assign(originalA);
        schedule.assign(originalB);
    }

    @Override
    public String description() {
        return label;
    }

    @Override
    public String action() {
        return "SWAP";
    }
}
