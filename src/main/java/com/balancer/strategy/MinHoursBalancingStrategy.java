package com.balancer.strategy;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

import java.util.Comparator;

/**
 * Default rule — min-heap on cumulative hours. Multi-tier comparator:
 * 1. fewest hours assigned so far (spreads work evenly → low variance)
 * 2. most spare capacity left this week
 * 3. higher skill level for the shift's skill
 * 4. id (deterministic tie-break)
 */
public final class MinHoursBalancingStrategy implements FairnessStrategy {

    @Override
    public String id() {
        return "min-hours";
    }

    @Override
    public String displayName() {
        return "Min-hours fairness";
    }

    @Override
    public String description() {
        return "Lowest cumulative hours first, then most spare capacity, then higher skill.";
    }

    @Override
    public Comparator<Volunteer> candidateOrder(Shift shift) {
        return Comparator.comparingDouble(Volunteer::totalAssignedHours)
                .thenComparing(Comparator.comparingDouble(
                        (Volunteer v) -> v.maxWeeklyHours() - v.hoursInWeek(shift.slot().weekStart())).reversed())
                .thenComparing(Comparator.comparingInt((Volunteer v) -> v.skillLevel(shift.requiredSkill())).reversed())
                .thenComparing(Volunteer::id);
    }
}
