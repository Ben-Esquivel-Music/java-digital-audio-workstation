package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes an effect ({@link InsertSlot}) from a
 * {@link MixerChannel}'s insert chain.
 *
 * <p>Executing this action removes the slot at the given index. Undoing it
 * re-inserts the slot at its original position.</p>
 */
public final class RemoveEffectAction implements UndoableAction {

    private final MixerChannel channel;
    private final int index;
    private InsertSlot removedSlot;

    /**
     * Creates a new remove-effect action.
     *
     * @param channel the mixer channel to remove the effect from
     * @param index   the index of the insert slot to remove
     */
    public RemoveEffectAction(MixerChannel channel, int index) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.index = index;
    }

    @Override
    public String description() {
        return "Remove Effect";
    }

    @Override
    public void execute() {
        removedSlot = channel.removeInsert(index);
    }

    @Override
    public void undo() {
        if (removedSlot != null) {
            channel.insertInsert(index, removedSlot);
        }
    }
}
