package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that toggles the "expensive" flag of an insert slot
 * on a {@link MixerChannel} (story 129 UI).
 *
 * <p>The "expensive" flag marks an insert as eligible for selective
 * bypass when the channel's per-track CPU budget triggers the
 * {@code BypassExpensive} degradation policy. Executing this action
 * sets the flag to the new value; undoing it restores the previous
 * state.</p>
 */
public final class ToggleExpensiveAction implements UndoableAction {

    private final MixerChannel channel;
    private final int slotIndex;
    private final boolean newExpensive;
    private boolean previousExpensive;

    /**
     * Creates a new toggle-expensive action.
     *
     * @param channel      the mixer channel containing the insert slot
     * @param slotIndex    the index of the insert slot to toggle
     * @param newExpensive the new expensive flag value
     */
    public ToggleExpensiveAction(MixerChannel channel, int slotIndex, boolean newExpensive) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.slotIndex = slotIndex;
        this.newExpensive = newExpensive;
    }

    @Override
    public String description() {
        return "Toggle Expensive";
    }

    @Override
    public void execute() {
        previousExpensive = channel.getInsertSlots().get(slotIndex).isExpensive();
        channel.getInsertSlots().get(slotIndex).setExpensive(newExpensive);
    }

    @Override
    public void undo() {
        channel.getInsertSlots().get(slotIndex).setExpensive(previousExpensive);
    }
}
