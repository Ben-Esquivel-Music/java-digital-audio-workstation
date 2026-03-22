package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioExportConfigTest {

    @Test
    void shouldCreateWithAllParameters() {
        AudioMetadata metadata = new AudioMetadata("Title", "Artist", "Album", "ISRC123");
        AudioExportConfig config = new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.TPDF, metadata, 0.9);

        assertThat(config.format()).isEqualTo(AudioExportFormat.WAV);
        assertThat(config.sampleRate()).isEqualTo(44100);
        assertThat(config.bitDepth()).isEqualTo(16);
        assertThat(config.ditherType()).isEqualTo(DitherType.TPDF);
        assertThat(config.metadata()).isEqualTo(metadata);
        assertThat(config.quality()).isEqualTo(0.9);
    }

    @Test
    void shouldCreateWithConvenienceConstructor() {
        AudioExportConfig config = new AudioExportConfig(AudioExportFormat.FLAC, 48000, 24, DitherType.NONE);

        assertThat(config.format()).isEqualTo(AudioExportFormat.FLAC);
        assertThat(config.sampleRate()).isEqualTo(48000);
        assertThat(config.bitDepth()).isEqualTo(24);
        assertThat(config.ditherType()).isEqualTo(DitherType.NONE);
        assertThat(config.metadata()).isEqualTo(AudioMetadata.EMPTY);
        assertThat(config.quality()).isEqualTo(0.8);
    }

    @Test
    void shouldRejectNullFormat() {
        assertThatThrownBy(() -> new AudioExportConfig(null, 44100, 16, DitherType.NONE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new AudioExportConfig(AudioExportFormat.WAV, 0, 16, DitherType.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBitDepth() {
        assertThatThrownBy(() -> new AudioExportConfig(AudioExportFormat.WAV, 44100, 0, DitherType.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidQuality() {
        assertThatThrownBy(() -> new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 16, DitherType.NONE, AudioMetadata.EMPTY, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
