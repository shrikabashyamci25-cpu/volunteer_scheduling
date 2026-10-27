package com.balancer.io;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV ingestion.
 * <pre>
 * volunteers.csv: id,name,email,maxWeeklyHours,skills,availability
 *   skills       = "first aid:3|cooking:2"
 *   availability = "2026-11-02T08:00/2026-11-02T14:00|2026-11-04T16:00/2026-11-04T21:00"
 * shifts.csv:     id,activity,location,start,end,requiredSkill,minSkillLevel,volunteersNeeded,priority
 * </pre>
 */
public final class CsvDataLoader {

    public List<Volunteer> volunteers(Path file) {
        return volunteersFromString(read(file));
    }

    public List<Shift> shifts(Path file) {
        return shiftsFromString(read(file));
    }

    public List<Volunteer> volunteersFromString(String csv) {
        List<Volunteer> out = new ArrayList<>();
        forEachRow(csv, (row, line) -> {
            Volunteer.Builder b = Volunteer.builder()
                    .id(row.get("id")).name(row.get("name")).email(row.getOrDefault("email", ""))
                    .maxWeeklyHours(Integer.parseInt(row.get("maxweeklyhours").trim()));
            for (String part : split(row.getOrDefault("skills", ""), '|')) {
                String[] kv = part.split(":");
                if (kv.length != 2) throw new IllegalArgumentException("Line " + line + ": bad skill '" + part + "' (use name:level)");
                b.skill(kv[0], Integer.parseInt(kv[1].trim()));
            }
            for (String part : split(row.getOrDefault("availability", ""), '|')) {
                String[] se = part.split("/");
                if (se.length != 2) throw new IllegalArgumentException("Line " + line + ": bad window '" + part + "' (use start/end)");
                b.available(se[0], se[1]);
            }
            out.add(b.build());
        });
        return out;
    }

    public List<Shift> shiftsFromString(String csv) {
        List<Shift> out = new ArrayList<>();
        forEachRow(csv, (row, line) -> out.add(Shift.builder()
                .id(row.get("id")).activity(row.get("activity")).location(row.getOrDefault("location", ""))
                .slot(row.get("start"), row.get("end"))
                .requiredSkill(row.get("requiredskill"))
                .minSkillLevel(intOr(row.get("minskilllevel"), 1))
                .volunteersNeeded(intOr(row.get("volunteersneeded"), 1))
                .priority(intOr(row.get("priority"), 2))
                .build()));
        return out;
    }

    // ---- tiny RFC-4180-ish parser (handles quoted fields with commas and "" escapes) -------

    private interface RowHandler {
        void accept(Map<String, String> row, int lineNo);
    }

    private static void forEachRow(String csv, RowHandler handler) {
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) return;
        List<String> header = parseLine(lines.get(0)).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = parseLine(lines.get(i));
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < cells.size() ? cells.get(c).trim() : "");
            }
            try {
                handler.accept(row, i + 1);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("CSV line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
    }

    static List<String> parseLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    private static List<String> split(String s, char sep) {
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.split(java.util.regex.Pattern.quote(String.valueOf(sep)))).stream()
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static int intOr(String s, int def) {
        return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
