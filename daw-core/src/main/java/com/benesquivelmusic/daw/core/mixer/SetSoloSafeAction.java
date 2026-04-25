package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that sets the
 * {@linkplain MixerChannel#isSoloSafe() solo-safe} flag of a
 * {@link MixerChannel}.
 *
 * <p>Executing this action sets the channel's solo-safe state to the new
 * value. Undoing it restores the previous state.</p>
 */
public final class SetSoloSafeAction implements UndoableAction {

    private final MixerChannel channel;
    private final boolean newSoloSafe;
    private boolean previousSoloSafe;

    /**
     * Creates a new set-solo-safe action.
     *
     * @param channel     the mixer channel whose solo-safe flag will change
     * @param newSoloSafe the new solo-safe state
     */
    public SetSoloSafeAction(MixerChannel channel, boolean newSoloSafe) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.newSoloSafe = newSoloSafe;
    }

    @Override
    public String description() {
        return "Toggle Solo Safe";
    }

    @Override
    public void execute() {
        previousSoloSafe = channel.isSoloSafe();
        channel.setSoloSafe(newSoloSafe);
    }

    @Override
    public void undo() {
        channel.setSoloSafe(previousSoloSafe);
    }
}
