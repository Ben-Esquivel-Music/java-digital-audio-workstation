package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LinkChannelsAction} and {@link UnlinkChannelsAction} —
 * the two undoable actions described in the issue.
 */
class ChannelLinkActionsTest {

    @Test
    void linkChannelsActionAddsAndUndoesLink() {
        ChannelLinkManager manager = new ChannelLinkManager();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        ChannelLink link = ChannelLink.ofPair(left, right);

        LinkChannelsAction action = new LinkChannelsAction(manager, link);
        action.execute();

        assertThat(manager.getLinks()).hasSize(1);
        assertThat(manager.partnerOf(left)).isEqualTo(right);

        action.undo();
        assertThat(manager.getLinks()).isEmpty();

        // Redo restores the same link record.
        action.execute();
        assertThat(manager.getLinks()).containsExactly(link);
    }

    @Test
    void unlinkChannelsActionRemovesAndRestoresLink() {
        ChannelLinkManager manager = new ChannelLinkManager();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        ChannelLink original = new ChannelLink(left, right,
                LinkMode.ABSOLUTE, true, false, true, false, true);
        manager.link(original);

        UnlinkChannelsAction action = new UnlinkChannelsAction(manager, left);
        action.execute();

        assertThat(manager.getLinks()).isEmpty();
        assertThat(action.getRemovedLink()).isEqualTo(original);

        // Undo restores the exact same link record (preserving mode + toggles).
        action.undo();
        assertThat(manager.getLinks()).containsExactly(original);
        assertThat(manager.getLink(left).mode()).isEqualTo(LinkMode.ABSOLUTE);
        assertThat(manager.getLink(left).linkPans()).isFalse();

        // Redo removes again.
        action.execute();
        assertThat(manager.getLinks()).isEmpty();
    }

    @Test
    void unlinkActionOnUnlinkedChannelIsNoOp() {
        ChannelLinkManager manager = new ChannelLinkManager();
        UnlinkChannelsAction action = new UnlinkChannelsAction(manager, UUID.randomUUID());
        action.execute();
        action.undo();
        // No exceptions; manager remains empty.
        assertThat(manager.getLinks()).isEmpty();
    }
}
