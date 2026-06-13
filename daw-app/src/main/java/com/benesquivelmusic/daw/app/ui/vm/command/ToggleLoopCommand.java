package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to toggle loop playback (§5.2 "Toggle loop"). Raised by the Loop
 * toggle and the Transport menu (§2.8).
 */
public record ToggleLoopCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.toggleLoop();
    }
}
