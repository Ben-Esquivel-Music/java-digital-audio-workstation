package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioFormatTest {

    @Test
    void shouldCreateFormatWithValidParameters() {
        AudioFormat format = new AudioFormat(48000.0, 2, 24, 256);

        assertThat(format.sampleRate()).isEqualTo(48000.0);
        assertThat(format.channels()).isEqualTo(2);
        assertThat(format.bitDepth()).isEqualTo(24);
        assertThat(format.bufferSize()).isEqualTo(256);
    }

    @Test
    void shouldProvideStandardPresets() {
        assertThat(AudioFormat.CD_QUALITY.sampleRate()).isEqualTo(44_100.0);
        assertThat(AudioFormat.CD_QUALITY.channels()).isEqualTo(2);
        assertThat(AudioFormat.CD_QUALITY.bitDepth()).isEqualTo(16);

        assertThat(AudioFormat.STUDIO_QUALITY.sampleRate()).isEqualTo(96_000.0);
        assertThat(AudioFormat.STUDIO_QUALITY.channels()).isEqualTo(2);
        assertThat(AudioFormat.STUDIO_QUALITY.bitDepth()).isEqualTo(24);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new AudioFormat(0, 2, 24, 256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioFormat(-44100, 2, 24, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        assertThatThrownBy(() -> new AudioFormat(44100, 0, 24, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveBitDepth() {
        assertThatThrownBy(() -> new AudioFormat(44100, 2, 0, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveBufferSize() {
        assertThatThrownBy(() -> new AudioFormat(44100, 2, 24, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        AudioFormat a = new AudioFormat(44100, 2, 16, 512);
        AudioFormat b = new AudioFormat(44100, 2, 16, 512);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
