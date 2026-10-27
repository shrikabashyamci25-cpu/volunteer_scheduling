package com.balancer.strategy;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

import java.util.Comparator;

/**
 * Strategy pattern: a balancing rule decides which eligible volunteer should be picked first
 * for a shift. The balancer engine puts candidates in a PriorityQueue ordered by this comparator,
 * so the head of the queue (the "minimum") is always the next best pick.
 */
public interface FairnessStrategy {

    String id();

    String displayName();

    String description();

    Comparator<Volunteer> candidateOrder(Shift shift);

    /** Whether the engine may run its variance-reducing refinement pass after greedy allocation. */
    default boolean allowsRefinement() {
        return true;
    }
}
