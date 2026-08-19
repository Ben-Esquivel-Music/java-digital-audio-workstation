package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to skip the playhead back to the beginning (§5.2). Raised by the
 * Skip Back toolbar button, the keyboard shortcut, and the track strip's
 * skip-to-start action, all of which converge on the same command (§2.8 "one
 * path"; story 315 review closed the last transport gestures that bypassed
 * the command layer).
 */
public record SkipBackCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.skipBack();
    }
}
