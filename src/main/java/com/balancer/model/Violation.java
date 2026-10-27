package com.balancer.model;

/** One broken constraint, with a human-readable explanation. */
public record Violation(ConflictReason reason, String volunteerId, String shiftId, String message) {

    public boolean overridable() {
        return reason.overridable();
    }
}
