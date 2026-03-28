package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the pan position of a {@link MixerChannel}.
 *
 * <p>Executing this action sets the channel's pan to the new value.
 * Undoing it restores the previous pan position.</p>
 */
public final class SetPanAction implements UndoableAction {

    private final MixerChannel channel;
    private final double newPan;
    private double previousPan;

    /**
     * Creates a new set-pan action.
     *
     * @param channel the mixer channel to adjust
     * @param newPan  the new pan position (−1.0 to 1.0)
     */
    public SetPanAction(MixerChannel channel, double newPan) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.newPan = newPan;
    }

    @Override
    public String description() {
        return "Adjust Pan";
    }

    @Override
    public void execute() {
        previousPan = channel.getPan();
        channel.setPan(newPan);
    }

    @Override
    public void undo() {
        channel.setPan(previousPan);
    }
}
