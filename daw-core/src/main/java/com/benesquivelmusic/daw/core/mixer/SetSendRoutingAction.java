package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the send routing on a mixer channel.
 *
 * <p>This action can add a new send, update an existing send's level or mode,
 * or remove a send. Undoing it restores the previous state.</p>
 */
public final class SetSendRoutingAction implements UndoableAction {

    private final MixerChannel channel;
    private final MixerChannel target;
    private final double newLevel;
    private final SendMode newMode;
    private double previousLevel;
    private SendMode previousMode;
    private boolean hadSendBefore;

    /**
     * Creates a new set-send-routing action.
     *
     * @param channel  the mixer channel whose send is being changed
     * @param target   the return bus target
     * @param newLevel the new send level (0.0–1.0)
     * @param newMode  the new send mode
     */
    public SetSendRoutingAction(MixerChannel channel, MixerChannel target,
                                double newLevel, SendMode newMode) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.newMode = Objects.requireNonNull(newMode, "newMode must not be null");
        if (newLevel < 0.0 || newLevel > 1.0) {
            throw new IllegalArgumentException("newLevel must be between 0.0 and 1.0: " + newLevel);
        }
        this.newLevel = newLevel;
    }

    @Override
    public String description() {
        return "Set Send Routing";
    }

    @Override
    public void execute() {
        Send existing = channel.getSendForTarget(target);
        if (existing != null) {
            hadSendBefore = true;
            previousLevel = existing.getLevel();
            previousMode = existing.getMode();
            existing.setLevel(newLevel);
            existing.setMode(newMode);
        } else {
            hadSendBefore = false;
            Send send = new Send(target, newLevel, newMode);
            channel.addSend(send);
        }
    }

    @Override
    public void undo() {
        Send existing = channel.getSendForTarget(target);
        if (existing == null) {
            return;
        }
        if (hadSendBefore) {
            existing.setLevel(previousLevel);
            existing.setMode(previousMode);
        } else {
            channel.removeSend(existing);
        }
    }
}
