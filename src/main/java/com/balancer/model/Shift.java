package com.balancer.model;

import java.time.LocalDateTime;

/** An immutable shift demand: an activity at a time that needs N volunteers with a skill. */
public final class Shift {

    private final String id;
    private final String activity;
    private final String location;
    private final TimeSlot slot;
    private final Skill requiredSkill;
    private final int minSkillLevel;
    private final int volunteersNeeded;
    private final int priority;

    private Shift(Builder b) {
        this.id = b.id;
        this.activity = b.activity;
        this.location = b.location;
        this.slot = b.slot;
        this.requiredSkill = b.requiredSkill;
        this.minSkillLevel = b.minSkillLevel;
        this.volunteersNeeded = b.volunteersNeeded;
        this.priority = b.priority;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String activity() { return activity; }
    public String location() { return location; }
    public TimeSlot slot() { return slot; }
    public Skill requiredSkill() { return requiredSkill; }
    public int minSkillLevel() { return minSkillLevel; }
    public int volunteersNeeded() { return volunteersNeeded; }
    /** 1 = low, 2 = normal, 3 = critical. Higher priority shifts are filled first. */
    public int priority() { return priority; }
    public double hours() { return slot.hours(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shift s && s.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return activity + " [" + id + "] " + slot;
    }

    public static final class Builder {
        private String id;
        private String activity;
        private String location = "";
        private TimeSlot slot;
        private Skill requiredSkill;
        private int minSkillLevel = 1;
        private int volunteersNeeded = 1;
        private int priority = 2;

        public Builder id(String id) { this.id = id; return this; }
        public Builder activity(String activity) { this.activity = activity; return this; }
        public Builder location(String location) { this.location = location == null ? "" : location; return this; }
        public Builder slot(TimeSlot slot) { this.slot = slot; return this; }
        public Builder slot(LocalDateTime start, LocalDateTime end) { return slot(new TimeSlot(start, end)); }
        public Builder slot(String start, String end) { return slot(TimeSlot.parse(start, end)); }
        public Builder requiredSkill(String skill) { this.requiredSkill = Skill.of(skill); return this; }
        public Builder requiredSkill(Skill skill) { this.requiredSkill = skill; return this; }
        public Builder minSkillLevel(int level) { this.minSkillLevel = level; return this; }
        public Builder volunteersNeeded(int n) { this.volunteersNeeded = n; return this; }
        public Builder priority(int p) { this.priority = p; return this; }

        public Shift build() {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Shift id is required");
            if (activity == null || activity.isBlank()) throw new IllegalArgumentException("Shift activity is required (" + id + ")");
            if (slot == null) throw new IllegalArgumentException("Shift time slot is required (" + id + ")");
            if (requiredSkill == null) throw new IllegalArgumentException("Shift required skill is required (" + id + ")");
            if (minSkillLevel < 1 || minSkillLevel > 5) throw new IllegalArgumentException("minSkillLevel must be 1-5 (" + id + ")");
            if (volunteersNeeded < 1) throw new IllegalArgumentException("volunteersNeeded must be >= 1 (" + id + ")");
            if (priority < 1 || priority > 3) throw new IllegalArgumentException("priority must be 1-3 (" + id + ")");
            id = id.trim();
            activity = activity.trim();
            return new Shift(this);
        }
    }
}
