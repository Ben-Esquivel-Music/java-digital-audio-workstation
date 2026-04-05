package com.benesquivelmusic.daw.core.undo;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *
 * <p>{@link UndoHistoryListener} instances may be registered to receive
 * notifications whenever the undo/redo state changes (e.g. for updating an
 * undo history panel in the UI).</p>
 */
public final class UndoManager {

    /** Default maximum number of undoable actions retained. */
    public static final int DEFAULT_MAX_HISTORY = 100;

    private final int maxHistory;
    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoableAction> redoStack = new ArrayDeque<>();
    private final List<UndoHistoryListener> listeners = new CopyOnWriteArrayList<>();

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
        fireHistoryChanged();
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
        fireHistoryChanged();
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
        fireHistoryChanged();
        return true;
    }

    /**
     * Undoes or redoes actions to move the current position to the given
     * index in the combined history list returned by {@link #getHistory()}.
     *
     * <p>The history list is ordered oldest-first. Index 0 is the oldest
     * action on the undo stack; index {@code undoSize() - 1} is the most
     * recent action executed (the current position). Indices beyond the undo
     * stack correspond to actions on the redo stack.</p>
     *
     * <p>If the target index is less than the current position, actions are
     * undone until the current position equals the target. If the target is
     * greater, actions are redone. If the target equals the current position,
     * this method is a no-op.</p>
     *
     * @param targetIndex the target index in the history list (0-based)
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public void goToHistoryIndex(int targetIndex) {
        int totalSize = undoStack.size() + redoStack.size();
        if (targetIndex < -1 || targetIndex >= totalSize) {
            throw new IndexOutOfBoundsException(
                    "targetIndex out of range: " + targetIndex
                            + " (history size: " + totalSize + ")");
        }
        int currentIndex = undoStack.size() - 1;
        if (targetIndex < currentIndex) {
            int steps = currentIndex - targetIndex;
            for (int i = 0; i < steps; i++) {
                UndoableAction action = undoStack.pop();
                action.undo();
                redoStack.push(action);
            }
            fireHistoryChanged();
        } else if (targetIndex > currentIndex) {
            int steps = targetIndex - currentIndex;
            for (int i = 0; i < steps; i++) {
                UndoableAction action = redoStack.pop();
                action.execute();
                undoStack.push(action);
            }
            trimHistory();
            fireHistoryChanged();
        }
    }

    /**
     * Returns the current position in the history, which is the index of the
     * most recently executed (or redone) action. Returns {@code -1} if the
     * undo stack is empty (i.e. the user is at the very beginning).
     *
     * @return the current history index, or {@code -1}
     */
    public int getCurrentHistoryIndex() {
        return undoStack.size() - 1;
    }

    /**
     * Returns a combined, unmodifiable list of all actions in the history,
     * ordered from oldest (index 0) to newest. Actions on the undo stack
     * come first, followed by actions on the redo stack. The "current
     * position" is at index {@link #getCurrentHistoryIndex()}: all actions
     * at that index and below have been executed; those above have been
     * undone and are available for redo.
     *
     * @return an unmodifiable list of all history actions
     */
    public List<UndoableAction> getHistory() {
        List<UndoableAction> history = new ArrayList<>(undoStack.size() + redoStack.size());
        // undoStack is a LIFO deque — iterate in reverse to get oldest-first
        List<UndoableAction> undoList = new ArrayList<>(undoStack);
        Collections.reverse(undoList);
        history.addAll(undoList);
        // redoStack is also LIFO — top of redo is the next to redo, which
        // should appear right after the current position
        history.addAll(redoStack);
        return Collections.unmodifiableList(history);
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
        fireHistoryChanged();
    }

    /** Returns the maximum history depth. */
    public int getMaxHistory() {
        return maxHistory;
    }

    /**
     * Adds a listener that will be notified whenever the undo/redo history changes.
     *
     * @param listener the listener to add
     */
    public void addHistoryListener(UndoHistoryListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a previously registered history listener.
     *
     * @param listener the listener to remove
     */
    public void removeHistoryListener(UndoHistoryListener listener) {
        listeners.remove(listener);
    }

    private void trimHistory() {
        while (undoStack.size() > maxHistory) {
            undoStack.removeLast();
        }
    }

    private void fireHistoryChanged() {
        for (UndoHistoryListener listener : listeners) {
            listener.historyChanged(this);
        }
    }
}
