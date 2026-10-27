package com.balancer.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The aggregate root: all volunteers, all shifts and the current assignments.
 * It is the only class allowed to book/release volunteer time, which keeps the
 * "no double booking" and "hours = sum of booked shifts" invariants in one place.
 */
public final class Schedule {

    private final Map<String, Volunteer> volunteers = new LinkedHashMap<>();
    private final Map<String, Shift> shifts = new LinkedHashMap<>();
    /** Skill index: fast lookup of who has which skill (Map&lt;Skill, Set&lt;Volunteer&gt;&gt;). */
    private final Map<Skill, Set<Volunteer>> skillIndex = new TreeMap<>();
    private final Map<String, Assignment> assignments = new LinkedHashMap<>();
    private final Map<String, Set<String>> volunteersByShift = new HashMap<>();

    public Schedule(Collection<Volunteer> volunteerList, Collection<Shift> shiftList) {
        for (Volunteer v : volunteerList) {
            if (volunteers.putIfAbsent(v.id(), v) != null) {
                throw new IllegalArgumentException("Duplicate volunteer id " + v.id());
            }
            v.releaseAll();
            v.skills().keySet().forEach(s -> skillIndex.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(v));
        }
        for (Shift s : shiftList) {
            if (shifts.putIfAbsent(s.id(), s) != null) {
                throw new IllegalArgumentException("Duplicate shift id " + s.id());
            }
            volunteersByShift.put(s.id(), new LinkedHashSet<>());
        }
    }

    // ---- lookups -------------------------------------------------------------------------

    public Collection<Volunteer> volunteers() { return Collections.unmodifiableCollection(volunteers.values()); }
    public Collection<Shift> shifts() { return Collections.unmodifiableCollection(shifts.values()); }

    public Volunteer volunteer(String id) {
        Volunteer v = volunteers.get(id);
        if (v == null) throw new NoSuchElementException("Unknown volunteer: " + id);
        return v;
    }

    public Shift shift(String id) {
        Shift s = shifts.get(id);
        if (s == null) throw new NoSuchElementException("Unknown shift: " + id);
        return s;
    }

    public Set<Volunteer> volunteersWithSkill(Skill skill) {
        return Collections.unmodifiableSet(skillIndex.getOrDefault(skill, Set.of()));
    }

    public Set<Skill> allSkills() {
        Set<Skill> all = new LinkedHashSet<>(skillIndex.keySet());
        shifts.values().forEach(s -> all.add(s.requiredSkill()));
        return all;
    }

    public boolean isAssigned(String volunteerId, String shiftId) {
        return assignments.containsKey(Assignment.key(volunteerId, shiftId));
    }

    public Optional<Assignment> assignment(String volunteerId, String shiftId) {
        return Optional.ofNullable(assignments.get(Assignment.key(volunteerId, shiftId)));
    }

    public int assignedCount(String shiftId) {
        return volunteersByShift.getOrDefault(shiftId, Set.of()).size();
    }

    public List<Volunteer> volunteersOn(String shiftId) {
        return volunteersByShift.getOrDefault(shiftId, Set.of()).stream().map(volunteers::get).toList();
    }

    public List<Shift> shiftsOf(String volunteerId) {
        return assignments.values().stream()
                .filter(a -> a.volunteerId().equals(volunteerId))
                .map(a -> shifts.get(a.shiftId()))
                .sorted((a, b) -> a.slot().compareTo(b.slot()))
                .toList();
    }

    public ShiftStatus status(Shift shift) {
        return ShiftStatus.of(assignedCount(shift.id()), shift.volunteersNeeded());
    }

    public List<Assignment> allAssignments() {
        return new ArrayList<>(assignments.values());
    }

    public Map<String, List<Assignment>> assignmentsByVolunteer() {
        return assignments.values().stream().collect(Collectors.groupingBy(Assignment::volunteerId));
    }

    // ---- mutation ------------------------------------------------------------------------

    /** Low-level add. Business rules are checked by ValidationEngine; this only guards invariants. */
    public void assign(Assignment a) {
        Volunteer v = volunteer(a.volunteerId());
        Shift s = shift(a.shiftId());
        if (assignments.containsKey(a.key())) {
            throw new IllegalStateException(v.name() + " is already on " + s.activity());
        }
        v.book(s.slot()); // throws on double booking
        assignments.put(a.key(), a);
        volunteersByShift.get(s.id()).add(v.id());
    }

    public Optional<Assignment> unassign(String volunteerId, String shiftId) {
        Assignment removed = assignments.remove(Assignment.key(volunteerId, shiftId));
        if (removed != null) {
            volunteer(volunteerId).release(shift(shiftId).slot());
            volunteersByShift.get(shiftId).remove(volunteerId);
        }
        return Optional.ofNullable(removed);
    }

    /** Removes auto-generated assignments, keeps manual (pinned) ones. */
    public List<Assignment> clearAutomatic() {
        List<Assignment> auto = assignments.values().stream().filter(a -> !a.manual()).toList();
        auto.forEach(a -> unassign(a.volunteerId(), a.shiftId()));
        return auto;
    }

    public void clearAll() {
        assignments.clear();
        volunteersByShift.values().forEach(Set::clear);
        volunteers.values().forEach(Volunteer::releaseAll);
    }

    /** Replace the whole assignment set (used for undo snapshots and loading from the DB). */
    public void restore(Collection<Assignment> snapshot) {
        clearAll();
        snapshot.forEach(this::assign);
    }
}
