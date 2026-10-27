package com.balancer.service;

import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.ShiftStatus;
import com.balancer.model.Violation;
import com.balancer.model.Volunteer;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bottleneck diagnostics: for every shift that is not fully staffed, explains WHY by checking every
 * volunteer against it and tallying the rejection reasons, then suggests "near misses" — people
 * blocked by exactly one overridable rule that an admin could force-assign.
 */
public final class DiagnosticsService {

    private final ValidationEngine validator;

    public DiagnosticsService(ValidationEngine validator) {
        this.validator = validator;
    }

    public record NearMiss(String volunteerId, String volunteerName, ConflictReason blocker, String detail) {
    }

    public record ShiftDiagnostic(String shiftId, String activity, String slot, ShiftStatus status,
                                  int needed, int assigned, int missing, ConflictReason primaryCause,
                                  Map<ConflictReason, Long> rejectionCounts, String summary,
                                  List<NearMiss> nearMisses) {
    }

    public List<ShiftDiagnostic> diagnose(Schedule schedule) {
        return schedule.shifts().stream()
                .filter(s -> schedule.status(s) != ShiftStatus.FULLY_STAFFED)
                .sorted(Comparator.comparingInt(Shift::priority).reversed().thenComparing(Shift::slot))
                .map(s -> diagnose(s, schedule))
                .toList();
    }

    public ShiftDiagnostic diagnose(Shift shift, Schedule schedule) {
        int assigned = schedule.assignedCount(shift.id());
        int missing = shift.volunteersNeeded() - assigned;

        // volunteer -> reasons (ignoring SHIFT_FULL / ALREADY_ASSIGNED, which are not root causes here)
        Map<Volunteer, List<Violation>> blocked = schedule.volunteers().stream()
                .filter(v -> !schedule.isAssigned(v.id(), shift.id()))
                .collect(Collectors.toMap(Function.identity(), v -> validator.check(v, shift, schedule).stream()
                        .filter(x -> x.reason() != ConflictReason.SHIFT_FULL && x.reason() != ConflictReason.ALREADY_ASSIGNED)
                        .toList()));

        Map<ConflictReason, Long> counts = new EnumMap<>(ConflictReason.class);
        blocked.values().stream().flatMap(List::stream)
                .collect(Collectors.groupingBy(Violation::reason, Collectors.counting()))
                .forEach(counts::put);

        long eligibleNow = blocked.values().stream().filter(List::isEmpty).count();
        long qualified = blocked.keySet().stream()
                .filter(v -> v.hasSkill(shift.requiredSkill(), shift.minSkillLevel())).count();

        ConflictReason primary;
        String summary;
        if (eligibleNow > 0) {
            primary = null;
            summary = eligibleNow + " eligible volunteer(s) are free — re-run the balancer or assign manually.";
        } else if (qualified == 0) {
            primary = schedule.volunteersWithSkill(shift.requiredSkill()).isEmpty()
                    ? ConflictReason.MISSING_SKILL : ConflictReason.SKILL_LEVEL_TOO_LOW;
            summary = "Skill deficit: no other volunteer has '" + shift.requiredSkill() + "' at level " + shift.minSkillLevel()
                    + "+. Recruit or upskill volunteers, or lower the requirement.";
        } else {
            // Among the qualified people, what stops them?
            Map<ConflictReason, Long> amongQualified = blocked.entrySet().stream()
                    .filter(e -> e.getKey().hasSkill(shift.requiredSkill(), shift.minSkillLevel()))
                    .flatMap(e -> e.getValue().stream())
                    .collect(Collectors.groupingBy(Violation::reason, Collectors.counting()));
            primary = amongQualified.entrySet().stream()
                    .max(Map.Entry.<ConflictReason, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse(ConflictReason.MISSING_SKILL);
            summary = qualified + (qualified == 1 ? " other qualified volunteer is" : " other qualified volunteers are")
                    + " blocked: " + describe(amongQualified) + ".";
        }

        List<NearMiss> nearMisses = blocked.entrySet().stream()
                .filter(e -> e.getValue().size() == 1 && e.getValue().get(0).overridable())
                // least risky overrides first: a little over the hour cap beats sending someone with no skill
                .sorted(Comparator.comparingInt((Map.Entry<Volunteer, List<Violation>> e) -> severity(e.getValue().get(0).reason()))
                        .thenComparingDouble(e -> e.getKey().totalAssignedHours())
                        .thenComparing(e -> e.getKey().id()))
                .limit(4)
                .map(e -> new NearMiss(e.getKey().id(), e.getKey().name(), e.getValue().get(0).reason(), e.getValue().get(0).message()))
                .toList();

        return new ShiftDiagnostic(shift.id(), shift.activity(), shift.slot().label(), schedule.status(shift),
                shift.volunteersNeeded(), assigned, missing, primary, counts, summary, nearMisses);
    }

    private static String describe(Map<ConflictReason, Long> counts) {
        return Optional.of(counts.entrySet().stream()
                        .sorted(Map.Entry.<ConflictReason, Long>comparingByValue().reversed())
                        .map(e -> e.getValue() + " " + phrase(e.getKey(), e.getValue() == 1))
                        .collect(Collectors.joining(", ")))
                .filter(s -> !s.isEmpty()).orElse("none can take it");
    }

    private static int severity(ConflictReason r) {
        return switch (r) {
            case HOUR_CAP_REACHED -> 0;
            case SKILL_LEVEL_TOO_LOW -> 1;
            case NOT_AVAILABLE -> 2;
            default -> 3;
        };
    }

    private static String phrase(ConflictReason r, boolean one) {
        return switch (r) {
            case HOUR_CAP_REACHED -> "at weekly hour cap";
            case TIME_OVERLAP_CONFLICT -> (one ? "is" : "are") + " busy on an overlapping shift";
            case NOT_AVAILABLE -> (one ? "is" : "are") + " not available then";
            case SKILL_LEVEL_TOO_LOW -> (one ? "lacks" : "lack") + " the skill level";
            case MISSING_SKILL -> (one ? "lacks" : "lack") + " the skill";
            default -> r.label().toLowerCase();
        };
    }
}
