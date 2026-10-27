package com.balancer.model;

import java.util.Objects;

/**
 * Immutable link between a volunteer and a shift.
 * {@code manual = true} means an administrator placed it; the auto-balancer keeps (pins) those.
 */
public record Assignment(String volunteerId, String shiftId, boolean manual) {

    public Assignment {
        Objects.requireNonNull(volunteerId, "volunteerId");
        Objects.requireNonNull(shiftId, "shiftId");
    }

    public static Assignment auto(String volunteerId, String shiftId) {
        return new Assignment(volunteerId, shiftId, false);
    }

    public static Assignment manual(String volunteerId, String shiftId) {
        return new Assignment(volunteerId, shiftId, true);
    }

    public String key() {
        return key(volunteerId, shiftId);
    }

    public static String key(String volunteerId, String shiftId) {
        return volunteerId + "@" + shiftId;
    }
}
