package com.balancer.strategy;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

import java.util.Comparator;

/** Quality first: the most skilled eligible volunteer wins; hours are only a tie-breaker. */
public final class StrictPriorityStrategy implements FairnessStrategy {

    @Override
    public String id() {
        return "strict-priority";
    }

    @Override
    public String displayName() {
        return "Best-skilled first";
    }

    @Override
    public String description() {
        return "Highest skill level first, then fewest hours. Better quality, less even workload.";
    }

    @Override
    public Comparator<Volunteer> candidateOrder(Shift shift) {
        return Comparator.comparingInt((Volunteer v) -> v.skillLevel(shift.requiredSkill())).reversed()
                .thenComparingDouble(Volunteer::totalAssignedHours)
                .thenComparing(Volunteer::id);
    }

    @Override
    public boolean allowsRefinement() {
        return false;
    }
}
