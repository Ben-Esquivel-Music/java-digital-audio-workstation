package com.benesquivelmusic.daw.core.undo;

/**
 * Listener interface for receiving notifications when the undo/redo
 * history changes.
 *
 * <p>Register instances with {@link UndoManager#addHistoryListener(UndoHistoryListener)}
 * to be notified after every execute, undo, redo, history jump, or clear
 * operation. The {@link UndoHistoryChange} says which one it was, so a
 * listener can tell a change to the project (execute, undo, redo, jump) from
 * one that only discards history (clear) — see
 * {@link UndoHistoryChange#mutatesProject()}.</p>
 */
@FunctionalInterface
public interface UndoHistoryListener {

    /**
     * Called whenever the undo/redo history has changed.
     *
     * @param manager the {@link UndoManager} whose history changed
     * @param change  what the manager did
     */
    void historyChanged(UndoManager manager, UndoHistoryChange change);
}
