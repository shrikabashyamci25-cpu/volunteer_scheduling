package com.balancer;

import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

/** Small helpers to build test fixtures quickly. */
final class TestData {

    private TestData() {
    }

    static final String MON = "2026-11-02";

    static Volunteer vol(String id, int cap, String skill, int level, String from, String to) {
        return Volunteer.builder().id(id).name("Vol " + id).maxWeeklyHours(cap)
                .skill(skill, level).available(MON + "T" + from, MON + "T" + to).build();
    }

    static Shift shift(String id, String skill, int minLevel, int needed, String from, String to) {
        return Shift.builder().id(id).activity("Act " + id).slot(MON + "T" + from, MON + "T" + to)
                .requiredSkill(skill).minSkillLevel(minLevel).volunteersNeeded(needed).build();
    }
}
