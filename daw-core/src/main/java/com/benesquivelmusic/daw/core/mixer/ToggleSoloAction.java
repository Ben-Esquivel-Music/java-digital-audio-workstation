package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that toggles the solo state of a {@link MixerChannel}.
 *
 * <p>Executing this action sets the channel's solo state to the new value.
 * Undoing it restores the previous solo state.</p>
 */
public final class ToggleSoloAction implements UndoableAction {

    private final MixerChannel channel;
    private final boolean newSolo;
    private boolean previousSolo;

    /**
     * Creates a new toggle-solo action.
     *
     * @param channel the mixer channel to toggle
     * @param newSolo the new solo state
     */
    public ToggleSoloAction(MixerChannel channel, boolean newSolo) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.newSolo = newSolo;
    }

    @Override
    public String description() {
        return "Toggle Solo";
    }

    @Override
    public void execute() {
        previousSolo = channel.isSolo();
        channel.setSolo(newSolo);
    }

    @Override
    public void undo() {
        channel.setSolo(previousSolo);
    }
}
