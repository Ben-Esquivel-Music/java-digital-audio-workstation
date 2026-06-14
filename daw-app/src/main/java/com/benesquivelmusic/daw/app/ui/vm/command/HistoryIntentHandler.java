package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * The intent seam a {@link HistoryCommand} flows into — the up-going half of
 * "state flows down, intent flows up" (Control Synchronization Design Book §2.2,
 * §5.6). A command never touches the {@code UndoManager} directly; it asks the
 * handler to run the undo/redo.
 *
 * <p>The production implementation ({@link CoreHistoryIntentHandler}) runs the
 * §5.1 cascade per intent: VALIDATE (only when an undo/redo is actually
 * available) → MUTATE ({@code UndoManager.undo()/redo()}, which fires the manager's
 * history listener so {@code HistoryVM} republishes) → ANNOUNCE (publish
 * {@code UiEvent.UndoStateChanged} on the bus). Tests substitute a recording fake
 * to assert that a gesture issues the right command.</p>
 */
public interface HistoryIntentHandler {

    /** Undoes the most recent action (§5.6 "Undo"). */
    void undo();

    /** Redoes the most recently undone action (§5.6 "Redo"). */
    void redo();
}
