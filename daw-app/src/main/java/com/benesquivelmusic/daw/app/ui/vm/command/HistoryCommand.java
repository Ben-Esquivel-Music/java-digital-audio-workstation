package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * An undo/redo intent raised by a toolbar button, an Edit-menu item, or a
 * keyboard shortcut — the three of which converge on the <em>same</em> command
 * (§2.8). Commands are thin, immutable wrappers; they delegate to a
 * {@link HistoryIntentHandler} that owns the {@code UndoManager} mutation and the
 * {@code UndoStateChanged} announce (§2.2 "intent flows up").
 *
 * <p>The hierarchy is {@code sealed} so both history intents are enumerated at
 * compile time (Control Synchronization Design Book §5.6 undo/redo, §6.9).</p>
 */
public sealed interface HistoryCommand
        permits UndoCommand,
                RedoCommand {

    /**
     * Runs this intent against the given handler.
     *
     * @param handler the handler that owns the undo/redo mutation path; must not be {@code null}
     */
    void execute(HistoryIntentHandler handler);
}
