package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that toggles the mute state of a {@link MixerChannel}.
 *
 * <p>Executing this action sets the channel's mute state to the new value.
 * Undoing it restores the previous mute state.</p>
 */
public final class ToggleMuteAction implements UndoableAction {

    private final MixerChannel channel;
    private final boolean newMuted;
    private boolean previousMuted;

    /**
     * Creates a new toggle-mute action.
     *
     * @param channel  the mixer channel to toggle
     * @param newMuted the new mute state
     */
    public ToggleMuteAction(MixerChannel channel, boolean newMuted) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.newMuted = newMuted;
    }

    @Override
    public String description() {
        return "Toggle Mute";
    }

    @Override
    public void execute() {
        previousMuted = channel.isMuted();
        channel.setMuted(newMuted);
    }

    @Override
    public void undo() {
        channel.setMuted(previousMuted);
    }
}
