package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * A reopened track keeps the id it was saved with, so its mixer channel
 * (channel id == track id) and its MIDI clip follow, and every id-keyed
 * reference to it still resolves. A missing, malformed or duplicate id loads
 * with a fresh id instead — the first track keeps a duplicated id — and every
 * other creation path keeps minting fresh ids.
 */
class TrackIdentityPersistenceTest {

    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void saveAndReopenKeepsEveryTrackId() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocals");
        project.createMidiTrack("Keys");
        project.createAudioTrack("Bass");
        List<String> savedIds = project.getTracks().stream().map(Track::getId).toList();

        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        assertThat(reopened.getTracks()).extracting(Track::getId).containsExactlyElementsOf(savedIds);
        for (Track track : reopened.getTracks()) {
            UUID id = UUID.fromString(track.getId());
            assertThat(reopened.getMixerChannelForTrack(track).getId())
                    .as("channel id == track id for '" + track.getName() + "'").isEqualTo(id);
            assertThat(track.getMidiClip().getOwningTrackId())
                    .as("MIDI clip owned by '" + track.getName() + "'").isEqualTo(id);
        }
    }

    @Test
    void aDuplicatedIdIsKeptByTheFirstTrackAndEveryTrackGetsItsOwnChannel() throws Exception {
        String shared = "00000000-0000-0000-0000-00000000000a";
        String xml = """
                <daw-project version="%d">
                  <tracks>
                    <track id="%2$s" name="First" type="AUDIO"/>
                    <track id="00000000-0000-0000-0000-00000000000b" name="Second" type="AUDIO"/>
                    <track id="%2$s" name="Third" type="AUDIO"/>
                    <track id="00000000-0000-0000-0000-00000000000c" name="Fourth" type="AUDIO"/>
                  </tracks>
                  <mixer>
                    <master volume="1.0"/>
                    <channels>
                      <channel volume="0.1"/>
                      <channel volume="0.2"/>
                      <channel volume="0.3"/>
                      <channel volume="0.4"/>
                    </channels>
                    <return-buses/>
                  </mixer>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, shared);

        DawProject project = deserializer.deserialize(xml);

        List<Track> tracks = project.getTracks();
        assertThat(tracks).hasSize(4);
        assertThat(tracks.get(0).getId()).as("the first track keeps the duplicated id").isEqualTo(shared);
        assertThat(tracks.get(2).getId()).as("the later track gets a fresh id").isNotEqualTo(shared);
        assertThat(tracks).extracting(Track::getId).doesNotHaveDuplicates();
        assertThat(project.getMixer().getChannels()).hasSize(4);
        double[] savedVolumes = {0.1, 0.2, 0.3, 0.4};
        for (int i = 0; i < tracks.size(); i++) {
            MixerChannel channel = project.getMixerChannelForTrack(tracks.get(i));
            assertThat(channel.getVolume()).as("channel " + i + " volume").isEqualTo(savedVolumes[i]);
            assertThat(channel.getId()).isEqualTo(UUID.fromString(tracks.get(i).getId()));
        }
        assertThat(deserializer.getUnreadItems()).as("track-id problems are log-only").isEmpty();
    }

    @Test
    void missingMalformedAndNonCanonicalIdsLoadWithoutThrowing() throws Exception {
        String xml = """
                <daw-project version="%d">
                  <tracks>
                    <track name="Missing" type="AUDIO"/>
                    <track id="not-a-uuid" name="Malformed" type="AUDIO"/>
                    <track id="1-2-3-4-5" name="Short" type="AUDIO"/>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION);

        DawProject project = deserializer.deserialize(xml);

        List<Track> tracks = project.getTracks();
        assertThat(tracks).hasSize(3);
        assertThat(tracks.get(2).getId())
                .as("a non-canonical UUID is stored canonically")
                .isEqualTo("00000001-0002-0003-0004-000000000005");
        for (Track track : tracks) {
            UUID id = UUID.fromString(track.getId());
            assertThat(track.getId()).isEqualTo(id.toString());
            assertThat(project.getMixerChannelForTrack(track).getId())
                    .as("channel id == track id for '" + track.getName() + "'").isEqualTo(id);
            assertThat(track.getMidiClip().getOwningTrackId()).isEqualTo(id);
        }
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void duplicatingAReopenedTrackMintsADifferentId() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocals");
        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));
        Track source = reopened.getTracks().getFirst();

        Track copy = reopened.duplicateTrack(source);

        assertThat(copy.getId()).isNotEqualTo(source.getId());
        assertThat(source.duplicate("Copy").getId()).isNotEqualTo(source.getId());
    }

    @Test
    void theIdRestoringConstructorStoresTheCanonicalIdAndOwnsTheMidiClip() {
        UUID id = UUID.fromString("1-2-3-4-5");

        Track track = new Track(id, "Restored", TrackType.MIDI);

        assertThat(track.getId()).isEqualTo("00000001-0002-0003-0004-000000000005");
        assertThat(track.getMidiClip().getOwningTrackId()).isEqualTo(id);
        assertThat(new Track("Fresh", TrackType.AUDIO).getId())
                .isNotEqualTo(new Track("Fresh", TrackType.AUDIO).getId());
        assertThatNullPointerException().isThrownBy(() -> new Track(null, "x", TrackType.AUDIO));
    }
}
