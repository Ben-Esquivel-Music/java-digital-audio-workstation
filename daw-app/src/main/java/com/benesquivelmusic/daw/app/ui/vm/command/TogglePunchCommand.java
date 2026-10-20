package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to flip the enabled flag of the existing punch region (story 328,
 * book §5.6). Raised by {@code DawAction.TOGGLE_PUNCH}; with no region
 * installed the handler leaves the transport untouched.
 */
public record TogglePunchCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.togglePunch();
    }
}
