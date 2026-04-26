package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.model.Project;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot-based undo/redo manager for an immutable {@link Project}.
 *
 * <p>Because every {@code Project} is structurally equal to any other
 * project with the same field values, an undo entry is simply the pair
 * {@code (before, after)} captured by {@link #record(Project, Project)}.
 * {@link #undo()} replaces the store's current snapshot with the matching
 * {@code before} value and pushes the entry onto the redo stack;
 * {@link #redo()} does the inverse.</p>
 *
 * <p>This class is thread-confined — call it from the UI / dispatcher
 * thread, the same context that decides when to invoke
 * {@link ProjectStore#apply(CompoundAction)}.</p>
 */
public final class UndoManager {

    /**
     * A single recorded transition between two project snapshots.
     */
    public record Entry(Project before, Project after) {
        public Entry {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(after, "after must not be null");
        }
    }

    private final ProjectStore store;
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();
    private final int capacity;

    public UndoManager(ProjectStore store) {
        this(store, 256);
    }

    public UndoManager(ProjectStore store, int capacity) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Records a transition. The redo stack is cleared because a fresh
     * action invalidates any previously-undone history.
     */
    public void record(Project before, Project after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (before.equals(after)) {
            return;
        }
        undoStack.push(new Entry(before, after));
        while (undoStack.size() > capacity) {
            undoStack.pollLast();
        }
        redoStack.clear();
    }

    /**
     * Convenience: applies {@code action} to the {@link ProjectStore} and
     * records the resulting transition for undo.
     *
     * <p>The transition is captured atomically via
     * {@link ProjectStore#applyForTransition(CompoundAction)} so the
     * recorded {@code (before, after)} pair is guaranteed to represent
     * the actual commit, even if other writers contend on the store
     * concurrently.</p>
     *
     * @return the new snapshot
     */
    public Project applyAndRecord(CompoundAction action) {
        Objects.requireNonNull(action, "action must not be null");
        ProjectStore.Transition transition = store.applyForTransition(action);
        record(transition.before(), transition.after());
        return transition.after();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Reverts the most recently recorded transition by replacing the
     * store's snapshot with its {@code before} value. Returns the new
     * snapshot, or {@link Optional#empty()} if there is nothing to undo.
     */
    public Optional<Project> undo() {
        Entry e = undoStack.poll();
        if (e == null) {
            return Optional.empty();
        }
        redoStack.push(e);
        store.replace(e.before());
        return Optional.of(e.before());
    }

    /**
     * Re-applies the most recently undone transition. Returns the new
     * snapshot, or {@link Optional#empty()} if there is nothing to redo.
     */
    public Optional<Project> redo() {
        Entry e = redoStack.poll();
        if (e == null) {
            return Optional.empty();
        }
        undoStack.push(e);
        store.replace(e.after());
        return Optional.of(e.after());
    }

    /** Drops all recorded history without touching the store. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
