package com.balancer.persistence;

import com.balancer.model.Schedule;
import com.balancer.model.Shift;
import com.balancer.model.Volunteer;

import java.util.Collection;
import java.util.List;

/** Facade over the repositories: load a full Schedule from SQL, save one back. */
public final class DataStore {

    private final VolunteerRepository volunteers;
    private final ShiftRepository shifts;
    private final AssignmentRepository assignments;
    private final ReportRepository reports;

    public DataStore(Database db) {
        this.volunteers = new VolunteerRepository(db);
        this.shifts = new ShiftRepository(db);
        this.assignments = new AssignmentRepository(db);
        this.reports = new ReportRepository(db);
    }

    public boolean isEmpty() {
        return volunteers.count() == 0 && shifts.findAll().isEmpty();
    }

    public void saveShift(Shift s) {
        shifts.saveAll(List.of(s));
    }

    public void deleteShift(String id) {
        shifts.delete(id);
    }

    public void saveVolunteer(Volunteer v) {
        volunteers.saveAll(List.of(v));
    }

    public void deleteVolunteer(String id) {
        volunteers.delete(id);
    }

    public void replaceDataset(Collection<Volunteer> vols, Collection<Shift> shiftList) {
        assignments.deleteEverything();
        volunteers.saveAll(vols);
        shifts.saveAll(shiftList);
    }

    public Schedule loadSchedule() {
        Schedule schedule = new Schedule(volunteers.findAll(), shifts.findAll());
        assignments.findAll().forEach(a -> {
            try {
                schedule.assign(a);
            } catch (RuntimeException ignored) {
                // stale row (e.g. dataset changed) — skip it rather than refuse to start
            }
        });
        return schedule;
    }

    public void saveAssignments(Schedule schedule) {
        assignments.replaceAll(schedule.allAssignments());
    }

    public void audit(String action, String detail) {
        assignments.log(action, detail);
    }

    public ReportRepository reports() {
        return reports;
    }
}
