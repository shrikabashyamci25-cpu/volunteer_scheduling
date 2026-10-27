package com.balancer;

import com.balancer.model.Assignment;
import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Violation;
import com.balancer.model.Volunteer;
import com.balancer.service.ValidationEngine;
import org.junit.Test;

import java.util.List;

import static com.balancer.TestData.shift;
import static com.balancer.TestData.vol;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ValidationEngineTest {

    private final ValidationEngine engine = new ValidationEngine();

    private static List<ConflictReason> reasons(List<Violation> vs) {
        return vs.stream().map(Violation::reason).toList();
    }

    @Test
    public void eligibleVolunteerHasNoViolations() {
        Volunteer v = vol("A", 10, "cooking", 3, "06:00", "14:00");
        Shift s = shift("S1", "cooking", 2, 1, "07:00", "10:00");
        assertTrue(engine.isEligible(v, s, new Schedule(List.of(v), List.of(s))));
    }

    @Test
    public void missingSkillAndLowLevel() {
        Volunteer noSkill = vol("A", 10, "driving", 3, "06:00", "14:00");
        Volunteer lowLevel = vol("B", 10, "cooking", 1, "06:00", "14:00");
        Shift s = shift("S1", "cooking", 2, 1, "07:00", "10:00");
        Schedule sch = new Schedule(List.of(noSkill, lowLevel), List.of(s));
        assertEquals(List.of(ConflictReason.MISSING_SKILL), reasons(engine.check(noSkill, s, sch)));
        assertEquals(List.of(ConflictReason.SKILL_LEVEL_TOO_LOW), reasons(engine.check(lowLevel, s, sch)));
    }

    @Test
    public void outsideAvailability() {
        Volunteer v = vol("A", 10, "cooking", 3, "08:00", "12:00");
        Shift s = shift("S1", "cooking", 1, 1, "07:00", "10:00");
        assertEquals(List.of(ConflictReason.NOT_AVAILABLE), reasons(engine.check(v, s, new Schedule(List.of(v), List.of(s)))));
    }

    @Test
    public void doubleBookingIsDetected() {
        Volunteer v = vol("A", 20, "cooking", 3, "06:00", "18:00");
        Shift s1 = shift("S1", "cooking", 1, 1, "08:00", "12:00");
        Shift s2 = shift("S2", "cooking", 1, 1, "11:00", "13:00");
        Schedule sch = new Schedule(List.of(v), List.of(s1, s2));
        sch.assign(Assignment.auto("A", "S1"));
        assertTrue(reasons(engine.check(v, s2, sch)).contains(ConflictReason.TIME_OVERLAP_CONFLICT));
    }

    @Test
    public void hourCapIsEnforced() {
        Volunteer v = vol("A", 5, "cooking", 3, "06:00", "18:00");
        Shift s1 = shift("S1", "cooking", 1, 1, "06:00", "10:00");
        Shift s2 = shift("S2", "cooking", 1, 1, "12:00", "14:00");
        Schedule sch = new Schedule(List.of(v), List.of(s1, s2));
        sch.assign(Assignment.auto("A", "S1"));
        assertEquals(List.of(ConflictReason.HOUR_CAP_REACHED), reasons(engine.check(v, s2, sch)));
    }

    @Test
    public void fullShiftIsRejected() {
        Volunteer a = vol("A", 10, "cooking", 3, "06:00", "18:00");
        Volunteer b = vol("B", 10, "cooking", 3, "06:00", "18:00");
        Shift s = shift("S1", "cooking", 1, 1, "08:00", "10:00");
        Schedule sch = new Schedule(List.of(a, b), List.of(s));
        sch.assign(Assignment.auto("A", "S1"));
        assertEquals(List.of(ConflictReason.SHIFT_FULL), reasons(engine.check(b, s, sch)));
    }

    @Test(expected = IllegalStateException.class)
    public void scheduleRefusesDoubleBookingEvenWithoutValidation() {
        Volunteer v = vol("A", 20, "cooking", 3, "06:00", "18:00");
        Schedule sch = new Schedule(List.of(v), List.of(
                shift("S1", "cooking", 1, 1, "08:00", "12:00"), shift("S2", "cooking", 1, 1, "09:00", "10:00")));
        sch.assign(Assignment.manual("A", "S1"));
        sch.assign(Assignment.manual("A", "S2"));
    }
}
