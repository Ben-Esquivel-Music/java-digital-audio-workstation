package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to skip the playhead forward by four bars (§5.2). Raised by the
 * Skip Forward toolbar button and the keyboard shortcut, which converge on the
 * same command (§2.8 "one path"; story 315 review closed the last transport
 * gestures that bypassed the command layer). The relative jump is resolved by
 * the handler against the pending seek target — see
 * {@link TransportIntentHandler#skipForward()}.
 */
public record SkipForwardCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.skipForward();
    }
}
