package com.balancer.util;

import com.balancer.model.TimeSlot;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Pure helper functions for half-open time interval maths: [start, end).
 * Two intervals overlap when startA &lt; endB AND endA &gt; startB.
 * Back-to-back shifts (one ends at 12:00, the next starts at 12:00) do NOT overlap.
 */
public final class TimeOverlapChecker {

    private TimeOverlapChecker() {
    }

    public static boolean overlaps(LocalDateTime startA, LocalDateTime endA,
                                   LocalDateTime startB, LocalDateTime endB) {
        return startA.isBefore(endB) && endA.isAfter(startB);
    }

    public static boolean overlaps(TimeSlot a, TimeSlot b) {
        return overlaps(a.start(), a.end(), b.start(), b.end());
    }

    /** True when {@code inner} lies completely inside {@code outer}. */
    public static boolean contains(TimeSlot outer, TimeSlot inner) {
        return !inner.start().isBefore(outer.start()) && !inner.end().isAfter(outer.end());
    }

    public static boolean anyOverlap(Collection<TimeSlot> slots, TimeSlot candidate) {
        return slots.stream().anyMatch(s -> overlaps(s, candidate));
    }

    /** Length of the shared part of two intervals (zero if they do not overlap). */
    public static Duration overlapDuration(TimeSlot a, TimeSlot b) {
        if (!overlaps(a, b)) {
            return Duration.ZERO;
        }
        LocalDateTime start = a.start().isAfter(b.start()) ? a.start() : b.start();
        LocalDateTime end = a.end().isBefore(b.end()) ? a.end() : b.end();
        return Duration.between(start, end);
    }

    /**
     * Merges overlapping or touching windows, e.g. 08:00-12:00 + 12:00-16:00 becomes 08:00-16:00,
     * so a 10:00-14:00 shift is correctly seen as "inside" the volunteer's availability.
     */
    public static List<TimeSlot> merge(Collection<TimeSlot> windows) {
        List<TimeSlot> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.naturalOrder());
        List<TimeSlot> merged = new ArrayList<>();
        for (TimeSlot w : sorted) {
            if (merged.isEmpty()) {
                merged.add(w);
                continue;
            }
            TimeSlot last = merged.get(merged.size() - 1);
            if (!w.start().isAfter(last.end())) {
                LocalDateTime end = w.end().isAfter(last.end()) ? w.end() : last.end();
                merged.set(merged.size() - 1, new TimeSlot(last.start(), end));
            } else {
                merged.add(w);
            }
        }
        return merged;
    }
}
