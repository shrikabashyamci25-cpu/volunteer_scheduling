package com.balancer.model;

import com.balancer.util.TimeOverlapChecker;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/** Immutable time window [start, end). */
public record TimeSlot(LocalDateTime start, LocalDateTime end) implements Comparable<TimeSlot> {

    private static final Comparator<TimeSlot> ORDER =
            Comparator.comparing(TimeSlot::start).thenComparing(TimeSlot::end);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    public TimeSlot {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Time slot end (" + end + ") must be after start (" + start + ")");
        }
    }

    public static TimeSlot parse(String start, String end) {
        return new TimeSlot(LocalDateTime.parse(start.trim()), LocalDateTime.parse(end.trim()));
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    public double hours() {
        return duration().toMinutes() / 60.0;
    }

    public boolean overlaps(TimeSlot other) {
        return TimeOverlapChecker.overlaps(this, other);
    }

    public boolean contains(TimeSlot other) {
        return TimeOverlapChecker.contains(this, other);
    }

    /** Monday of the ISO week this slot starts in — used for weekly hour caps. */
    public LocalDate weekStart() {
        return start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public LocalDate day() {
        return start.toLocalDate();
    }

    public String label() {
        return DAY.format(start) + "–" + TIME.format(end);
    }

    @Override
    public int compareTo(TimeSlot o) {
        return ORDER.compare(this, o);
    }

    @Override
    public String toString() {
        return label();
    }
}
