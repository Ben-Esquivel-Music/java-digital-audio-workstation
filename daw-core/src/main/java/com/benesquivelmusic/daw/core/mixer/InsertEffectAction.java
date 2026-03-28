package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that inserts an effect ({@link InsertSlot}) into a
 * {@link MixerChannel}'s insert chain at a given position.
 *
 * <p>Executing this action inserts the slot. Undoing it removes the slot.</p>
 */
public final class InsertEffectAction implements UndoableAction {

    private final MixerChannel channel;
    private final int index;
    private final InsertSlot slot;

    /**
     * Creates a new insert-effect action.
     *
     * @param channel the mixer channel to insert the effect into
     * @param index   the insertion index in the insert chain
     * @param slot    the insert slot to add
     */
    public InsertEffectAction(MixerChannel channel, int index, InsertSlot slot) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.index = index;
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
    }

    @Override
    public String description() {
        return "Insert Effect";
    }

    @Override
    public void execute() {
        channel.insertInsert(index, slot);
    }

    @Override
    public void undo() {
        channel.removeInsert(slot);
    }
}
