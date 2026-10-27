package com.balancer.web;

import com.balancer.command.EditRejectedException;
import com.balancer.io.CsvDataLoader;
import com.balancer.io.Dtos;
import com.balancer.io.Json;
import com.balancer.io.JsonDataLoader;
import com.balancer.io.ScheduleExporter;
import com.balancer.model.Shift;
import com.balancer.model.Volunteer;
import com.balancer.service.ScheduleEditor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;

/**
 * Tiny REST API + static file server on the JDK's built-in {@link HttpServer} (no framework needed).
 *
 * <pre>
 * GET  /api/state                     full dashboard state (shifts, volunteers, metrics, diagnostics)
 * GET  /api/candidates?shift=S01      every volunteer with eligibility + reasons for one shift
 * POST /api/balance    {strategy}
 * POST /api/assign     {volunteerId, shiftId, force}
 * POST /api/remove     {volunteerId, shiftId}
 * POST /api/swap       {volunteerA, shiftA, volunteerB, shiftB}
 * POST /api/volunteers {name, email, maxWeeklyHours, skills: {skill: level}, availability: [{start, end}]}
 * POST /api/volunteers/remove {volunteerId}
 * POST /api/shifts     {activity, location, start, end, requiredSkill, minSkillLevel, volunteersNeeded, priority}
 * POST /api/shifts/remove {shiftId}
 * POST /api/undo | /api/redo | /api/clear
 * GET  /api/reports  |  /api/reports/{id}     SQL reports over the H2 views
 * GET  /api/export?format=json|csv
 * POST /api/import?format=json|csv   {volunteers: "...file text...", shifts: "...file text..."}
 * </pre>
 */
public final class ApiServer {

    private final ScheduleEditor editor;
    private final ScheduleExporter exporter = new ScheduleExporter();
    private HttpServer server;

    public ApiServer(ScheduleEditor editor) {
        this.editor = editor;
    }

    public ApiServer start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return this;
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---- API -----------------------------------------------------------------------------

    private void handleApi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        try {
            Object result = switch (method + " " + path) {
                case "GET /api/state" -> StateMapper.state(editor);
                case "GET /api/candidates" -> StateMapper.candidates(editor, require(query, "shift"));
                case "GET /api/reports" -> editor.store().orElseThrow(() -> new IllegalStateException("No database")).reports().list();
                case "POST /api/balance" -> act(editor.balance(body(ex).get("strategy")));
                case "POST /api/assign" -> {
                    Map<String, String> b = body(ex);
                    yield act(editor.assign(require(b, "volunteerId"), require(b, "shiftId"), Boolean.parseBoolean(b.get("force"))));
                }
                case "POST /api/remove" -> {
                    Map<String, String> b = body(ex);
                    yield act(editor.remove(require(b, "volunteerId"), require(b, "shiftId")));
                }
                case "POST /api/swap" -> {
                    Map<String, String> b = body(ex);
                    yield act(editor.swap(require(b, "volunteerA"), require(b, "shiftA"), require(b, "volunteerB"), require(b, "shiftB")));
                }
                case "POST /api/volunteers" -> act(editor.addVolunteer(volunteerBuilder(bodyAs(ex, Dtos.VolunteerDto.class))));
                case "POST /api/volunteers/remove" -> act(editor.removeVolunteer(require(body(ex), "volunteerId")));
                case "POST /api/shifts" -> act(editor.addShift(shiftBuilder(bodyAs(ex, Dtos.ShiftDto.class))));
                case "POST /api/shifts/remove" -> act(editor.removeShift(require(body(ex), "shiftId")));
                case "POST /api/undo" -> act(editor.undo());
                case "POST /api/redo" -> act(editor.redo());
                case "POST /api/clear" -> act(editor.clear());
                case "POST /api/import" -> act(importData(query.getOrDefault("format", "json"), body(ex)));
                case "GET /api/export" -> {
                    export(ex, query.getOrDefault("format", "json"));
                    yield null;
                }
                default -> {
                    if (method.equals("GET") && path.startsWith("/api/reports/")) {
                        yield editor.store().orElseThrow(() -> new IllegalStateException("No database"))
                                .reports().run(path.substring("/api/reports/".length()));
                    }
                    throw new NoSuchElementException("No route for " + method + " " + path);
                }
            };
            if (result != null) sendJson(ex, 200, result);
        } catch (EditRejectedException e) {
            sendJson(ex, 409, Map.of("error", e.getMessage(),
                    "violations", e.violations().stream().map(StateMapper.ViolationView::of).toList()));
        } catch (NoSuchElementException e) {
            sendJson(ex, 404, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** After every action, return the result plus the fresh state so the UI re-renders in one round trip. */
    private Map<String, Object> act(ScheduleEditor.EditResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", r.message());
        out.put("overridden", r.overridden().stream().map(StateMapper.ViolationView::of).toList());
        out.put("state", StateMapper.state(editor));
        return out;
    }

    private ScheduleEditor.EditResult importData(String format, Map<String, String> body) {
        String vText = require(body, "volunteers");
        String sText = require(body, "shifts");
        List<Volunteer> vols;
        List<Shift> shifts;
        if (format.equalsIgnoreCase("csv")) {
            CsvDataLoader csv = new CsvDataLoader();
            vols = csv.volunteersFromString(vText);
            shifts = csv.shiftsFromString(sText);
        } else {
            JsonDataLoader json = new JsonDataLoader();
            vols = json.volunteersFromString(vText);
            shifts = json.shiftsFromString(sText);
        }
        return editor.replaceDataset(vols, shifts);
    }

    /** Dashboard form → Builder. The id is left unset; the editor assigns the next free one. */
    private static Volunteer.Builder volunteerBuilder(Dtos.VolunteerDto d) {
        if (d.skills() == null || d.skills().isEmpty()) throw new IllegalArgumentException("Pick at least one skill");
        if (d.availability() == null || d.availability().isEmpty()) throw new IllegalArgumentException("Add at least one available time");
        Volunteer.Builder b = Volunteer.builder().name(d.name()).email(d.email()).maxWeeklyHours(d.maxWeeklyHours());
        d.skills().forEach(b::skill);
        d.availability().forEach(s -> {
            if (s.start() == null || s.end() == null || !s.end().isAfter(s.start())) {
                throw new IllegalArgumentException("Each available time needs an end after its start");
            }
            b.available(s.start(), s.end());
        });
        return b;
    }

    /** Dashboard event form → Builder. The id is left unset; the editor assigns the next free one. */
    private static Shift.Builder shiftBuilder(Dtos.ShiftDto d) {
        if (d.start() == null || d.end() == null || !d.end().isAfter(d.start())) {
            throw new IllegalArgumentException("The event needs an end time after its start time");
        }
        if (d.requiredSkill() == null || d.requiredSkill().isBlank()) throw new IllegalArgumentException("Enter the skill this event needs");
        return Shift.builder().activity(d.activity()).location(d.location())
                .slot(d.start(), d.end())
                .requiredSkill(d.requiredSkill().trim())
                .minSkillLevel(d.minSkillLevel() == 0 ? 1 : d.minSkillLevel())
                .volunteersNeeded(d.volunteersNeeded() == 0 ? 1 : d.volunteersNeeded())
                .priority(d.priority() == 0 ? 2 : d.priority());
    }

    private void export(HttpExchange ex, String format) throws IOException {
        var s = editor.schedule();
        boolean csv = format.equalsIgnoreCase("csv");
        String content = csv ? exporter.toCsv(s)
                : exporter.toJson(s, editor.metrics().compute(s), editor.diagnostics().diagnose(s));
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"schedule." + (csv ? "csv" : "json") + "\"");
        send(ex, 200, csv ? "text/csv; charset=utf-8" : "application/json; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    // ---- static files --------------------------------------------------------------------

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "bad path".getBytes());
            return;
        }
        try (InputStream in = ApiServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain", "Not found".getBytes());
                return;
            }
            send(ex, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Map<String, String> body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) return new HashMap<>();
        Map<String, Object> parsed = Json.MAPPER.readValue(raw, new TypeReference<>() { });
        Map<String, String> out = new HashMap<>();
        parsed.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    private static <T> T bodyAs(HttpExchange ex, Class<T> type) throws IOException {
        try {
            return Json.MAPPER.readValue(ex.getRequestBody().readAllBytes(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request body: " + e.getOriginalMessage());
        }
    }

    private static String require(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing parameter: " + key);
        return v;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
