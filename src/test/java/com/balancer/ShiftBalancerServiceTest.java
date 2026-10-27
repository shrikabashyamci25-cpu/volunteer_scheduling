package com.balancer;

import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.ShiftStatus;
import com.balancer.model.TimeSlot;
import com.balancer.model.Volunteer;
import com.balancer.service.DiagnosticsService;
import com.balancer.service.MetricsService;
import com.balancer.service.ShiftBalancerService;
import com.balancer.service.ValidationEngine;
import com.balancer.strategy.MinHoursBalancingStrategy;
import com.balancer.strategy.StrategyRegistry;
import com.balancer.strategy.StrictPriorityStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.balancer.TestData.shift;
import static com.balancer.TestData.vol;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ShiftBalancerServiceTest {

    private final ValidationEngine engine = new ValidationEngine();
    private final ShiftBalancerService balancer = new ShiftBalancerService(engine);

    @Test
    public void spreadsWorkEvenlyAcrossEqualVolunteers() {
        List<Volunteer> vols = List.of(
                vol("A", 20, "logistics", 2, "06:00", "22:00"),
                vol("B", 20, "logistics", 2, "06:00", "22:00"),
                vol("C", 20, "logistics", 2, "06:00", "22:00"));
        List<Shift> shifts = new ArrayList<>();
        for (int h = 6; h < 18; h += 2) {
            shifts.add(shift("S" + h, "logistics", 1, 1, String.format("%02d:00", h), String.format("%02d:00", h + 2)));
        }
        Schedule sch = new Schedule(vols, shifts);
        balancer.balance(sch, new MinHoursBalancingStrategy());

        vols.forEach(v -> assertEquals(4.0, v.totalAssignedHours(), 1e-9)); // 12 h / 3 people
        assertEquals(1.0, new MetricsService().compute(sch).jainFairnessIndex(), 1e-9);
    }

    @Test
    public void neverDoubleBooksAndRespectsCaps() throws Exception {
        Schedule sch = sampleSchedule();
        for (var strategy : new StrategyRegistry().all()) {
            balancer.balance(sch, strategy);
            for (Volunteer v : sch.volunteers()) {
                List<TimeSlot> slots = v.bookedSlots();
                for (int i = 0; i < slots.size(); i++)
                    for (int j = i + 1; j < slots.size(); j++)
                        assertFalse(v + " double booked", slots.get(i).overlaps(slots.get(j)));
                assertTrue(v + " over cap", v.totalAssignedHours() <= v.maxWeeklyHours() + 1e-9);
            }
            assertTrue("auto schedule must be violation-free", engine.revalidate(sch).isEmpty());
        }
    }

    @Test
    public void scarceSkillGoesToTheOnlyQualifiedPerson() {
        // C is the only driver; the balancer must not burn C's hours on logistics first.
        List<Volunteer> vols = List.of(
                vol("A", 10, "logistics", 2, "06:00", "22:00"),
                Volunteer.builder().id("C").name("C").maxWeeklyHours(4).skill("driving", 3).skill("logistics", 3)
                        .available("2026-11-02T06:00", "2026-11-02T22:00").build());
        List<Shift> shifts = List.of(
                shift("L1", "logistics", 1, 1, "08:00", "12:00"),
                shift("D1", "driving", 2, 1, "13:00", "17:00"));
        Schedule sch = new Schedule(vols, shifts);
        balancer.balance(sch, new MinHoursBalancingStrategy());
        assertTrue(sch.isAssigned("C", "D1"));
        assertTrue(sch.isAssigned("A", "L1"));
    }

    @Test
    public void strictPriorityPicksMostSkilled() {
        List<Volunteer> vols = List.of(vol("A", 10, "first aid", 2, "06:00", "22:00"), vol("B", 10, "first aid", 5, "06:00", "22:00"));
        Schedule sch = new Schedule(vols, List.of(shift("S1", "first aid", 1, 1, "08:00", "10:00")));
        balancer.balance(sch, new StrictPriorityStrategy());
        assertTrue(sch.isAssigned("B", "S1"));
    }

    @Test
    public void diagnosticsExplainSkillDeficit() {
        Schedule sch = new Schedule(List.of(vol("A", 10, "cooking", 2, "06:00", "22:00")),
                List.of(shift("S1", "first aid", 4, 1, "08:00", "10:00")));
        balancer.balance(sch, new MinHoursBalancingStrategy());
        var diags = new DiagnosticsService(engine).diagnose(sch);
        assertEquals(1, diags.size());
        assertEquals(ShiftStatus.OPEN, diags.get(0).status());
        assertEquals(com.balancer.model.ConflictReason.MISSING_SKILL, diags.get(0).primaryCause());
    }

    @Test
    public void diagnosticsSuggestNearMissForHourCap() {
        Volunteer a = vol("A", 3, "cooking", 3, "06:00", "22:00");
        Schedule sch = new Schedule(List.of(a), List.of(
                shift("S1", "cooking", 1, 1, "08:00", "10:00"), shift("S2", "cooking", 1, 1, "12:00", "14:00")));
        balancer.balance(sch, new MinHoursBalancingStrategy());
        var d = new DiagnosticsService(engine).diagnose(sch).get(0);
        assertEquals(com.balancer.model.ConflictReason.HOUR_CAP_REACHED, d.primaryCause());
        assertEquals("A", d.nearMisses().get(0).volunteerId());
    }

    @Test
    public void manualAssignmentsArePinnedAcrossRebalance() {
        Schedule sch = new Schedule(List.of(vol("A", 10, "cooking", 3, "06:00", "22:00"), vol("B", 10, "cooking", 3, "06:00", "22:00")),
                List.of(shift("S1", "cooking", 1, 1, "08:00", "10:00")));
        sch.assign(com.balancer.model.Assignment.manual("B", "S1"));
        var result = balancer.balance(sch, new MinHoursBalancingStrategy());
        assertEquals(1, result.keptManual());
        assertTrue(sch.isAssigned("B", "S1"));
        assertNull(sch.assignment("A", "S1").orElse(null));
    }

    static Schedule sampleSchedule() throws Exception {
        try (var v = ShiftBalancerServiceTest.class.getResourceAsStream("/data/volunteers.json");
             var s = ShiftBalancerServiceTest.class.getResourceAsStream("/data/shifts.json")) {
            var loader = new com.balancer.io.JsonDataLoader();
            return new Schedule(loader.volunteersFromString(new String(v.readAllBytes())),
                    loader.shiftsFromString(new String(s.readAllBytes())));
        }
    }
}
