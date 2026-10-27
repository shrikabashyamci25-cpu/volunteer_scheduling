package com.balancer.service;

import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.ShiftStatus;
import com.balancer.model.Volunteer;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Workload distribution and coverage statistics, computed with Streams + grouping collectors. */
public final class MetricsService {

    public record VolunteerLoad(String id, String name, double hours, int maxWeeklyHours,
                                int shifts, double utilization) {
    }

    public record ScheduleMetrics(int totalShifts, int fullyStaffed, int partiallyStaffed, int open,
                                  int seatsNeeded, int seatsFilled, double coverageRate,
                                  double meanHours, double stdDevHours, double minHours, double maxHours,
                                  double jainFairnessIndex, int activeVolunteers,
                                  Map<ShiftStatus, Long> shiftsByStatus,
                                  Map<String, Double> hoursBySkill,
                                  Map<LocalDate, Double> hoursByDay,
                                  List<VolunteerLoad> loads) {
    }

    public ScheduleMetrics compute(Schedule schedule) {
        Map<ShiftStatus, Long> byStatus = new EnumMap<>(ShiftStatus.class);
        for (ShiftStatus st : ShiftStatus.values()) byStatus.put(st, 0L);
        schedule.shifts().stream()
                .collect(Collectors.groupingBy(schedule::status, Collectors.counting()))
                .forEach(byStatus::put);

        int seatsNeeded = schedule.shifts().stream().mapToInt(Shift::volunteersNeeded).sum();
        int seatsFilled = schedule.shifts().stream()
                .mapToInt(s -> Math.min(s.volunteersNeeded(), schedule.assignedCount(s.id()))).sum();

        List<VolunteerLoad> loads = schedule.volunteers().stream()
                .map(v -> new VolunteerLoad(v.id(), v.name(), round(v.totalAssignedHours()), v.maxWeeklyHours(),
                        v.bookedShiftCount(), round(v.totalAssignedHours() / v.maxWeeklyHours())))
                .sorted(Comparator.comparingDouble(VolunteerLoad::hours).reversed().thenComparing(VolunteerLoad::name))
                .toList();

        DoubleSummaryStatistics stats = schedule.volunteers().stream()
                .mapToDouble(Volunteer::totalAssignedHours).summaryStatistics();
        double mean = stats.getCount() == 0 ? 0 : stats.getAverage();
        double variance = schedule.volunteers().stream()
                .mapToDouble(v -> Math.pow(v.totalAssignedHours() - mean, 2)).average().orElse(0);
        double sum = stats.getSum();
        double sumSq = schedule.volunteers().stream().mapToDouble(v -> v.totalAssignedHours() * v.totalAssignedHours()).sum();
        // Jain's index: 1.0 = perfectly equal hours, 1/n = one person does everything.
        double jain = sumSq == 0 ? 1.0 : (sum * sum) / (stats.getCount() * sumSq);

        Map<String, Double> bySkill = schedule.allAssignments().stream()
                .map(a -> schedule.shift(a.shiftId()))
                .collect(Collectors.groupingBy(s -> s.requiredSkill().name(), TreeMap::new, Collectors.summingDouble(Shift::hours)));

        Map<LocalDate, Double> byDay = schedule.allAssignments().stream()
                .map(a -> schedule.shift(a.shiftId()))
                .collect(Collectors.groupingBy(s -> s.slot().day(), TreeMap::new, Collectors.summingDouble(Shift::hours)));

        int active = (int) schedule.volunteers().stream().filter(v -> v.bookedShiftCount() > 0).count();

        return new ScheduleMetrics(schedule.shifts().size(),
                byStatus.get(ShiftStatus.FULLY_STAFFED).intValue(),
                byStatus.get(ShiftStatus.PARTIALLY_STAFFED).intValue(),
                byStatus.get(ShiftStatus.OPEN).intValue(),
                seatsNeeded, seatsFilled,
                round(seatsNeeded == 0 ? 1 : (double) seatsFilled / seatsNeeded),
                round(mean), round(Math.sqrt(variance)),
                round(stats.getCount() == 0 ? 0 : stats.getMin()), round(stats.getCount() == 0 ? 0 : stats.getMax()),
                round(jain), active, byStatus, bySkill, byDay, loads);
    }

    private static double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
