package com.balancer.service;

import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Violation;
import com.balancer.model.Volunteer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hard-constraint checker. Every rule lives here so the balancer, manual edits and the
 * diagnostics report all agree on what "eligible" means.
 */
public final class ValidationEngine {

    /** All reasons {@code v} cannot (currently) be added to {@code s}. Empty list = eligible. */
    public List<Violation> check(Volunteer v, Shift s, Schedule schedule) {
        List<Violation> out = new ArrayList<>();

        if (schedule.isAssigned(v.id(), s.id())) {
            out.add(new Violation(ConflictReason.ALREADY_ASSIGNED, v.id(), s.id(),
                    v.name() + " is already on this shift"));
            return out;
        }

        int level = v.skillLevel(s.requiredSkill());
        if (level == 0) {
            out.add(new Violation(ConflictReason.MISSING_SKILL, v.id(), s.id(),
                    v.name() + " has no '" + s.requiredSkill() + "' skill"));
        } else if (level < s.minSkillLevel()) {
            out.add(new Violation(ConflictReason.SKILL_LEVEL_TOO_LOW, v.id(), s.id(),
                    v.name() + " is level " + level + " in '" + s.requiredSkill() + "', needs " + s.minSkillLevel()));
        }

        if (!v.isAvailableFor(s.slot())) {
            out.add(new Violation(ConflictReason.NOT_AVAILABLE, v.id(), s.id(),
                    v.name() + " is not available " + s.slot().label()));
        }

        if (v.hasTimeConflict(s.slot())) {
            String clash = schedule.shiftsOf(v.id()).stream()
                    .filter(o -> o.slot().overlaps(s.slot()))
                    .map(Shift::activity).findFirst().orElse("another shift");
            out.add(new Violation(ConflictReason.TIME_OVERLAP_CONFLICT, v.id(), s.id(),
                    v.name() + " is already on '" + clash + "' at that time"));
        }

        if (v.wouldExceedCap(s.slot())) {
            double used = v.hoursInWeek(s.slot().weekStart());
            out.add(new Violation(ConflictReason.HOUR_CAP_REACHED, v.id(), s.id(),
                    String.format("%s has %.1f/%d h this week; +%.1f h would exceed the cap",
                            v.name(), used, v.maxWeeklyHours(), s.hours())));
        }

        if (schedule.assignedCount(s.id()) >= s.volunteersNeeded()) {
            out.add(new Violation(ConflictReason.SHIFT_FULL, v.id(), s.id(),
                    s.activity() + " already has " + s.volunteersNeeded() + " volunteer(s)"));
        }
        return out;
    }

    public boolean isEligible(Volunteer v, Shift s, Schedule schedule) {
        return check(v, s, schedule).isEmpty();
    }

    /**
     * Re-validates the whole current schedule (used after manual/forced edits).
     * Reports assignments that break skill/availability rules and volunteers over their weekly cap.
     */
    public List<Violation> revalidate(Schedule schedule) {
        List<Violation> out = new ArrayList<>();
        schedule.allAssignments().forEach(a -> {
            Volunteer v = schedule.volunteer(a.volunteerId());
            Shift s = schedule.shift(a.shiftId());
            int level = v.skillLevel(s.requiredSkill());
            if (level == 0) {
                out.add(new Violation(ConflictReason.MISSING_SKILL, v.id(), s.id(),
                        v.name() + " is on '" + s.activity() + "' without the '" + s.requiredSkill() + "' skill"));
            } else if (level < s.minSkillLevel()) {
                out.add(new Violation(ConflictReason.SKILL_LEVEL_TOO_LOW, v.id(), s.id(),
                        v.name() + " (level " + level + ") is on '" + s.activity() + "' which needs level " + s.minSkillLevel()));
            }
            if (!v.isAvailableFor(s.slot())) {
                out.add(new Violation(ConflictReason.NOT_AVAILABLE, v.id(), s.id(),
                        v.name() + " is on '" + s.activity() + "' outside their availability"));
            }
        });

        for (Volunteer v : schedule.volunteers()) {
            Map<LocalDate, Double> perWeek = schedule.shiftsOf(v.id()).stream()
                    .collect(Collectors.groupingBy(s -> s.slot().weekStart(), Collectors.summingDouble(Shift::hours)));
            perWeek.forEach((week, hours) -> {
                if (hours > v.maxWeeklyHours() + 1e-9) {
                    out.add(new Violation(ConflictReason.HOUR_CAP_REACHED, v.id(), null,
                            String.format("%s is at %.1f h in week of %s (cap %d h)", v.name(), hours, week, v.maxWeeklyHours())));
                }
            });
        }
        return out;
    }
}
