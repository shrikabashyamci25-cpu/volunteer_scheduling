package com.balancer;

import com.balancer.io.CsvDataLoader;
import com.balancer.io.JsonDataLoader;
import com.balancer.io.ScheduleExporter;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Volunteer;
import com.balancer.persistence.DataStore;
import com.balancer.persistence.Database;
import com.balancer.service.DiagnosticsService;
import com.balancer.service.MetricsService;
import com.balancer.service.ScheduleEditor;
import com.balancer.web.ApiServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point.
 * <pre>
 *   java -jar balancer.jar                       start the web dashboard on http://localhost:8080
 *   java -jar balancer.jar --port 9090
 *   java -jar balancer.jar --data ./mydata       import volunteers.(json|csv) + shifts.(json|csv) from a folder
 *   java -jar balancer.jar --cli [--strategy capacity-ratio] [--out ./out]
 *                                                 headless: balance, print report, export JSON + CSV
 *   java -jar balancer.jar --mem                 use an in-memory database (nothing saved)
 * </pre>
 */
public final class App {

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = parseArgs(args);

        Database db = opts.containsKey("mem") ? Database.inMemory("balancer")
                : Database.file(Path.of(opts.getOrDefault("db", "data/balancer")).toAbsolutePath().toString());
        db.init();
        DataStore store = new DataStore(db);

        if (opts.containsKey("data")) {
            Dataset d = loadFolder(Path.of(opts.get("data")));
            store.replaceDataset(d.volunteers(), d.shifts());
            System.out.println("Imported " + d.volunteers().size() + " volunteers, " + d.shifts().size() + " shifts from " + opts.get("data"));
        } else if (store.isEmpty()) {
            // No built-in data: events and volunteers are both entered on the dashboard.
            System.out.println("No events or volunteers yet. Add them on the dashboard.");
        }

        Schedule schedule = store.loadSchedule();
        ScheduleEditor editor = new ScheduleEditor(schedule, store);

        if (opts.containsKey("cli")) {
            runCli(editor, opts);
            return;
        }

        if (schedule.allAssignments().isEmpty() && !schedule.volunteers().isEmpty()) {
            editor.balance(opts.get("strategy"));
        }
        int port = Integer.parseInt(opts.getOrDefault("port", "8080"));
        new ApiServer(editor).start(port);
        System.out.println("""
                ┌──────────────────────────────────────────────┐
                │  Volunteer Shift Balancer is running          │
                │  Open  http://localhost:%-5d                 │
                │  Ctrl+C to stop                               │
                └──────────────────────────────────────────────┘""".formatted(port));
    }

    // ---- CLI mode ------------------------------------------------------------------------

    private static void runCli(ScheduleEditor editor, Map<String, String> opts) {
        editor.balance(opts.get("strategy"));
        Schedule s = editor.schedule();
        MetricsService.ScheduleMetrics m = editor.metrics().compute(s);
        List<DiagnosticsService.ShiftDiagnostic> diags = editor.diagnostics().diagnose(s);

        System.out.println("\n=== SCHEDULE (" + editor.currentStrategy().displayName() + ") ===");
        s.shifts().stream().sorted((a, b) -> a.slot().compareTo(b.slot())).forEach(sh -> System.out.printf(
                "%-4s %-26s %-24s %d/%d  %-18s %s%n", sh.id(), sh.activity(), sh.slot().label(),
                s.assignedCount(sh.id()), sh.volunteersNeeded(), s.status(sh).label(),
                s.volunteersOn(sh.id()).stream().map(Volunteer::name).toList()));

        System.out.println("\n=== WORKLOAD ===");
        m.loads().forEach(l -> System.out.printf("%-20s %5.1f h / %2d h  %s%n", l.name(), l.hours(), l.maxWeeklyHours(),
                "█".repeat((int) Math.round(l.hours()))));

        System.out.printf("%nCoverage %.1f%%  |  fully staffed %d/%d  |  mean %.2f h  σ %.2f h  |  Jain fairness %.3f%n",
                m.coverageRate() * 100, m.fullyStaffed(), m.totalShifts(), m.meanHours(), m.stdDevHours(), m.jainFairnessIndex());

        if (!diags.isEmpty()) {
            System.out.println("\n=== UNCOVERED SHIFTS ===");
            diags.forEach(d -> System.out.printf("- %s (%s) missing %d → %s%n", d.activity(), d.slot(), d.missing(), d.summary()));
        }

        Path out = Path.of(opts.getOrDefault("out", "out"));
        ScheduleExporter exporter = new ScheduleExporter();
        exporter.write(out.resolve("schedule.json"), exporter.toJson(s, m, diags));
        exporter.write(out.resolve("schedule.csv"), exporter.toCsv(s));
        System.out.println("\nExported " + out.resolve("schedule.json") + " and " + out.resolve("schedule.csv"));
    }

    // ---- data loading --------------------------------------------------------------------

    record Dataset(List<Volunteer> volunteers, List<Shift> shifts) {
    }

    static Dataset loadFolder(Path dir) {
        JsonDataLoader json = new JsonDataLoader();
        CsvDataLoader csv = new CsvDataLoader();
        List<Volunteer> vols = Files.exists(dir.resolve("volunteers.json")) ? json.volunteers(dir.resolve("volunteers.json"))
                : csv.volunteers(dir.resolve("volunteers.csv"));
        List<Shift> shifts = Files.exists(dir.resolve("shifts.json")) ? json.shifts(dir.resolve("shifts.json"))
                : csv.shifts(dir.resolve("shifts.csv"));
        return new Dataset(vols, shifts);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            out.put(key, hasValue ? args[++i] : "true");
        }
        return out;
    }
}
