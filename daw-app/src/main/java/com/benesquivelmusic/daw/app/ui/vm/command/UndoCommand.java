package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to undo the most recent action (§5.6). Raised by the undo toolbar
 * button, the Edit &rarr; Undo menu item, and {@code Ctrl+Z}.
 */
public record UndoCommand() implements HistoryCommand {

    @Override
    public void execute(HistoryIntentHandler handler) {
        handler.undo();
    }
}
