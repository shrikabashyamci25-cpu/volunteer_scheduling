package com.balancer.model;

import com.balancer.util.TimeOverlapChecker;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A volunteer profile. Profile data is immutable; the only mutable state is the set of booked
 * time slots, and that can only be changed by {@link Schedule} (package-private methods),
 * so hours can never drift out of sync with the actual assignments.
 */
public final class Volunteer {

    private final String id;
    private final String name;
    private final String email;
    private final int maxWeeklyHours;
    private final Map<Skill, Integer> skills;
    private final List<TimeSlot> availability;

    /** Sorted by start time. Never contains overlapping slots (enforced by Schedule). */
    private final NavigableSet<TimeSlot> booked = new TreeSet<>();

    private Volunteer(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.email = b.email;
        this.maxWeeklyHours = b.maxWeeklyHours;
        this.skills = Collections.unmodifiableMap(new TreeMap<>(b.skills));
        this.availability = List.copyOf(TimeOverlapChecker.merge(b.availability));
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- profile -------------------------------------------------------------------------

    public String id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public int maxWeeklyHours() { return maxWeeklyHours; }
    public Map<Skill, Integer> skills() { return skills; }
    public List<TimeSlot> availability() { return availability; }

    /** 0 when the volunteer does not have the skill, otherwise 1..5. */
    public int skillLevel(Skill skill) {
        return skills.getOrDefault(skill, 0);
    }

    public boolean hasSkill(Skill skill, int minLevel) {
        return skillLevel(skill) >= Math.max(1, minLevel);
    }

    public boolean isAvailableFor(TimeSlot slot) {
        return availability.stream().anyMatch(window -> window.contains(slot));
    }

    // ---- booking state -------------------------------------------------------------------

    /**
     * O(log n) double-booking check. Booked slots never overlap, so their end times are sorted too:
     * only the last slot starting before {@code slot.end()} can possibly overlap.
     */
    public boolean hasTimeConflict(TimeSlot slot) {
        TimeSlot probe = new TimeSlot(slot.end(), slot.end().plusNanos(1));
        TimeSlot candidate = booked.lower(probe);
        return candidate != null && candidate.overlaps(slot);
    }

    public double hoursInWeek(LocalDate weekStart) {
        return booked.stream().filter(b -> b.weekStart().equals(weekStart)).mapToDouble(TimeSlot::hours).sum();
    }

    public boolean wouldExceedCap(TimeSlot slot) {
        return hoursInWeek(slot.weekStart()) + slot.hours() > maxWeeklyHours + 1e-9;
    }

    public double totalAssignedHours() {
        return booked.stream().mapToDouble(TimeSlot::hours).sum();
    }

    public int bookedShiftCount() {
        return booked.size();
    }

    public List<TimeSlot> bookedSlots() {
        return List.copyOf(booked);
    }

    void book(TimeSlot slot) {
        if (hasTimeConflict(slot)) {
            throw new IllegalStateException(name + " is already booked during " + slot);
        }
        booked.add(slot);
    }

    void release(TimeSlot slot) {
        booked.remove(slot);
    }

    void releaseAll() {
        booked.clear();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Volunteer v && v.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }

    // ---- builder -------------------------------------------------------------------------

    public static final class Builder {
        private String id;
        private String name;
        private String email = "";
        private int maxWeeklyHours = 10;
        private final Map<Skill, Integer> skills = new TreeMap<>();
        private final List<TimeSlot> availability = new ArrayList<>();

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder email(String email) { this.email = email == null ? "" : email; return this; }
        public Builder maxWeeklyHours(int h) { this.maxWeeklyHours = h; return this; }

        public Builder skill(String skill, int level) {
            return skill(Skill.of(skill), level);
        }

        public Builder skill(Skill skill, int level) {
            if (level < 1 || level > 5) {
                throw new IllegalArgumentException("Skill level must be 1-5, got " + level + " for " + skill);
            }
            skills.put(skill, level);
            return this;
        }

        public Builder available(TimeSlot slot) {
            availability.add(slot);
            return this;
        }

        public Builder available(LocalDateTime start, LocalDateTime end) {
            return available(new TimeSlot(start, end));
        }

        public Builder available(String start, String end) {
            return available(TimeSlot.parse(start, end));
        }

        public Volunteer build() {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Volunteer id is required");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Volunteer name is required (" + id + ")");
            if (maxWeeklyHours <= 0) throw new IllegalArgumentException("maxWeeklyHours must be > 0 for " + id);
            id = id.trim();
            name = name.trim();
            Objects.requireNonNull(email);
            return new Volunteer(this);
        }
    }
}
