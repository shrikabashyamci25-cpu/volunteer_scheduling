package com.balancer.persistence;

import com.balancer.model.Shift;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** JDBC access for shifts. */
public final class ShiftRepository {

    private final Database db;

    public ShiftRepository(Database db) {
        this.db = db;
    }

    public void saveAll(Collection<Shift> shifts) {
        db.inTransaction(c -> {
            try (PreparedStatement skill = c.prepareStatement("MERGE INTO skill (name) KEY (name) VALUES (?)");
                 PreparedStatement ps = c.prepareStatement("""
                         MERGE INTO shift (id, activity, location, start_time, end_time, required_skill,
                                           min_skill_level, volunteers_needed, priority)
                         KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (Shift s : shifts) {
                    skill.setString(1, s.requiredSkill().name());
                    skill.executeUpdate();
                    ps.setString(1, s.id());
                    ps.setString(2, s.activity());
                    ps.setString(3, s.location());
                    ps.setTimestamp(4, Timestamp.valueOf(s.slot().start()));
                    ps.setTimestamp(5, Timestamp.valueOf(s.slot().end()));
                    ps.setString(6, s.requiredSkill().name());
                    ps.setInt(7, s.minSkillLevel());
                    ps.setInt(8, s.volunteersNeeded());
                    ps.setInt(9, s.priority());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Assignments to it go too (ON DELETE CASCADE). */
    public void delete(String id) {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM shift WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<Shift> findAll() {
        return db.inTransaction(c -> {
            List<Shift> out = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery("""
                    SELECT id, activity, location, start_time, end_time, required_skill,
                           min_skill_level, volunteers_needed, priority
                    FROM shift ORDER BY start_time, id""")) {
                while (rs.next()) {
                    out.add(Shift.builder()
                            .id(rs.getString("id"))
                            .activity(rs.getString("activity"))
                            .location(rs.getString("location"))
                            .slot(rs.getTimestamp("start_time").toLocalDateTime(), rs.getTimestamp("end_time").toLocalDateTime())
                            .requiredSkill(rs.getString("required_skill"))
                            .minSkillLevel(rs.getInt("min_skill_level"))
                            .volunteersNeeded(rs.getInt("volunteers_needed"))
                            .priority(rs.getInt("priority"))
                            .build());
                }
            }
            return out;
        });
    }
}
