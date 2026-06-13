package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to stop playback (§5.2 "Stop"). Raised by the Stop button, the
 * spacebar, and the Transport menu (§2.8).
 */
public record StopTransportCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.stop();
    }
}
