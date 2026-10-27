package com.balancer.model;

/**
 * Every reason a volunteer can be rejected for a shift.
 * {@code overridable} = an admin may force-assign despite it. Physical impossibilities
 * (being in two places at once, a full shift) can never be overridden.
 */
public enum ConflictReason {
    MISSING_SKILL("Skill deficit", true),
    SKILL_LEVEL_TOO_LOW("Skill level too low", true),
    NOT_AVAILABLE("Outside availability", true),
    HOUR_CAP_REACHED("Weekly hour cap reached", true),
    TIME_OVERLAP_CONFLICT("Time overlap conflict", false),
    ALREADY_ASSIGNED("Already on this shift", false),
    SHIFT_FULL("Shift already full", false);

    private final String label;
    private final boolean overridable;

    ConflictReason(String label, boolean overridable) {
        this.label = label;
        this.overridable = overridable;
    }

    public String label() {
        return label;
    }

    public boolean overridable() {
        return overridable;
    }
}
