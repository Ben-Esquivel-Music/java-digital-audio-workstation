package com.benesquivelmusic.daw.core.audioimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class AudioReadResultTest {

    @Test
    void shouldCreateValidResult() {
        float[][] audioData = new float[2][44100];
        AudioReadResult result = new AudioReadResult(audioData, 44100, 2, 16);

        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.bitDepth()).isEqualTo(16);
        assertThat(result.numFrames()).isEqualTo(44100);
        assertThat(result.durationSeconds()).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void shouldAllowZeroBitDepthForLossyFormats() {
        float[][] audioData = new float[2][1000];
        AudioReadResult result = new AudioReadResult(audioData, 44100, 2, 0);

        assertThat(result.bitDepth()).isEqualTo(0);
    }

    @Test
    void shouldRejectNullAudioData() {
        assertThatThrownBy(() -> new AudioReadResult(null, 44100, 2, 16))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[][] audioData = new float[2][100];
        assertThatThrownBy(() -> new AudioReadResult(audioData, 0, 2, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        float[][] audioData = new float[2][100];
        assertThatThrownBy(() -> new AudioReadResult(audioData, 44100, 0, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeBitDepth() {
        float[][] audioData = new float[2][100];
        assertThatThrownBy(() -> new AudioReadResult(audioData, 44100, 2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
