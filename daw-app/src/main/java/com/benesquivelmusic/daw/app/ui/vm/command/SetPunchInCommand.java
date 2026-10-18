package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to set the punch-in point at the playhead (story 328, book §5.6).
 * Raised by {@code DawAction.SET_PUNCH_IN}; the handler owns the region
 * rules (the point just set wins, the other point yields).
 */
public record SetPunchInCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.setPunchIn();
    }
}
