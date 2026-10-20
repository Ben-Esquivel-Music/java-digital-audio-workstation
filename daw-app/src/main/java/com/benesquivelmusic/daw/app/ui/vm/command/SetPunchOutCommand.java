package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to set the punch-out point at the playhead (story 328, book §5.6).
 * Raised by {@code DawAction.SET_PUNCH_OUT}; the handler owns the region
 * rules (the point just set wins, the other point yields).
 */
public record SetPunchOutCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.setPunchOut();
    }
}
