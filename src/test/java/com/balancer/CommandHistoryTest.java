package com.balancer;

import com.balancer.command.AssignCommand;
import com.balancer.command.CommandHistory;
import com.balancer.command.EditRejectedException;
import com.balancer.command.RemoveCommand;
import com.balancer.command.SwapCommand;
import com.balancer.model.Assignment;
import com.balancer.model.ConflictReason;
import com.balancer.model.Schedule;
import com.balancer.service.ScheduleEditor;
import com.balancer.service.ValidationEngine;
import org.junit.Test;

import java.util.List;

import static com.balancer.TestData.shift;
import static com.balancer.TestData.vol;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CommandHistoryTest {

    private final ValidationEngine engine = new ValidationEngine();

    private Schedule schedule() {
        return new Schedule(
                List.of(vol("A", 10, "cooking", 3, "06:00", "22:00"), vol("B", 10, "driving", 3, "06:00", "22:00"),
                        vol("C", 10, "cooking", 3, "06:00", "22:00")),
                List.of(shift("K", "cooking", 1, 1, "08:00", "10:00"), shift("D", "driving", 1, 1, "08:00", "10:00"),
                        shift("K2", "cooking", 1, 1, "12:00", "14:00")));
    }

    @Test
    public void assignUndoRedo() {
        Schedule s = schedule();
        CommandHistory h = new CommandHistory();
        h.execute(new AssignCommand(engine, "A", "K", false), s);
        assertTrue(s.isAssigned("A", "K"));
        h.undo(s);
        assertFalse(s.isAssigned("A", "K"));
        assertEquals(0.0, s.volunteer("A").totalAssignedHours(), 1e-9);
        h.redo(s);
        assertTrue(s.isAssigned("A", "K"));
    }

    @Test
    public void invalidAssignIsRejectedButForceOverridesSoftRules() {
        Schedule s = schedule();
        CommandHistory h = new CommandHistory();
        try {
            h.execute(new AssignCommand(engine, "B", "K", false), s);
            fail("B has no cooking skill");
        } catch (EditRejectedException e) {
            assertEquals(ConflictReason.MISSING_SKILL, e.violations().get(0).reason());
        }
        assertFalse(h.canUndo());
        h.execute(new AssignCommand(engine, "B", "K", true), s);
        assertTrue(s.isAssigned("B", "K"));
        assertEquals(1, engine.revalidate(s).size()); // re-validation flags the forced skill deficit
    }

    @Test(expected = EditRejectedException.class)
    public void forceNeverAllowsDoubleBooking() {
        Schedule s = schedule();
        s.assign(Assignment.manual("A", "K"));
        new CommandHistory().execute(new AssignCommand(engine, "A", "D", true), s);
    }

    @Test
    public void swapAndUndoRestoresOriginal() {
        Schedule s = schedule();
        s.assign(Assignment.auto("A", "K"));
        s.assign(Assignment.auto("C", "K2"));
        CommandHistory h = new CommandHistory();
        h.execute(new SwapCommand(engine, "A", "K", "C", "K2"), s);
        assertTrue(s.isAssigned("A", "K2"));
        assertTrue(s.isAssigned("C", "K"));
        h.undo(s);
        assertTrue(s.isAssigned("A", "K"));
        assertTrue(s.isAssigned("C", "K2"));
    }

    @Test
    public void invalidSwapLeavesScheduleUntouched() {
        Schedule s = schedule();
        s.assign(Assignment.auto("A", "K"));
        s.assign(Assignment.auto("B", "D"));
        try {
            new CommandHistory().execute(new SwapCommand(engine, "A", "K", "B", "D"), s);
            fail("skills don't match");
        } catch (EditRejectedException expected) {
            assertTrue(s.isAssigned("A", "K"));
            assertTrue(s.isAssigned("B", "D"));
        }
    }

    @Test
    public void removeAndBalanceAreUndoable() {
        ScheduleEditor editor = new ScheduleEditor(schedule(), null);
        editor.balance("min-hours");
        int placed = editor.schedule().allAssignments().size();
        assertEquals(3, placed);
        var any = editor.schedule().allAssignments().get(0);
        editor.remove(any.volunteerId(), any.shiftId());
        assertEquals(2, editor.schedule().allAssignments().size());
        editor.undo();
        assertEquals(3, editor.schedule().allAssignments().size());
        editor.undo(); // undo the balance itself
        assertEquals(0, editor.schedule().allAssignments().size());
        editor.redo();
        assertEquals(3, editor.schedule().allAssignments().size());
        new RemoveCommand("A", "K"); // constructing does nothing until executed
    }
}
