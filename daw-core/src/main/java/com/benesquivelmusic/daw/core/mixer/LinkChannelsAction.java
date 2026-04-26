package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that links two mixer channels into a stereo pair.
 *
 * <p>Executing the action calls {@link ChannelLinkManager#link(ChannelLink)};
 * undo removes the link via {@link ChannelLinkManager#unlink(java.util.UUID)}.
 * Per the issue, unlinking does <em>not</em> reset the channels' values —
 * both members retain their current volume, pan, mute, and solo state.</p>
 */
public final class LinkChannelsAction implements UndoableAction {

    private final ChannelLinkManager manager;
    private final ChannelLink link;

    /**
     * Creates the action.
     *
     * @param manager the manager that owns the link set
     * @param link    the link to register
     */
    public LinkChannelsAction(ChannelLinkManager manager, ChannelLink link) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.link = Objects.requireNonNull(link, "link must not be null");
    }

    @Override
    public String description() {
        return "Link Channels";
    }

    @Override
    public void execute() {
        manager.link(link);
    }

    @Override
    public void undo() {
        manager.unlink(link.leftChannelId());
    }

    /** Returns the link this action registers. */
    public ChannelLink getLink() {
        return link;
    }
}
