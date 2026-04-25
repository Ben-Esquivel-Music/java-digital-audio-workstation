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
    /**
     * Captured pre-execute {@link SendTap} so that {@link #undo()} can
     * fully restore PRE_INSERTS — which the legacy {@link SendMode} view
     * collapses to PRE_FADER and therefore cannot represent on its own.
     */
    private SendTap previousTap;
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
            // Capture the full tap (PRE_INSERTS / PRE_FADER / POST_FADER)
            // so undo can restore PRE_INSERTS — the legacy SendMode view
            // collapses PRE_INSERTS to PRE_FADER and is lossy.
            previousTap = existing.getTap();
            existing.setLevel(newLevel);
            // Only update the legacy mode when it actually changes. The
            // {@link Send#setMode} call collapses {@link SendTap#PRE_INSERTS}
            // back to {@link SendTap#PRE_FADER}, so skipping the call when
            // the mode is unchanged preserves any PRE_INSERTS tap that may
            // have been configured separately (e.g. by SetSendTapAction).
            if (existing.getMode() != newMode) {
                existing.setMode(newMode);
            }
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
            // Restore the full tap (which may have been PRE_INSERTS — a
            // value the legacy SendMode cannot represent). Only call
            // setTap when it actually differs from the current value so
            // we avoid an unnecessary write on the common no-op path.
            if (previousTap != null && existing.getTap() != previousTap) {
                existing.setTap(previousTap);
            } else if (previousTap == null && existing.getMode() != previousMode) {
                // Defensive: if previousTap was never captured (shouldn't
                // happen post-execute, but keeps the action robust if
                // someone calls undo() without a prior execute()), fall
                // back to the legacy mode-based restore.
                existing.setMode(previousMode);
            }
        } else {
            channel.removeSend(existing);
        }
    }
}
