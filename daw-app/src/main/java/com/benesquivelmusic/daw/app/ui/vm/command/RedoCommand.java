package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to redo the most recently undone action (§5.6). Raised by the redo
 * toolbar button, the Edit &rarr; Redo menu item, and {@code Ctrl+Y} /
 * {@code Ctrl+Shift+Z}.
 */
public record RedoCommand() implements HistoryCommand {

    @Override
    public void execute(HistoryIntentHandler handler) {
        handler.redo();
    }
}
