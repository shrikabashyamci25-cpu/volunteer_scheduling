package com.balancer;

import com.balancer.io.CsvDataLoader;
import com.balancer.io.ScheduleExporter;
import com.balancer.model.Schedule;
import com.balancer.model.Skill;
import com.balancer.persistence.DataStore;
import com.balancer.persistence.Database;
import com.balancer.service.ShiftBalancerService;
import com.balancer.service.ValidationEngine;
import com.balancer.strategy.MinHoursBalancingStrategy;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PersistenceAndIoTest {

    @Test
    public void csvParsesSkillsAvailabilityAndQuotes() {
        String csv = """
                id,name,email,maxWeeklyHours,skills,availability
                V1,"Rao, Ananya",a@x.org,12,first aid:3|Cooking:2,2026-11-02T08:00/2026-11-02T12:00|2026-11-02T12:00/2026-11-02T16:00
                """;
        var v = new CsvDataLoader().volunteersFromString(csv).get(0);
        assertEquals("Rao, Ananya", v.name());
        assertEquals(2, v.skillLevel(Skill.of("cooking")));
        assertEquals(1, v.availability().size()); // touching windows merged
    }

    @Test
    public void scheduleRoundTripsThroughSqlAndViewsAgree() throws Exception {
        Database db = Database.inMemory("t" + UUID.randomUUID()).init();
        DataStore store = new DataStore(db);
        Schedule original = ShiftBalancerServiceTest.sampleSchedule();
        store.replaceDataset(original.volunteers(), original.shifts());

        Schedule s = store.loadSchedule();
        new ShiftBalancerService(new ValidationEngine()).balance(s, new MinHoursBalancingStrategy());
        store.saveAssignments(s);

        Schedule reloaded = store.loadSchedule();
        assertEquals(s.allAssignments().size(), reloaded.allAssignments().size());
        assertEquals(original.volunteers().size(), reloaded.volunteers().size());

        // SQL view hours must equal the Java-computed hours
        var workload = store.reports().run("workload");
        double sqlTotal = workload.rows().stream().mapToDouble(r -> ((Number) r.get("hours_assigned")).doubleValue()).sum();
        double javaTotal = reloaded.volunteers().stream().mapToDouble(v -> v.totalAssignedHours()).sum();
        assertEquals(javaTotal, sqlTotal, 0.01);

        for (var r : store.reports().list()) {
            store.reports().run(r.id()); // every report query must execute
        }
    }

    @Test
    public void exportContainsEveryAssignment() throws Exception {
        Schedule s = ShiftBalancerServiceTest.sampleSchedule();
        new ShiftBalancerService(new ValidationEngine()).balance(s, new MinHoursBalancingStrategy());
        String csv = new ScheduleExporter().toCsv(s);
        assertEquals(s.allAssignments().size() + 1, csv.lines().count());
        assertTrue(csv.startsWith("shift_id,"));
        assertFalse(s.allAssignments().isEmpty());
    }
}
