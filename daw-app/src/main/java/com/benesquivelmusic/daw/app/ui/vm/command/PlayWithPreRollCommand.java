package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to start playback with the configured pre-roll applied (Story 134;
 * §5.2). Raised by the Shift+Space keyboard shortcut — and any future surface
 * that means "play with context" — through the same command path as every
 * other transport gesture (§2.8 "one path"; story 315 review closed the last
 * transport gestures that bypassed the command layer).
 *
 * <p>The pre/post-roll <em>toggles</em> are deliberately not commands: they
 * configure the transport rather than transition it, so they stay imperative
 * on the production controller.</p>
 */
public record PlayWithPreRollCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.playWithPreRoll();
    }
}
