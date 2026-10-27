package com.balancer.model;

/** Staffing state of a shift. */
public enum ShiftStatus {
    OPEN("Open"),
    PARTIALLY_STAFFED("Partially staffed"),
    FULLY_STAFFED("Fully staffed");

    private final String label;

    ShiftStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ShiftStatus of(int assigned, int needed) {
        if (assigned <= 0) {
            return OPEN;
        }
        return assigned >= needed ? FULLY_STAFFED : PARTIALLY_STAFFED;
    }
}
