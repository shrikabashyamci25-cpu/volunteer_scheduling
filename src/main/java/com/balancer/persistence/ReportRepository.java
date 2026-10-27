package com.balancer.persistence;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Read-only SQL reports. Each one is a named, whitelisted query over the reporting views,
 * so the UI can show live SQL output without allowing arbitrary SQL from the browser.
 */
public final class ReportRepository {

    public record Report(String id, String title, String sql) {
    }

    public record ReportResult(String id, String title, String sql, List<String> columns, List<Map<String, Object>> rows) {
    }

    private final Database db;
    private final Map<String, Report> reports = new LinkedHashMap<>();

    public ReportRepository(Database db) {
        this.db = db;
        add("workload", "Volunteer workload",
                "SELECT name, shift_count, hours_assigned, max_weekly_hours, utilization_pct\n"
                        + "FROM v_volunteer_workload\nORDER BY hours_assigned DESC, name");
        add("coverage", "Shift coverage",
                "SELECT id, activity, start_time, required_skill, filled, volunteers_needed, missing, status\n"
                        + "FROM v_shift_coverage\nORDER BY missing DESC, start_time");
        add("skills", "Skill supply vs demand",
                "SELECT skill, volunteers_with_skill, seats_demanded, hours_demanded, max_hours_offered,\n"
                        + "       CAST(hours_demanded * 100.0 / NULLIF(max_hours_offered, 0) AS DECIMAL(6,1)) AS pressure_pct\n"
                        + "FROM v_skill_supply_demand\nORDER BY pressure_pct DESC NULLS FIRST");
        add("daily", "Daily coverage", "SELECT * FROM v_daily_load ORDER BY shift_day");
        add("ranking", "Load ranking (window functions)",
                "SELECT name, hours_assigned,\n"
                        + "       RANK() OVER (ORDER BY hours_assigned DESC) AS load_rank,\n"
                        + "       CAST(hours_assigned - AVG(hours_assigned) OVER () AS DECIMAL(6,2)) AS diff_from_mean\n"
                        + "FROM v_volunteer_workload\nORDER BY load_rank, name");
        add("audit", "Audit log (last 25 edits)",
                "SELECT logged_at, action, detail FROM audit_log ORDER BY id DESC LIMIT 25");
    }

    private void add(String id, String title, String sql) {
        reports.put(id, new Report(id, title, sql));
    }

    public List<Report> list() {
        return List.copyOf(reports.values());
    }

    public ReportResult run(String id) {
        Report r = reports.get(id);
        if (r == null) throw new NoSuchElementException("Unknown report: " + id);
        return db.inTransaction(c -> {
            try (ResultSet rs = c.createStatement().executeQuery(r.sql())) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i).toLowerCase());
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols.size(); i++) {
                        Object val = rs.getObject(i);
                        if (val instanceof Timestamp ts) val = ts.toLocalDateTime().toString().replace('T', ' ');
                        else if (val instanceof java.sql.Date d) val = d.toString();
                        row.put(cols.get(i - 1), val);
                    }
                    rows.add(row);
                }
                return new ReportResult(r.id(), r.title(), r.sql(), cols, rows);
            }
        });
    }
}
