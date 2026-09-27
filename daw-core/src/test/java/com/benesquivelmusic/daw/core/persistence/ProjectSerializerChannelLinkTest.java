package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.ChannelLinkManager;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectSerializer} writes the &lt;channel-links&gt;
 * section and that {@link ProjectDeserializer} reads it back, satisfying
 * the issue's "persist {@code ChannelLink} relationships via
 * {@code ProjectSerializer}" goal. The section is always emitted (even
 * when empty) so future readers can rely on its presence; legacy projects
 * without the element load with no links because the manager starts empty.
 */
class ProjectSerializerChannelLinkTest {

    @Test
    void shouldSerializeChannelLinksAndAttributes() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        project.getChannelLinkManager().link(new ChannelLink(left, right,
                LinkMode.ABSOLUTE, true, false, true, true));

        String xml = new ProjectSerializer().serialize(project);

        assertThat(xml).contains("<channel-links>");
        assertThat(xml).contains("left-channel-id=\"" + left + "\"");
        assertThat(xml).contains("right-channel-id=\"" + right + "\"");
        assertThat(xml).contains("mode=\"ABSOLUTE\"");
        assertThat(xml).contains("link-faders=\"true\"");
        assertThat(xml).contains("link-pans=\"false\"");
        assertThat(xml).contains("link-mute-solo=\"true\"");
        assertThat(xml)
                .as("story 322 removed the 'link inserts' flag; it must no longer be written")
                .doesNotContain("link-inserts");
        assertThat(xml).contains("link-sends=\"true\"");
    }

    @Test
    void shouldTolerateLegacyLinkInsertsAttributeOnRead() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        project.getChannelLinkManager().link(new ChannelLink(left, right,
                LinkMode.ABSOLUTE, true, false, true, true));
        String xml = new ProjectSerializer().serialize(project)
                .replace("link-sends=\"true\"", "link-inserts=\"true\" link-sends=\"true\"");

        DawProject restored = new ProjectDeserializer().deserialize(xml);

        ChannelLink restoredLink = restored.getChannelLinkManager().getLink(left);
        assertThat(restoredLink).isNotNull();
        assertThat(restoredLink.linkSends()).isTrue();
        assertThat(restoredLink.linkPans()).isFalse();
    }

    @Test
    void shouldSerializeEmptyChannelLinksElementWhenNoLinks() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).contains("channel-links");
    }

    @Test
    void shouldRoundTripChannelLinks() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        project.getChannelLinkManager().link(new ChannelLink(left, right,
                LinkMode.RELATIVE, true, true, false, false));

        String xml = new ProjectSerializer().serialize(project);
        DawProject restored = new ProjectDeserializer().deserialize(xml);

        ChannelLinkManager restoredManager = restored.getChannelLinkManager();
        assertThat(restoredManager.getLinks()).hasSize(1);
        ChannelLink restoredLink = restoredManager.getLink(left);
        assertThat(restoredLink.leftChannelId()).isEqualTo(left);
        assertThat(restoredLink.rightChannelId()).isEqualTo(right);
        assertThat(restoredLink.mode()).isEqualTo(LinkMode.RELATIVE);
        assertThat(restoredLink.linkFaders()).isTrue();
        assertThat(restoredLink.linkPans()).isTrue();
        assertThat(restoredLink.linkMuteSolo()).isFalse();
        assertThat(restoredLink.linkSends()).isFalse();
    }
}
