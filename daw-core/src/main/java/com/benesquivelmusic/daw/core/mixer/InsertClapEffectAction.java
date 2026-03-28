package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that inserts a CLAP plugin effect ({@link InsertSlot})
 * into a {@link MixerChannel}'s insert chain at a given position.
 *
 * <p>Unlike {@link InsertEffectAction} (which manages built-in effects),
 * this action handles the CLAP plugin lifecycle: undoing the insert
 * removes the slot from the chain, and the caller is responsible for
 * disposing the underlying CLAP plugin when it is no longer needed.</p>
 *
 * <p>Executing this action inserts the slot. Undoing it removes the slot.</p>
 */
public final class InsertClapEffectAction implements UndoableAction {

    private final MixerChannel channel;
    private final int index;
    private final InsertSlot slot;

    /**
     * Creates a new insert-CLAP-effect action.
     *
     * @param channel the mixer channel to insert the effect into
     * @param index   the insertion index in the insert chain
     * @param slot    the insert slot wrapping the CLAP plugin
     */
    public InsertClapEffectAction(MixerChannel channel, int index, InsertSlot slot) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.index = index;
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
    }

    @Override
    public String description() {
        return "Insert CLAP Effect";
    }

    @Override
    public void execute() {
        channel.insertInsert(index, slot);
    }

    @Override
    public void undo() {
        channel.removeInsert(slot);
    }

    /**
     * Returns the insert slot managed by this action.
     *
     * @return the CLAP insert slot
     */
    public InsertSlot getSlot() {
        return slot;
    }
}
