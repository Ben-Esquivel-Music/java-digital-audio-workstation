package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StemExportConfigTest {

    private static final AudioExportConfig AUDIO_CONFIG =
            new AudioExportConfig(AudioExportFormat.WAV, 44100, 24, DitherType.NONE);

    @Test
    void shouldCreateWithAllParameters() {
        List<Integer> indices = List.of(0, 2, 4);
        StemExportConfig config = new StemExportConfig(
                indices, AUDIO_CONFIG, StemNamingConvention.TRACK_NAME, "MyProject");

        assertThat(config.trackIndices()).isEqualTo(List.of(0, 2, 4));
        assertThat(config.audioExportConfig()).isEqualTo(AUDIO_CONFIG);
        assertThat(config.namingConvention()).isEqualTo(StemNamingConvention.TRACK_NAME);
        assertThat(config.projectName()).isEqualTo("MyProject");
    }

    @Test
    void shouldDefensivelyCopyTrackIndices() {
        List<Integer> indices = new java.util.ArrayList<>(List.of(0, 1));
        StemExportConfig config = new StemExportConfig(
                indices, AUDIO_CONFIG, StemNamingConvention.TRACK_NAME, "Project");

        indices.add(5);
        assertThat(config.trackIndices()).hasSize(2);
    }

    @Test
    void shouldReturnUnmodifiableTrackIndices() {
        StemExportConfig config = new StemExportConfig(
                List.of(0, 1), AUDIO_CONFIG, StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> config.trackIndices().add(5))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullTrackIndices() {
        assertThatThrownBy(() -> new StemExportConfig(
                null, AUDIO_CONFIG, StemNamingConvention.TRACK_NAME, "Project"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullAudioExportConfig() {
        assertThatThrownBy(() -> new StemExportConfig(
                List.of(0), null, StemNamingConvention.TRACK_NAME, "Project"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNamingConvention() {
        assertThatThrownBy(() -> new StemExportConfig(
                List.of(0), AUDIO_CONFIG, null, "Project"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullProjectName() {
        assertThatThrownBy(() -> new StemExportConfig(
                List.of(0), AUDIO_CONFIG, StemNamingConvention.TRACK_NAME, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportEmptyTrackIndicesList() {
        StemExportConfig config = new StemExportConfig(
                List.of(), AUDIO_CONFIG, StemNamingConvention.NUMBERED, "Project");

        assertThat(config.trackIndices()).isEmpty();
    }
}
