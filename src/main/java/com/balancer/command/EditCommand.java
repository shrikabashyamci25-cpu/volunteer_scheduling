package com.balancer.command;

import com.balancer.model.Schedule;

/** Command pattern: every admin action is an object that knows how to do and undo itself. */
public interface EditCommand {

    /** Apply the change. Must throw {@link EditRejectedException} and leave the schedule untouched on failure. */
    void execute(Schedule schedule);

    void undo(Schedule schedule);

    /** Short label for the history panel, e.g. "Assign Asha → Food sorting". */
    String description();

    /** Category for the audit log (ASSIGN, REMOVE, SWAP, BALANCE, CLEAR). */
    String action();
}
