package com.balancer.persistence;

import com.balancer.model.Assignment;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** JDBC access for assignments and the audit log. */
public final class AssignmentRepository {

    private final Database db;

    public AssignmentRepository(Database db) {
        this.db = db;
    }

    /** Atomically replaces the stored schedule with the in-memory one. */
    public void replaceAll(Collection<Assignment> assignments) {
        db.inTransaction(c -> {
            c.createStatement().executeUpdate("DELETE FROM assignment");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO assignment (volunteer_id, shift_id, manual) VALUES (?, ?, ?)")) {
                for (Assignment a : assignments) {
                    ps.setString(1, a.volunteerId());
                    ps.setString(2, a.shiftId());
                    ps.setBoolean(3, a.manual());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public List<Assignment> findAll() {
        return db.inTransaction(c -> {
            List<Assignment> out = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT volunteer_id, shift_id, manual FROM assignment ORDER BY assigned_at, shift_id")) {
                while (rs.next()) {
                    out.add(new Assignment(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
                }
            }
            return out;
        });
    }

    public void log(String action, String detail) {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log (action, detail) VALUES (?, ?)")) {
                ps.setString(1, action);
                ps.setString(2, detail == null ? "" : detail.substring(0, Math.min(500, detail.length())));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Wipes volunteers, shifts and assignments (used before importing a new dataset). */
    public void deleteEverything() {
        db.inTransaction(c -> {
            var st = c.createStatement();
            st.executeUpdate("DELETE FROM assignment");
            st.executeUpdate("DELETE FROM availability");
            st.executeUpdate("DELETE FROM volunteer_skill");
            st.executeUpdate("DELETE FROM shift");
            st.executeUpdate("DELETE FROM volunteer");
            st.executeUpdate("DELETE FROM skill");
            return null;
        });
    }
}
