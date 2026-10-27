package com.balancer.command;

import com.balancer.model.Schedule;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** Undo / redo stacks for {@link EditCommand}s. */
public final class CommandHistory {

    private static final int LIMIT = 100;

    private final Deque<EditCommand> undoStack = new ArrayDeque<>();
    private final Deque<EditCommand> redoStack = new ArrayDeque<>();

    public void execute(EditCommand cmd, Schedule schedule) {
        cmd.execute(schedule); // throws before touching the stacks if rejected
        undoStack.push(cmd);
        if (undoStack.size() > LIMIT) undoStack.removeLast();
        redoStack.clear();
    }

    public Optional<EditCommand> undo(Schedule schedule) {
        if (undoStack.isEmpty()) return Optional.empty();
        EditCommand cmd = undoStack.pop();
        cmd.undo(schedule);
        redoStack.push(cmd);
        return Optional.of(cmd);
    }

    public Optional<EditCommand> redo(Schedule schedule) {
        if (redoStack.isEmpty()) return Optional.empty();
        EditCommand cmd = redoStack.pop();
        cmd.execute(schedule);
        undoStack.push(cmd);
        return Optional.of(cmd);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public Optional<String> nextUndo() { return Optional.ofNullable(undoStack.peek()).map(EditCommand::description); }
    public Optional<String> nextRedo() { return Optional.ofNullable(redoStack.peek()).map(EditCommand::description); }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
