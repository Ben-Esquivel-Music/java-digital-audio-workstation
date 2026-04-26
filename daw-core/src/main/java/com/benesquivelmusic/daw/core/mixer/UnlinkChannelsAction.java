package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that unlinks the stereo pair containing the given
 * channel.
 *
 * <p>The original {@link ChannelLink} is captured at execution time so that
 * undo can restore the exact same record (same mode and per-attribute
 * toggles). Per the issue, unlinking preserves both channels' current
 * values; this action only mutates the link set, never the channel
 * strips.</p>
 */
public final class UnlinkChannelsAction implements UndoableAction {

    private final ChannelLinkManager manager;
    private final UUID channelId;
    private ChannelLink removedLink;

    /**
     * Creates the action.
     *
     * @param manager   the manager that owns the link set
     * @param channelId the channel whose link should be removed (left or right)
     */
    public UnlinkChannelsAction(ChannelLinkManager manager, UUID channelId) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.channelId = Objects.requireNonNull(channelId, "channelId must not be null");
    }

    @Override
    public String description() {
        return "Unlink Channels";
    }

    @Override
    public void execute() {
        ChannelLink removed = manager.unlink(channelId);
        if (removed != null) {
            removedLink = removed;
        }
    }

    @Override
    public void undo() {
        if (removedLink != null) {
            manager.link(removedLink);
        }
    }

    /** Returns the link removed by this action, or {@code null} if not yet executed. */
    public ChannelLink getRemovedLink() {
        return removedLink;
    }
}
