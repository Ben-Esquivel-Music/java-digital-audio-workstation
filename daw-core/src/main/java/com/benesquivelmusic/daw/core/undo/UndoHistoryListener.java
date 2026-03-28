package com.benesquivelmusic.daw.core.undo;

/**
 * Listener interface for receiving notifications when the undo/redo
 * history changes.
 *
 * <p>Register instances with {@link UndoManager#addHistoryListener(UndoHistoryListener)}
 * to be notified after every execute, undo, redo, or clear operation.</p>
 */
@FunctionalInterface
public interface UndoHistoryListener {

    /**
     * Called whenever the undo/redo history has changed.
     *
     * @param manager the {@link UndoManager} whose history changed
     */
    void historyChanged(UndoManager manager);
}
