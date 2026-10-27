package com.balancer.strategy;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

import java.util.Comparator;

/**
 * Proportional fairness: someone who offered 20 h/week should do more than someone who offered 6.
 * Orders by utilisation (hours used ÷ weekly cap) ascending.
 */
public final class CapacityRatioStrategy implements FairnessStrategy {

    @Override
    public String id() {
        return "capacity-ratio";
    }

    @Override
    public String displayName() {
        return "Proportional to capacity";
    }

    @Override
    public String description() {
        return "Lowest % of weekly cap used first, so load scales with what each person offered.";
    }

    @Override
    public Comparator<Volunteer> candidateOrder(Shift shift) {
        return Comparator.comparingDouble((Volunteer v) ->
                        (v.hoursInWeek(shift.slot().weekStart()) + shift.hours()) / v.maxWeeklyHours())
                .thenComparing(Comparator.comparingInt((Volunteer v) -> v.skillLevel(shift.requiredSkill())).reversed())
                .thenComparing(Volunteer::id);
    }

    @Override
    public boolean allowsRefinement() {
        return false; // refinement equalises absolute hours, which would fight this rule
    }
}
