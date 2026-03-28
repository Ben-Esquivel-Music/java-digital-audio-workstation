package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the volume of a {@link MixerChannel}.
 *
 * <p>Executing this action sets the channel's volume to the new value.
 * Undoing it restores the previous volume.</p>
 */
public final class SetVolumeAction implements UndoableAction {

    private final MixerChannel channel;
    private final double newVolume;
    private double previousVolume;

    /**
     * Creates a new set-volume action.
     *
     * @param channel   the mixer channel to adjust
     * @param newVolume the new volume level (0.0 – 1.0)
     */
    public SetVolumeAction(MixerChannel channel, double newVolume) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.newVolume = newVolume;
    }

    @Override
    public String description() {
        return "Adjust Volume";
    }

    @Override
    public void execute() {
        previousVolume = channel.getVolume();
        channel.setVolume(newVolume);
    }

    @Override
    public void undo() {
        channel.setVolume(previousVolume);
    }
}
