package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendTap;
import com.benesquivelmusic.daw.core.mixer.snapshot.SendSnapshot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 2 writes no derived duplicate a reader would never honour: the
 * master and the track channels carry no {@code name} (the master is always
 * "Master"; a track channel is rebuilt from its track), a return bus does
 * (a bus beyond the default is recreated with it), and a mixer send carries
 * its {@code tap} but not the lossy legacy {@code mode}, which is still read
 * — only when {@code tap} is absent — from older files.
 */
class DerivedMixerAttributesPersistenceTest {

    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void masterAndChannelsCarryNoNameAndReturnBusesDo() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocals");
        project.getMixer().addReturnBus("Delay Return");

        Element mixer = mixerOf(new ProjectSerializer().serialize(project));

        assertThat(single(mixer, "master").hasAttribute("name")).isFalse();
        assertThat(children(single(mixer, "channels"), "channel"))
                .isNotEmpty().noneMatch(channel -> channel.hasAttribute("name"));
        assertThat(children(single(mixer, "return-buses"), "return-bus"))
                .extracting(bus -> bus.getAttribute("name"))
                .containsExactly("Reverb Return", "Delay Return");
    }

    @Test
    void mixerSendsCarryTapButNotMode() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track vocals = project.createAudioTrack("Vocals");
        MixerChannel reverb = project.getMixer().getReturnBuses().getFirst();
        project.getMixerChannelForTrack(vocals).addSend(new Send(reverb, 0.4, SendTap.PRE_INSERTS));

        Element mixer = mixerOf(new ProjectSerializer().serialize(project));

        List<Element> sends = children(single(single(single(mixer, "channels"), "channel"), "sends"), "send");
        assertThat(sends).singleElement().satisfies(send -> {
            assertThat(send.getAttribute("tap")).isEqualTo("PRE_INSERTS");
            assertThat(send.hasAttribute("mode")).isFalse();
        });
        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));
        assertThat(reopened.getMixer().getChannels().getFirst().getSends().getFirst().getTap())
                .isEqualTo(SendTap.PRE_INSERTS);
    }

    @Test
    void aLegacySendWithOnlyModeDerivesItsTapAndTapWinsOverMode() throws Exception {
        String xml = """
                <daw-project version="1">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Vocals" type="AUDIO"/>
                    <track id="00000000-0000-0000-0000-00000000000b" name="Guitar" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master name="Master" volume="1.0"/>
                    <channels>
                      <channel name="Vocals"><sends><send level="0.5" mode="PRE_FADER" target-index="0"/></sends></channel>
                      <channel name="Guitar"><sends><send level="0.5" mode="PRE_FADER" tap="POST_FADER" target-index="0"/></sends></channel>
                    </channels>
                    <return-buses><return-bus name="Reverb Return"/></return-buses>
                  </mixer>
                  <mixer-snapshots active-slot="A">
                    <snapshot name="Verse" timestamp="2026-01-01T00:00:00Z">
                      <master volume="1.0"/>
                      <channels>
                        <channel volume="1.0"><sends><send level="0.3" mode="PRE_FADER" target-index="0"/></sends></channel>
                        <channel volume="1.0"><sends><send level="0.3" mode="PRE_FADER" tap="PRE_INSERTS" target-index="0"/></sends></channel>
                      </channels>
                      <return-buses/>
                    </snapshot>
                  </mixer-snapshots>
                </daw-project>
                """;

        DawProject project = deserializer.deserialize(xml);

        List<MixerChannel> channels = project.getMixer().getChannels();
        assertThat(channels.get(0).getSends().getFirst().getTap())
                .as("only mode: the tap is derived from it").isEqualTo(SendTap.PRE_FADER);
        assertThat(channels.get(1).getSends().getFirst().getTap())
                .as("both: tap wins").isEqualTo(SendTap.POST_FADER);
        List<SendSnapshot> snapshotSends = new ArrayList<>();
        project.getMixerSnapshotManager().getSnapshots().getFirst().channels()
                .forEach(channel -> snapshotSends.addAll(channel.sends()));
        assertThat(snapshotSends).extracting(SendSnapshot::tap)
                .containsExactly(SendTap.PRE_FADER, SendTap.PRE_INSERTS);
    }

    @Test
    void renamedTrackReopensWithItsChannelNamedLikeIt() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Gtr 1");
        track.setName("Lead Guitar");

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        Track restored = reopened.getTracks().getFirst();
        assertThat(restored.getName()).isEqualTo("Lead Guitar");
        assertThat(reopened.getMixerChannelForTrack(restored).getName()).isEqualTo("Lead Guitar");
    }

    private static Element mixerOf(String xml) throws Exception {
        Document document = ProjectDeserializer.parseXml(xml);
        return single(document.getDocumentElement(), "mixer");
    }

    private static Element single(Element parent, String tagName) {
        List<Element> found = children(parent, tagName);
        assertThat(found).as("<" + tagName + "> under <" + parent.getTagName() + ">").hasSize(1);
        return found.getFirst();
    }

    private static List<Element> children(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && child.getTagName().equals(tagName)) {
                result.add(child);
            }
        }
        return result;
    }
}
