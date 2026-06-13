package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to toggle recording (§5.2 "Record"). Raised by the Record button and
 * the Transport menu (§2.8).
 */
public record ToggleRecordCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.toggleRecord();
    }
}
