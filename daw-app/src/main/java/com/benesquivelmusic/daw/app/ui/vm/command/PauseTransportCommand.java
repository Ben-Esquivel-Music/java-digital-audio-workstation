package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to pause playback at the current position (§5.2; story 315). Raised by
 * the Play control while the transport is PLAYING — Play-toggles-pause (UI
 * Design Book §5.1) — so the Play button, the spacebar, and the Transport menu
 * all converge on the same command pair (§2.8).
 */
public record PauseTransportCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.pause();
    }
}
