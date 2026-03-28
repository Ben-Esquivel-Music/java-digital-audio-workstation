package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that reorders an insert effect slot within a
 * {@link MixerChannel}'s insert chain.
 *
 * <p>Executing this action moves the insert slot from one position to
 * another. Undoing it reverses the move.</p>
 */
public final class ReorderEffectAction implements UndoableAction {

    private final MixerChannel channel;
    private final int fromIndex;
    private final int toIndex;

    /**
     * Creates a new reorder-effect action.
     *
     * @param channel   the mixer channel containing the insert slots
     * @param fromIndex the current index of the slot to move
     * @param toIndex   the target index for the slot
     */
    public ReorderEffectAction(MixerChannel channel, int fromIndex, int toIndex) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
    }

    @Override
    public String description() {
        return "Reorder Effect";
    }

    @Override
    public void execute() {
        channel.moveInsert(fromIndex, toIndex);
    }

    @Override
    public void undo() {
        channel.moveInsert(toIndex, fromIndex);
    }
}
