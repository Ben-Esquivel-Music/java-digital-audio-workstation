package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueSend;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.spatial.BedChannelRouting;
import com.benesquivelmusic.daw.core.mixer.spatial.BedRoutingPresets;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.project.RemoveTrackAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The id-keyed mixer references — cue sends, VCA members, channel links, bed
 * routings — name tracks by id (a track's channel id equals its track id).
 * Because a reopened track keeps its saved id, every reference to a track of
 * the file resolves after reopen and the mix sounds the same. A reference to
 * a track the file does not contain, a malformed one, a channel linked to
 * itself and a repeat of an earlier assignment are removed, their container
 * kept, and reported; and the writer never saves a reference to a track it does not
 * write (a deleted track's references stay in the live model for undo).
 */
class MixerTrackReferencePersistenceTest {

    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void cueSendsResolveToTheReopenedTracksAndRenderTheSameMix() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track vocals = project.createAudioTrack("Vocals");
        Track guitar = project.createAudioTrack("Guitar");
        CueBus bus = project.getCueBusManager().createCueBus("Singer", 1);
        project.getCueBusManager().replace(bus
                .withSend(new CueSend(id(vocals), 0.9, -0.5, true))
                .withSend(new CueSend(id(guitar), 0.3, 0.75, false))
                .withMasterGain(0.8));
        float[][] before = renderCue(project, bus.id());

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        CueBus restored = reopened.getCueBusManager().getById(bus.id());
        for (Track track : reopened.getTracks()) {
            Track original = track.getName().equals("Vocals") ? vocals : guitar;
            assertThat(restored.findSend(id(track)))
                    .as("the send to '" + track.getName() + "' resolves to the reopened track")
                    .isEqualTo(project.getCueBusManager().getById(bus.id()).findSend(id(original)));
        }
        float[][] after = renderCue(reopened, bus.id());
        assertThat(after[0]).containsExactly(before[0]);
        assertThat(after[1]).containsExactly(before[1]);
        assertThat(after[0][0]).as("the cue mix is not silent").isNotZero();
    }

    @Test
    void vcaMembersKeepTheirCompositeVolumeAfterReopen() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        Track bass = project.createAudioTrack("Bass");
        project.getMixerChannelForTrack(kick).setVolume(0.8);
        project.getMixerChannelForTrack(snare).setVolume(0.5);
        project.getMixerChannelForTrack(bass).setVolume(0.7);
        VcaGroup drums = project.getVcaGroupManager().createVcaGroup("Drums", List.of(id(kick), id(snare)));
        project.getVcaGroupManager().setMasterGainDb(drums.id(), -6.0);
        Map<String, Double> before = compositeVolumes(project);

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        Map<String, Double> after = compositeVolumes(reopened);
        double minusSixDb = Math.pow(10.0, -6.0 / 20.0);
        assertThat(after).isEqualTo(before);
        assertThat(after.get("Kick")).isCloseTo(0.8 * minusSixDb, within(1e-12));
        assertThat(after.get("Snare")).isCloseTo(0.5 * minusSixDb, within(1e-12));
        assertThat(after.get("Bass")).as("a non-member is unaffected").isEqualTo(0.7);
    }

    @Test
    void channelLinkWithNonDefaultModeAndFlagsResolvesAfterReopen() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track left = project.createAudioTrack("Overhead L");
        Track right = project.createAudioTrack("Overhead R");
        ChannelLink link = new ChannelLink(id(left), id(right), LinkMode.ABSOLUTE, true, false, true, false);
        project.getChannelLinkManager().link(link);

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        MixerChannel reopenedLeft = reopened.getMixerChannelForTrack(reopened.getTracks().get(0));
        MixerChannel reopenedRight = reopened.getMixerChannelForTrack(reopened.getTracks().get(1));
        assertThat(reopened.getChannelLinkManager().getLink(reopenedLeft.getId())).isEqualTo(link);
        assertThat(reopened.getChannelLinkManager().partnerOf(reopenedLeft.getId()))
                .isEqualTo(reopenedRight.getId());
    }

    @Test
    void referencesToTracksTheFileDoesNotHoldAreRemovedReportedAndNeverSavedAgain() throws Exception {
        String a = "00000000-0000-0000-0000-00000000000a";
        String b = "00000000-0000-0000-0000-00000000000b";
        String absent = "00000000-0000-0000-0000-0000000000ff";
        String xml = """
                <daw-project version="%d">
                  <tracks>
                    <track id="%2$s" name="A" type="AUDIO"/>
                    <track id="%3$s" name="B" type="AUDIO"/>
                    <track id="t3-malformed" name="C" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master volume="1.0"/>
                    <channels/>
                    <return-buses/>
                    <cue-buses>
                      <cue-bus id="00000000-0000-0000-0000-0000000000c1" label="Singer" hardware-output-index="1" master-gain="1.0">
                        <cue-send track-id="%2$s" gain="0.5" pan="0.0" pre-fader="true"/>
                        <cue-send track-id="%4$s" gain="0.5" pan="0.0" pre-fader="true"/>
                        <cue-send track-id="nope" gain="0.5" pan="0.0" pre-fader="true"/>
                        <cue-send track-id="%2$s" gain="0.9" pan="0.0" pre-fader="false"/>
                        <cue-send track-id="t3-malformed" gain="0.5" pan="0.0" pre-fader="true"/>
                      </cue-bus>
                    </cue-buses>
                    <vca-groups>
                      <vca-group id="00000000-0000-0000-0000-0000000000d1" label="Drums" master-gain-db="-6.0">
                        <vca-member channel-id="%2$s"/>
                        <vca-member channel-id="%4$s"/>
                        <vca-member channel-id="%3$s"/>
                        <vca-member channel-id="%3$s"/>
                      </vca-group>
                    </vca-groups>
                    <channel-links>
                      <channel-link left-channel-id="%2$s" right-channel-id="%4$s" mode="RELATIVE"/>
                      <channel-link left-channel-id="%2$s" right-channel-id="%3$s" mode="ABSOLUTE"/>
                      <channel-link left-channel-id="%3$s" right-channel-id="%2$s" mode="RELATIVE"/>
                      <channel-link left-channel-id="nope" right-channel-id="%2$s" mode="RELATIVE"/>
                    </channel-links>
                  </mixer>
                  <bed-bus id="00000000-0000-0000-0000-0000000000e1" format="FORMAT_5_1">
                    <bed-routings>
                      <bed-routing track-id="%2$s" format="FORMAT_5_1"/>
                      <bed-routing track-id="%4$s" format="FORMAT_5_1"/>
                      <bed-routing track-id="%2$s" format="FORMAT_5_1"/>
                    </bed-routings>
                  </bed-bus>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, a, b, absent);

        DawProject project = deserializer.deserialize(xml);

        UUID idA = UUID.fromString(a);
        UUID idB = UUID.fromString(b);
        CueBus singer = project.getCueBusManager().getCueBuses().getFirst();
        assertThat(singer.label()).isEqualTo("Singer");
        assertThat(singer.sends()).as("the first send to A wins")
                .containsExactly(new CueSend(idA, 0.5, 0.0, true));
        VcaGroup drums = project.getVcaGroupManager().getVcaGroups().getFirst();
        assertThat(drums.label()).isEqualTo("Drums");
        assertThat(drums.masterGainDb()).isEqualTo(-6.0);
        assertThat(drums.memberChannelIds()).containsExactly(idA, idB);
        assertThat(project.getChannelLinkManager().getLinks())
                .containsExactly(new ChannelLink(idA, idB, LinkMode.ABSOLUTE, true, true, true, true));
        assertThat(project.getBedBusManager().getFormat()).isEqualTo(ImmersiveFormat.FORMAT_5_1);
        assertThat(project.getBedBusManager().getRoutings()).containsOnlyKeys(idA);

        List<UnreadProjectItem> unread = deserializer.getUnreadItems();
        assertThat(unread).allMatch(item -> item.kind() == UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED);
        assertThat(unread).extracting(item -> ((UnreadProjectItem.InMixer) item.location()).container(),
                        UnreadProjectItem::index)
                .containsExactly(
                        Tuple.tuple("cue bus 'Singer'", 1),
                        Tuple.tuple("cue bus 'Singer'", 2),
                        Tuple.tuple("cue bus 'Singer'", 3),
                        Tuple.tuple("cue bus 'Singer'", 4),
                        Tuple.tuple("VCA group 'Drums'", 1),
                        Tuple.tuple("VCA group 'Drums'", 3),
                        Tuple.tuple("channel links", 0),
                        Tuple.tuple("channel links", 2),
                        Tuple.tuple("channel links", 3),
                        Tuple.tuple("bed bus", 1),
                        Tuple.tuple("bed bus", 2));
        assertThat(unread.get(0).detail()).isEqualTo("cue-send@track-id " + absent + " names no track in this file");
        assertThat(unread.get(1).detail()).isEqualTo("cue-send@track-id 'nope' is not a track id");
        assertThat(unread.get(2).detail()).contains("repeats an earlier assignment");

        String resaved = new ProjectSerializer().serialize(project);
        assertThat(resaved).doesNotContain(absent).doesNotContain("nope").doesNotContain("t3-malformed");
        deserializer.deserialize(resaved);
        assertThat(deserializer.getUnreadItems()).as("a second save emits none of them").isEmpty();
    }

    /**
     * Every referenced track is present: what is removed is a self-link and
     * the repeats of an earlier assignment (studio-engineer ruling Q4). Each
     * is one removed mixer assignment naming its reason and its track; the
     * first assignment of a track wins.
     */
    @Test
    void selfLinksAndRepeatsAreRemovedWhileEveryTrackIsPresentAndTheFirstAssignmentWins() throws Exception {
        String a = "00000000-0000-0000-0000-00000000000a";
        String b = "00000000-0000-0000-0000-00000000000b";
        String c = "00000000-0000-0000-0000-00000000000c";
        String xml = """
                <daw-project version="%d">
                  <tracks>
                    <track id="%2$s" name="A" type="AUDIO"/>
                    <track id="%3$s" name="B" type="AUDIO"/>
                    <track id="%4$s" name="C" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master volume="1.0"/>
                    <channels/>
                    <return-buses/>
                    <cue-buses>
                      <cue-bus id="00000000-0000-0000-0000-0000000000c1" label="Singer" hardware-output-index="1" master-gain="1.0">
                        <cue-send track-id="%2$s" gain="0.5" pan="0.0" pre-fader="true"/>
                        <cue-send track-id="%2$s" gain="0.9" pan="0.25" pre-fader="false"/>
                      </cue-bus>
                    </cue-buses>
                    <vca-groups>
                      <vca-group id="00000000-0000-0000-0000-0000000000d1" label="Drums" master-gain-db="-6.0">
                        <vca-member channel-id="%3$s"/>
                        <vca-member channel-id="%3$s"/>
                      </vca-group>
                    </vca-groups>
                    <channel-links>
                      <channel-link left-channel-id="%2$s" right-channel-id="%2$s" mode="RELATIVE"/>
                      <channel-link left-channel-id="%2$s" right-channel-id="%3$s" mode="ABSOLUTE"/>
                      <channel-link left-channel-id="%3$s" right-channel-id="%4$s" mode="RELATIVE"/>
                    </channel-links>
                  </mixer>
                  <bed-bus id="00000000-0000-0000-0000-0000000000e1" format="FORMAT_5_1">
                    <bed-routings>
                      <bed-routing track-id="%4$s" format="FORMAT_5_1"/>
                      <bed-routing track-id="%4$s" format="FORMAT_5_1"/>
                    </bed-routings>
                  </bed-bus>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, a, b, c);

        DawProject project = deserializer.deserialize(xml);

        List<UnreadProjectItem> unread = deserializer.getUnreadItems();
        assertThat(unread).hasSize(5)
                .allMatch(item -> item.kind() == UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED);
        assertThat(unread).extracting(item -> ((UnreadProjectItem.InMixer) item.location()).container(),
                        UnreadProjectItem::index, UnreadProjectItem::detail)
                .containsExactly(
                        Tuple.tuple("cue bus 'Singer'", 1, "cue-send@track-id " + a
                                + " (Track 1 'A') repeats an earlier assignment of the same track"),
                        Tuple.tuple("VCA group 'Drums'", 1, "vca-member@channel-id " + b
                                + " (Track 2 'B') repeats an earlier assignment of the same track"),
                        Tuple.tuple("channel links", 0, "channel-link links Track 1 'A' (" + a + ") to itself"),
                        Tuple.tuple("channel links", 2, "channel-link@left-channel-id " + b
                                + " (Track 2 'B') is already in an earlier channel link"),
                        Tuple.tuple("bed bus", 1, "bed-routing@track-id " + c
                                + " (Track 3 'C') repeats an earlier assignment of the same track"));
        assertThat(unread.get(2).logLine()).isEqualTo("Mixer channel links: mixer assignment 1 removed"
                + " — channel-link links Track 1 'A' (" + a + ") to itself");

        UUID idA = UUID.fromString(a);
        UUID idB = UUID.fromString(b);
        UUID idC = UUID.fromString(c);
        assertThat(project.getCueBusManager().getCueBuses().getFirst().sends()).as("A's first cue send wins")
                .containsExactly(new CueSend(idA, 0.5, 0.0, true));
        assertThat(project.getVcaGroupManager().getVcaGroups().getFirst().memberChannelIds()).containsExactly(idB);
        assertThat(project.getChannelLinkManager().getLinks()).as("the link (A, B) is kept")
                .containsExactly(new ChannelLink(idA, idB, LinkMode.ABSOLUTE, true, true, true, true));
        assertThat(project.getChannelLinkManager().getLink(idC)).as("C is not linked").isNull();
        assertThat(project.getBedBusManager().getRoutings()).containsOnlyKeys(idC);
    }

    @Test
    void aDeletedTracksReferencesAreNotSavedAndComeBackWithUndo() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        CueBus bus = project.getCueBusManager().createCueBus("Drummer", 1);
        project.getCueBusManager().replace(bus
                .withSend(new CueSend(id(kick), 0.6, 0.0, true))
                .withSend(new CueSend(id(snare), 0.4, 0.0, true)));
        project.getVcaGroupManager().createVcaGroup("Drums", List.of(id(kick), id(snare)));
        project.getChannelLinkManager().link(ChannelLink.ofPair(id(kick), id(snare)));
        project.getBedBusManager().setRouting(
                BedRoutingPresets.lcr(id(kick), project.getBedBusManager().getFormat()));
        RemoveTrackAction removeKick = new RemoveTrackAction(project, kick);

        removeKick.execute();
        String withoutKick = new ProjectSerializer().serialize(project);

        assertThat(withoutKick).as("no reference to the deleted track is written")
                .doesNotContain(kick.getId());
        assertThat(project.getChannelLinkManager().getLink(id(kick)))
                .as("the live model keeps the references for undo").isNotNull();
        deserializer.deserialize(withoutKick);
        assertThat(deserializer.getUnreadItems()).as("no false damage on reopen").isEmpty();

        removeKick.undo();
        String withKick = new ProjectSerializer().serialize(project);
        DawProject reopened = deserializer.deserialize(withKick);

        assertThat(withKick)
                .contains("track-id=\"" + kick.getId() + "\"")
                .contains("vca-member channel-id=\"" + kick.getId() + "\"")
                .contains("left-channel-id=\"" + kick.getId() + "\"")
                .contains("bed-routing channel-gains-db");
        assertThat(reopened.getCueBusManager().getById(bus.id()).findSend(id(kick))).isNotNull();
        assertThat(reopened.getVcaGroupManager().getVcaGroups().getFirst().memberChannelIds())
                .containsExactlyInAnyOrder(id(kick), id(snare));
        assertThat(reopened.getChannelLinkManager().partnerOf(id(kick))).isEqualTo(id(snare));
        assertThat(reopened.getBedBusManager().getRouting(id(kick))).isPresent();
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void bedRoutingGainsRoundTripForEveryRoutedTrack() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track choir = project.createAudioTrack("Choir");
        Track strings = project.createAudioTrack("Strings");
        ImmersiveFormat format = project.getBedBusManager().getFormat();
        BedChannelRouting choirRouting = BedChannelRouting.silent(id(choir), format)
                .withChannelGain(0, -3.0).withChannelGain(2, -1.5);
        BedChannelRouting stringsRouting = BedChannelRouting.silent(id(strings), format)
                .withChannelGain(1, -6.0);
        project.getBedBusManager().setRouting(choirRouting);
        project.getBedBusManager().setRouting(stringsRouting);

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        assertThat(reopened.getBedBusManager().getRouting(id(reopened.getTracks().get(0))).orElseThrow()
                .channelGainsDb()).containsExactly(choirRouting.channelGainsDb());
        assertThat(reopened.getBedBusManager().getRouting(id(reopened.getTracks().get(1))).orElseThrow()
                .channelGainsDb()).containsExactly(stringsRouting.channelGainsDb());
    }

    private static UUID id(Track track) {
        return UUID.fromString(track.getId());
    }

    /** Each track's composite render gain: its channel fader times its VCA multiplier (as {@code Mixer} renders it). */
    private static Map<String, Double> compositeVolumes(DawProject project) {
        Map<String, Double> volumes = new java.util.LinkedHashMap<>();
        for (Track track : project.getTracks()) {
            MixerChannel channel = project.getMixerChannelForTrack(track);
            volumes.put(track.getName(), channel.getVolume()
                    * project.getVcaGroupManager().effectiveLinearMultiplier(channel.getId()));
        }
        return volumes;
    }

    /** Renders the cue bus over a constant per-track test signal keyed by the project's own track ids. */
    private static float[][] renderCue(DawProject project, UUID busId) {
        int frames = 4;
        Map<UUID, float[][]> preFader = new java.util.HashMap<>();
        Map<UUID, float[][]> postFader = new java.util.HashMap<>();
        float level = 0.25f;
        for (Track track : project.getTracks()) {
            float[][] signal = {new float[frames], new float[frames]};
            java.util.Arrays.fill(signal[0], level);
            java.util.Arrays.fill(signal[1], level);
            preFader.put(id(track), signal);
            postFader.put(id(track), signal);
            level += 0.25f;
        }
        float[][] out = new float[2][frames];
        project.getCueBusManager().renderCueBus(project.getCueBusManager().getById(busId),
                preFader, postFader, out, frames);
        return out;
    }
}
