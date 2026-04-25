package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the {@link SendTap tap point} of an existing
 * {@link Send} on a mixer channel.
 *
 * <p>The send must already exist on the channel; if no send routes to the
 * specified target, this action is a no-op (both {@code execute} and
 * {@code undo}). To create or remove a send, use
 * {@link SetSendRoutingAction}.</p>
 *
 * <p>Undoing this action restores the previous tap point.</p>
 */
public final class SetSendTapAction implements UndoableAction {

    private final MixerChannel channel;
    private final MixerChannel target;
    private final SendTap newTap;
    private SendTap previousTap;
    private boolean applied;

    /**
     * Creates a new set-send-tap action.
     *
     * @param channel the mixer channel whose send is being updated
     * @param target  the return bus identifying the send to update
     * @param newTap  the new tap point
     */
    public SetSendTapAction(MixerChannel channel, MixerChannel target, SendTap newTap) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.newTap = Objects.requireNonNull(newTap, "newTap must not be null");
    }

    @Override
    public String description() {
        return "Set Send Tap";
    }

    @Override
    public void execute() {
        Send send = channel.getSendForTarget(target);
        if (send == null) {
            applied = false;
            return;
        }
        previousTap = send.getTap();
        send.setTap(newTap);
        applied = true;
    }

    @Override
    public void undo() {
        if (!applied) {
            return;
        }
        Send send = channel.getSendForTarget(target);
        if (send == null) {
            return;
        }
        send.setTap(previousTap);
    }
}
