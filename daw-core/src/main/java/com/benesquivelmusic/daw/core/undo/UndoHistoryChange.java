package com.benesquivelmusic.daw.core.undo;

/**
 * What an {@link UndoManager} did to its history, reported to every
 * {@link UndoHistoryListener} with the change.
 *
 * <p>The kind is the discriminator between a change that alters the project
 * and one that only alters the history. An {@link #EXECUTED execute},
 * {@link #UNDONE undo}, {@link #REDONE redo} or {@link #JUMPED history jump}
 * applies or reverses actions, so the project now differs from what was last
 * saved; a {@link #CLEARED clear} or {@link #REDO_CLEARED redo-stack clear}
 * discards entries without touching the project, so it leaves a clean
 * project clean ({@link #mutatesProject()}). Opening or creating a project
 * clears no history — the application installs a fresh, empty manager —
 * and saving leaves the history as it is. The kind is reported by the
 * operation itself, never inferred from the stack sizes: at the history cap
 * an execute pushes one action and trims the oldest, leaving both sizes
 * unchanged.</p>
 */
public enum UndoHistoryChange {

    /** {@link UndoManager#execute} ran an action and pushed it. */
    EXECUTED(true),

    /** {@link UndoManager#undo} reversed the most recent action. */
    UNDONE(true),

    /** {@link UndoManager#redo} re-applied the most recently undone action. */
    REDONE(true),

    /** {@link UndoManager#goToHistoryIndex} undid or redid one or more actions. */
    JUMPED(true),

    /** {@link UndoManager#clear} discarded both stacks; the project is untouched. */
    CLEARED(false),

    /** {@link UndoManager#clearRedoStack} discarded the redo stack; the project is untouched. */
    REDO_CLEARED(false);

    private final boolean mutatesProject;

    UndoHistoryChange(boolean mutatesProject) {
        this.mutatesProject = mutatesProject;
    }

    /**
     * Returns whether this change applied or reversed actions on the project,
     * so the project now differs from what was last saved.
     *
     * @return {@code true} for execute, undo, redo and a history jump;
     *         {@code false} for a clear of either stack
     */
    public boolean mutatesProject() {
        return mutatesProject;
    }
}
