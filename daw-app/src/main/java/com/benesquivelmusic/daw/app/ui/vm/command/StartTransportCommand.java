package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to start playback (§5.2 "Start / Play"). Raised by the Play button,
 * the spacebar, and the Transport menu (§2.8).
 */
public record StartTransportCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.start();
    }
}
