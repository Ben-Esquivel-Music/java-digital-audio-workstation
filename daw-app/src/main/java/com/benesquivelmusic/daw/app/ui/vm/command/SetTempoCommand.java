package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to set the initial tempo (§5.2 "Set tempo"). Raised by the tempo field
 * on commit (focus-loss / Enter), never per keystroke (§4.4).
 *
 * @param bpm the requested tempo in beats per minute
 */
public record SetTempoCommand(double bpm) implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.setTempo(bpm);
    }
}
