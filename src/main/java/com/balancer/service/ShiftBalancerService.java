package com.balancer.service;

import com.balancer.model.Assignment;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Volunteer;
import com.balancer.strategy.FairnessStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The workload balancing engine.
 *
 * <ol>
 *   <li>Drop previous auto assignments (manual ones stay pinned).</li>
 *   <li>Order shifts: critical priority first, then the hardest-to-fill (fewest eligible people),
 *       then chronologically. Filling scarce shifts first stops easy shifts from "using up" the
 *       only qualified people.</li>
 *   <li>For each shift, filter candidates with the ValidationEngine (stream pipeline) and push them
 *       into a {@link PriorityQueue} ordered by the chosen {@link FairnessStrategy}. Poll the head
 *       until the shift is full. The heap is rebuilt per shift, so it always reflects the latest
 *       cumulative hours.</li>
 *   <li>Optional refinement: move single assignments from the busiest volunteers to eligible
 *       lighter ones whenever that narrows the gap — this lowers hour variance further.</li>
 * </ol>
 */
public final class ShiftBalancerService {

    private static final int MAX_REFINEMENT_ROUNDS = 200;

    private final ValidationEngine validator;

    public ShiftBalancerService(ValidationEngine validator) {
        this.validator = validator;
    }

    public record BalanceResult(String strategyId, int assignmentsMade, int refinementMoves,
                                int keptManual, long elapsedMicros) {
    }

    public BalanceResult balance(Schedule schedule, FairnessStrategy strategy) {
        long t0 = System.nanoTime();
        schedule.clearAutomatic();
        int keptManual = schedule.allAssignments().size();

        Map<String, Long> eligibleCount = schedule.shifts().stream()
                .collect(Collectors.toMap(Shift::id, s -> countEligible(s, schedule)));

        List<Shift> order = schedule.shifts().stream()
                .sorted(Comparator.comparingInt(Shift::priority).reversed()
                        .thenComparingLong(s -> eligibleCount.get(s.id()))
                        .thenComparing(Shift::slot))
                .toList();

        int made = fill(schedule, strategy, order);
        int moves = 0;
        if (strategy.allowsRefinement()) {
            // Refinement frees hours on busy people, which can unlock new placements: alternate until stable.
            for (int pass = 0; pass < 10; pass++) {
                int m = refine(schedule);
                moves += m;
                int extra = fill(schedule, strategy, order);
                made += extra;
                if (m == 0 && extra == 0) break;
            }
        }
        return new BalanceResult(strategy.id(), made, moves, keptManual, (System.nanoTime() - t0) / 1000);
    }

    /** Greedy min-heap allocation over the shifts in the given order. Returns assignments made. */
    private int fill(Schedule schedule, FairnessStrategy strategy, List<Shift> order) {
        int made = 0;
        for (Shift shift : order) {
            int open = shift.volunteersNeeded() - schedule.assignedCount(shift.id());
            if (open <= 0) continue;

            PriorityQueue<Volunteer> heap = new PriorityQueue<>(strategy.candidateOrder(shift));
            schedule.volunteersWithSkill(shift.requiredSkill()).stream()
                    .filter(v -> validator.isEligible(v, shift, schedule))
                    .forEach(heap::offer);

            while (open > 0 && !heap.isEmpty()) {
                Volunteer best = heap.poll();
                if (!validator.isEligible(best, shift, schedule)) continue;
                schedule.assign(Assignment.auto(best.id(), shift.id()));
                open--;
                made++;
            }
        }
        return made;
    }

    private long countEligible(Shift shift, Schedule schedule) {
        return schedule.volunteersWithSkill(shift.requiredSkill()).stream()
                .filter(v -> validator.isEligible(v, shift, schedule))
                .count();
    }

    /**
     * Local search: take an auto assignment from a heavy volunteer and give it to an eligible lighter
     * one if, after the move, the lighter person still has fewer hours than the heavy one had before.
     * Each accepted move strictly reduces the sum of squared hours, so the loop always terminates.
     */
    private int refine(Schedule schedule) {
        int moves = 0;
        Map<String, Volunteer> byId = schedule.volunteers().stream()
                .collect(Collectors.toMap(Volunteer::id, Function.identity()));
        for (int round = 0; round < MAX_REFINEMENT_ROUNDS; round++) {
            boolean improved = false;
            List<Assignment> heaviestFirst = schedule.allAssignments().stream()
                    .filter(a -> !a.manual())
                    .sorted(Comparator.comparingDouble((Assignment a) -> byId.get(a.volunteerId()).totalAssignedHours()).reversed())
                    .toList();

            for (Assignment a : heaviestFirst) {
                Volunteer heavy = byId.get(a.volunteerId());
                Shift shift = schedule.shift(a.shiftId());
                double heavyHours = heavy.totalAssignedHours();

                schedule.unassign(heavy.id(), shift.id());
                Volunteer lighter = schedule.volunteersWithSkill(shift.requiredSkill()).stream()
                        .filter(v -> !v.equals(heavy))
                        .filter(v -> v.totalAssignedHours() + shift.hours() < heavyHours - 1e-9)
                        .filter(v -> validator.isEligible(v, shift, schedule))
                        .min(Comparator.comparingDouble(Volunteer::totalAssignedHours).thenComparing(Volunteer::id))
                        .orElse(null);

                if (lighter != null) {
                    schedule.assign(Assignment.auto(lighter.id(), shift.id()));
                    moves++;
                    improved = true;
                    break; // ordering changed, start a new round
                }
                schedule.assign(a); // put it back
            }
            if (!improved) break;
        }
        return moves;
    }
}
