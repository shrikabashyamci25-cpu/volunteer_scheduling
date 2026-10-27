package com.balancer;

import com.balancer.model.TimeSlot;
import com.balancer.util.TimeOverlapChecker;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimeOverlapCheckerTest {

    private static TimeSlot t(String a, String b) {
        return TimeSlot.parse("2026-11-02T" + a, "2026-11-02T" + b);
    }

    @Test
    public void overlappingIntervalsAreDetected() {
        assertTrue(TimeOverlapChecker.overlaps(t("09:00", "12:00"), t("11:00", "13:00")));
        assertTrue(TimeOverlapChecker.overlaps(t("09:00", "12:00"), t("10:00", "11:00")));
    }

    @Test
    public void backToBackShiftsDoNotOverlap() {
        assertFalse(TimeOverlapChecker.overlaps(t("09:00", "12:00"), t("12:00", "14:00")));
        assertFalse(TimeOverlapChecker.overlaps(t("12:00", "14:00"), t("09:00", "12:00")));
    }

    @Test
    public void containmentAndOverlapDuration() {
        assertTrue(t("08:00", "18:00").contains(t("09:00", "12:00")));
        assertFalse(t("08:00", "11:00").contains(t("09:00", "12:00")));
        assertEquals(Duration.ofHours(1), TimeOverlapChecker.overlapDuration(t("09:00", "12:00"), t("11:00", "13:00")));
    }

    @Test
    public void touchingWindowsAreMerged() {
        List<TimeSlot> merged = TimeOverlapChecker.merge(List.of(t("12:00", "16:00"), t("08:00", "12:00"), t("18:00", "20:00")));
        assertEquals(List.of(t("08:00", "16:00"), t("18:00", "20:00")), merged);
    }

    @Test(expected = IllegalArgumentException.class)
    public void endBeforeStartIsRejected() {
        t("12:00", "09:00");
    }
}
