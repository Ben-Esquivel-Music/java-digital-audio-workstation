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
        var project = new DawProject("My Song", AudioFormat.STUDIO_QUALITY);

        assertThat(project.getName()).isEqualTo("My Song");
        assertThat(project.getFormat()).isEqualTo(AudioFormat.STUDIO_QUALITY);
        assertThat(project.getTracks()).isEmpty();
        assertThat(project.getMixer()).isNotNull();
        assertThat(project.getTransport()).isNotNull();
    }

    @Test
    void shouldAddAndRemoveTracks() {
        var project = new DawProject("Test", AudioFormat.CD_QUALITY);
        var track = new Track("Vocals", TrackType.AUDIO);

        project.addTrack(track);
        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getTracks().get(0).getName()).isEqualTo("Vocals");

        assertThat(project.removeTrack(track)).isTrue();
        assertThat(project.getTracks()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableTrackList() {
        var project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> project.getTracks().add(new Track("Illegal", TrackType.AUDIO)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldSetProjectName() {
        var project = new DawProject("Original", AudioFormat.CD_QUALITY);
        project.setName("Renamed");
        assertThat(project.getName()).isEqualTo("Renamed");
    }

    @Test
    void shouldRejectNullProjectName() {
        assertThatThrownBy(() -> new DawProject(null, AudioFormat.CD_QUALITY))
                .isInstanceOf(NullPointerException.class);
    }
}
