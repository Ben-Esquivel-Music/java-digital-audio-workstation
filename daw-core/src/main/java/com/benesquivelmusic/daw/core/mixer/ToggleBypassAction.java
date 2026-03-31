package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that toggles the bypass state of an insert effect slot
 * on a {@link MixerChannel}.
 *
 * <p>Executing this action sets the slot's bypass state to the new value.
 * Undoing it restores the previous bypass state.</p>
 */
public final class ToggleBypassAction implements UndoableAction {

    private final MixerChannel channel;
    private final int slotIndex;
    private final boolean newBypassed;
    private boolean previousBypassed;

    /**
     * Creates a new toggle-bypass action.
     *
     * @param channel     the mixer channel containing the insert slot
     * @param slotIndex   the index of the insert slot to toggle
     * @param newBypassed the new bypass state
     */
    public ToggleBypassAction(MixerChannel channel, int slotIndex, boolean newBypassed) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.slotIndex = slotIndex;
        this.newBypassed = newBypassed;
    }

    @Override
    public String description() {
        return "Toggle Bypass";
    }

    @Override
    public void execute() {
        previousBypassed = channel.getInsertSlots().get(slotIndex).isBypassed();
        channel.setInsertBypassed(slotIndex, newBypassed);
    }

    @Override
    public void undo() {
        channel.setInsertBypassed(slotIndex, previousBypassed);
    }
}
