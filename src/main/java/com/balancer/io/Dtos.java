package com.balancer.io;

import com.balancer.model.Shift;
import com.balancer.model.TimeSlot;
import com.balancer.model.Volunteer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain records that mirror the JSON/CSV file format, plus mapping to/from domain objects. */
public final class Dtos {

    private Dtos() {
    }

    public record SlotDto(LocalDateTime start, LocalDateTime end) {
    }

    public record VolunteerDto(String id, String name, String email, int maxWeeklyHours,
                               Map<String, Integer> skills, List<SlotDto> availability) {
    }

    public record ShiftDto(String id, String activity, String location, LocalDateTime start, LocalDateTime end,
                           String requiredSkill, int minSkillLevel, int volunteersNeeded, int priority) {
    }

    public record Dataset(List<VolunteerDto> volunteers, List<ShiftDto> shifts) {
    }

    // ---- DTO -> domain (via Builders) -----------------------------------------------------

    public static Volunteer toDomain(VolunteerDto d) {
        Volunteer.Builder b = Volunteer.builder()
                .id(d.id()).name(d.name()).email(d.email()).maxWeeklyHours(d.maxWeeklyHours());
        if (d.skills() != null) d.skills().forEach(b::skill);
        if (d.availability() != null) d.availability().forEach(s -> b.available(s.start(), s.end()));
        return b.build();
    }

    public static Shift toDomain(ShiftDto d) {
        return Shift.builder()
                .id(d.id()).activity(d.activity()).location(d.location())
                .slot(d.start(), d.end())
                .requiredSkill(d.requiredSkill())
                .minSkillLevel(d.minSkillLevel() == 0 ? 1 : d.minSkillLevel())
                .volunteersNeeded(d.volunteersNeeded() == 0 ? 1 : d.volunteersNeeded())
                .priority(d.priority() == 0 ? 2 : d.priority())
                .build();
    }

    // ---- domain -> DTO -------------------------------------------------------------------

    public static VolunteerDto toDto(Volunteer v) {
        Map<String, Integer> skills = new LinkedHashMap<>();
        v.skills().forEach((k, lvl) -> skills.put(k.name(), lvl));
        List<SlotDto> av = v.availability().stream().map(Dtos::toDto).toList();
        return new VolunteerDto(v.id(), v.name(), v.email(), v.maxWeeklyHours(), skills, av);
    }

    public static ShiftDto toDto(Shift s) {
        return new ShiftDto(s.id(), s.activity(), s.location(), s.slot().start(), s.slot().end(),
                s.requiredSkill().name(), s.minSkillLevel(), s.volunteersNeeded(), s.priority());
    }

    public static SlotDto toDto(TimeSlot t) {
        return new SlotDto(t.start(), t.end());
    }
}
