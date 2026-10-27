package com.balancer.persistence;

import com.balancer.model.Skill;
import com.balancer.model.TimeSlot;
import com.balancer.model.Volunteer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC access for volunteers, their skills and availability windows. */
public final class VolunteerRepository {

    private final Database db;

    public VolunteerRepository(Database db) {
        this.db = db;
    }

    public void saveAll(Collection<Volunteer> volunteers) {
        db.inTransaction(c -> {
            try (PreparedStatement skill = c.prepareStatement("MERGE INTO skill (name) KEY (name) VALUES (?)");
                 PreparedStatement vol = c.prepareStatement(
                         "MERGE INTO volunteer (id, name, email, max_weekly_hours) KEY (id) VALUES (?, ?, ?, ?)");
                 PreparedStatement delSkills = c.prepareStatement("DELETE FROM volunteer_skill WHERE volunteer_id = ?");
                 PreparedStatement delAvail = c.prepareStatement("DELETE FROM availability WHERE volunteer_id = ?");
                 PreparedStatement vs = c.prepareStatement(
                         "INSERT INTO volunteer_skill (volunteer_id, skill_name, level) VALUES (?, ?, ?)");
                 PreparedStatement av = c.prepareStatement(
                         "INSERT INTO availability (volunteer_id, start_time, end_time) VALUES (?, ?, ?)")) {
                for (Volunteer v : volunteers) {
                    vol.setString(1, v.id());
                    vol.setString(2, v.name());
                    vol.setString(3, v.email());
                    vol.setInt(4, v.maxWeeklyHours());
                    vol.executeUpdate();
                    delSkills.setString(1, v.id());
                    delSkills.executeUpdate();
                    delAvail.setString(1, v.id());
                    delAvail.executeUpdate();
                    for (Map.Entry<Skill, Integer> e : v.skills().entrySet()) {
                        skill.setString(1, e.getKey().name());
                        skill.executeUpdate();
                        vs.setString(1, v.id());
                        vs.setString(2, e.getKey().name());
                        vs.setInt(3, e.getValue());
                        vs.addBatch();
                    }
                    for (TimeSlot t : v.availability()) {
                        av.setString(1, v.id());
                        av.setTimestamp(2, Timestamp.valueOf(t.start()));
                        av.setTimestamp(3, Timestamp.valueOf(t.end()));
                        av.addBatch();
                    }
                }
                vs.executeBatch();
                av.executeBatch();
            }
            return null;
        });
    }

    public List<Volunteer> findAll() {
        return db.inTransaction(c -> {
            Map<String, Volunteer.Builder> builders = new LinkedHashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT id, name, email, max_weekly_hours FROM volunteer ORDER BY id")) {
                while (rs.next()) {
                    builders.put(rs.getString("id"), Volunteer.builder()
                            .id(rs.getString("id")).name(rs.getString("name"))
                            .email(rs.getString("email")).maxWeeklyHours(rs.getInt("max_weekly_hours")));
                }
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT volunteer_id, skill_name, level FROM volunteer_skill")) {
                while (rs.next()) {
                    builders.get(rs.getString(1)).skill(rs.getString(2), rs.getInt(3));
                }
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT volunteer_id, start_time, end_time FROM availability ORDER BY volunteer_id, start_time")) {
                while (rs.next()) {
                    builders.get(rs.getString(1)).available(
                            rs.getTimestamp(2).toLocalDateTime(), rs.getTimestamp(3).toLocalDateTime());
                }
            }
            List<Volunteer> out = new ArrayList<>();
            builders.values().forEach(b -> out.add(b.build()));
            return out;
        });
    }

    /** Skills, availability and assignments go with it (ON DELETE CASCADE). */
    public void delete(String id) {
        db.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM volunteer WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int count() {
        return db.inTransaction(VolunteerRepository::countRows);
    }

    private static int countRows(Connection c) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM volunteer")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
