package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DawProjectTest {

    @Test
    void shouldCreateProjectWithDefaults() {
        DawProject project = new DawProject("My Song", AudioFormat.STUDIO_QUALITY);

        assertThat(project.getName()).isEqualTo("My Song");
        assertThat(project.getFormat()).isEqualTo(AudioFormat.STUDIO_QUALITY);
        assertThat(project.getTracks()).isEmpty();
        assertThat(project.getMixer()).isNotNull();
        assertThat(project.getTransport()).isNotNull();
    }

    @Test
    void shouldAddAndRemoveTracks() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Vocals", TrackType.AUDIO);

        project.addTrack(track);
        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getTracks().get(0).getName()).isEqualTo("Vocals");

        assertThat(project.removeTrack(track)).isTrue();
        assertThat(project.getTracks()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableTrackList() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> project.getTracks().add(new Track("Illegal", TrackType.AUDIO)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldSetProjectName() {
        DawProject project = new DawProject("Original", AudioFormat.CD_QUALITY);
        project.setName("Renamed");
        assertThat(project.getName()).isEqualTo("Renamed");
    }

    @Test
    void shouldRejectNullProjectName() {
        assertThatThrownBy(() -> new DawProject(null, AudioFormat.CD_QUALITY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAddMixerChannelWhenTrackIsAdded() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Bass", TrackType.AUDIO);

        project.addTrack(track);

        assertThat(project.getMixer().getChannelCount()).isEqualTo(1);
        assertThat(project.getMixer().getChannels().get(0).getName()).isEqualTo("Bass");
    }

    @Test
    void shouldRemoveMixerChannelWhenTrackIsRemoved() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Drums", TrackType.AUDIO);

        project.addTrack(track);
        assertThat(project.getMixer().getChannelCount()).isEqualTo(1);

        project.removeTrack(track);

        assertThat(project.getMixer().getChannelCount()).isZero();
    }

    @Test
    void shouldNotDuplicateMixerChannelOnUndoRedoCycle() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Guitar", TrackType.AUDIO);

        // Simulate add → undo (remove) → redo (add again)
        project.addTrack(track);
        assertThat(project.getMixer().getChannelCount()).isEqualTo(1);

        project.removeTrack(track);
        assertThat(project.getMixer().getChannelCount()).isZero();

        project.addTrack(track);
        assertThat(project.getMixer().getChannelCount()).isEqualTo(1);
    }

    @Test
    void shouldCreateAudioTrackWithMixerChannel() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        Track track = project.createAudioTrack("Lead Synth");

        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getMixer().getChannelCount()).isEqualTo(1);
        assertThat(project.getMixer().getChannels().get(0).getName()).isEqualTo("Lead Synth");
        assertThat(track.getName()).isEqualTo("Lead Synth");
    }

    @Test
    void shouldReturnMixerChannelForTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Vocals");

        assertThat(project.getMixerChannelForTrack(track)).isNotNull();
        assertThat(project.getMixerChannelForTrack(track).getName()).isEqualTo("Vocals");
    }

    @Test
    void shouldReturnNullForUnknownTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track unknownTrack = new Track("Unknown", TrackType.AUDIO);

        assertThat(project.getMixerChannelForTrack(unknownTrack)).isNull();
    }

    @Test
    void shouldRetainMixerChannelMappingAfterRemoval() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Bass");

        project.removeTrack(track);

        // Channel mapping retained for undo/redo reuse
        assertThat(project.getMixerChannelForTrack(track)).isNotNull();
        assertThat(project.getMixerChannelForTrack(track).getName()).isEqualTo("Bass");
    }

    @Test
    void shouldRejectNullTrackInGetMixerChannelForTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> project.getMixerChannelForTrack(null))
                .isInstanceOf(NullPointerException.class);
    }
}
