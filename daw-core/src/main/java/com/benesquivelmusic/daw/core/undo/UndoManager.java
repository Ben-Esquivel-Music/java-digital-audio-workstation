package com.benesquivelmusic.daw.core.undo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Manages an undo/redo history of {@link UndoableAction} instances.
 *
 * <p>Actions are executed via {@link #execute(UndoableAction)}, which pushes them
 * onto the undo stack. Calling {@link #undo()} reverses the most recent action,
 * and {@link #redo()} re-applies the most recently undone action. Any new action
 * executed after an undo clears the redo stack (standard undo semantics).</p>
 *
 * <p>The history is capped at a configurable maximum depth. When the limit is
 * exceeded, the oldest actions are silently discarded.</p>
 */
public final class UndoManager {

    /** Default maximum number of undoable actions retained. */
    public static final int DEFAULT_MAX_HISTORY = 100;

    private final int maxHistory;
    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoableAction> redoStack = new ArrayDeque<>();

    /**
     * Creates an {@code UndoManager} with the default history limit.
     */
    public UndoManager() {
        this(DEFAULT_MAX_HISTORY);
    }

    /**
     * Creates an {@code UndoManager} with the given history limit.
     *
     * @param maxHistory the maximum number of undo steps to retain (must be &gt; 0)
     */
    public UndoManager(int maxHistory) {
        if (maxHistory <= 0) {
            throw new IllegalArgumentException("maxHistory must be positive: " + maxHistory);
        }
        this.maxHistory = maxHistory;
    }

    /**
     * Executes the given action and pushes it onto the undo stack.
     * The redo stack is cleared because the action creates a new timeline branch.
     *
     * @param action the action to execute
     */
    public void execute(UndoableAction action) {
        Objects.requireNonNull(action, "action must not be null");
        action.execute();
        undoStack.push(action);
        redoStack.clear();
        trimHistory();
    }

    /**
     * Undoes the most recent action.
     *
     * @return {@code true} if an action was undone, {@code false} if the undo stack was empty
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        UndoableAction action = undoStack.pop();
        action.undo();
        redoStack.push(action);
        return true;
    }

    /**
     * Redoes the most recently undone action.
     *
     * @return {@code true} if an action was redone, {@code false} if the redo stack was empty
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        UndoableAction action = redoStack.pop();
        action.execute();
        undoStack.push(action);
        trimHistory();
        return true;
    }

    /** Returns {@code true} if there is at least one action that can be undone. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Returns {@code true} if there is at least one action that can be redone. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Returns a human-readable description of the next action to undo,
     * or an empty string if the undo stack is empty.
     */
    public String undoDescription() {
        return undoStack.isEmpty() ? "" : undoStack.peek().description();
    }

    /**
     * Returns a human-readable description of the next action to redo,
     * or an empty string if the redo stack is empty.
     */
    public String redoDescription() {
        return redoStack.isEmpty() ? "" : redoStack.peek().description();
    }

    /** Returns the current number of actions on the undo stack. */
    public int undoSize() {
        return undoStack.size();
    }

    /** Returns the current number of actions on the redo stack. */
    public int redoSize() {
        return redoStack.size();
    }

    /** Clears both the undo and redo stacks. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    /** Returns the maximum history depth. */
    public int getMaxHistory() {
        return maxHistory;
    }

    private void trimHistory() {
        while (undoStack.size() > maxHistory) {
            undoStack.removeLast();
        }
    }
}
