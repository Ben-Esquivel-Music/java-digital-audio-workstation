package com.benesquivelmusic.daw.core.undo;

/**
 * An action that can be undone and redone.
 *
 * <p>Every user-facing mutation to the project state should be wrapped in an
 * {@code UndoableAction} and executed via the {@link UndoManager} so that
 * users can freely undo/redo their work.</p>
 */
public interface UndoableAction {

    /** A short, human-readable description of this action (e.g. "Add Audio Track"). */
    String description();

    /** Executes the action (first time or redo). */
    void execute();

    /** Reverses the action (undo). */
    void undo();
}
