package com.balancer.io;

import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Volunteer;
import com.balancer.service.DiagnosticsService;
import com.balancer.service.MetricsService;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Final schedule export: a rich JSON payload or a flat CSV (one row per assignment). */
public final class ScheduleExporter {

    public record ExportRow(String shiftId, String activity, String location, LocalDateTime start, LocalDateTime end,
                            double hours, String skill, String volunteerId, String volunteerName, boolean manual) {
    }

    public List<ExportRow> rows(Schedule schedule) {
        return schedule.allAssignments().stream()
                .map(a -> {
                    Shift s = schedule.shift(a.shiftId());
                    Volunteer v = schedule.volunteer(a.volunteerId());
                    return new ExportRow(s.id(), s.activity(), s.location(), s.slot().start(), s.slot().end(),
                            s.hours(), s.requiredSkill().name(), v.id(), v.name(), a.manual());
                })
                .sorted(Comparator.comparing(ExportRow::start).thenComparing(ExportRow::shiftId).thenComparing(ExportRow::volunteerName))
                .toList();
    }

    public String toJson(Schedule schedule, MetricsService.ScheduleMetrics metrics,
                         List<DiagnosticsService.ShiftDiagnostic> diagnostics) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", LocalDateTime.now().withNano(0));
        payload.put("metrics", metrics);
        payload.put("byVolunteer", rows(schedule).stream()
                .collect(Collectors.groupingBy(ExportRow::volunteerName, java.util.TreeMap::new, Collectors.toList())));
        payload.put("byShift", rows(schedule).stream()
                .collect(Collectors.groupingBy(ExportRow::shiftId, LinkedHashMap::new,
                        Collectors.mapping(ExportRow::volunteerName, Collectors.toList()))));
        payload.put("uncoveredShifts", diagnostics);
        try {
            return Json.MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toCsv(Schedule schedule) {
        StringBuilder sb = new StringBuilder("shift_id,activity,location,start,end,hours,skill,volunteer_id,volunteer_name,manual\n");
        for (ExportRow r : rows(schedule)) {
            sb.append(String.join(",", esc(r.shiftId()), esc(r.activity()), esc(r.location()),
                    r.start().toString(), r.end().toString(), String.valueOf(r.hours()), esc(r.skill()),
                    esc(r.volunteerId()), esc(r.volunteerName()), String.valueOf(r.manual()))).append('\n');
        }
        return sb.toString();
    }

    public void write(Path file, String content) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
