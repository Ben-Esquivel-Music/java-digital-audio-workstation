package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An undoable action that removes a CLAP plugin effect ({@link InsertSlot})
 * from a {@link MixerChannel}'s insert chain.
 *
 * <p>Unlike {@link RemoveEffectAction} (which manages built-in effects),
 * this action optionally disposes the underlying CLAP plugin host when the
 * removal is finalized (i.e., when the action is no longer undoable).</p>
 *
 * <p>Executing this action removes the slot. Undoing it re-inserts the
 * slot at its original position.</p>
 */
public final class RemoveClapEffectAction implements UndoableAction {

    private static final Logger LOGGER = Logger.getLogger(RemoveClapEffectAction.class.getName());

    private final MixerChannel channel;
    private final int index;
    private final ClapPluginManager pluginManager;
    private InsertSlot removedSlot;

    /**
     * Creates a new remove-CLAP-effect action.
     *
     * @param channel       the mixer channel to remove the effect from
     * @param index         the index of the insert slot to remove
     * @param pluginManager the CLAP plugin manager for disposal
     */
    public RemoveClapEffectAction(MixerChannel channel, int index,
                                  ClapPluginManager pluginManager) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.index = index;
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager must not be null");
    }

    @Override
    public String description() {
        return "Remove CLAP Effect";
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

    /**
     * Disposes the underlying CLAP plugin host. Call this when the action
     * is no longer undoable and the plugin resources should be freed.
     */
    public void disposePlugin() {
        if (removedSlot != null) {
            try {
                pluginManager.disposePlugin(removedSlot);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error disposing CLAP plugin", e);
            }
        }
    }

    /**
     * Returns the removed insert slot, or {@code null} if the action has
     * not been executed yet.
     *
     * @return the removed slot
     */
    public InsertSlot getRemovedSlot() {
        return removedSlot;
    }
}
