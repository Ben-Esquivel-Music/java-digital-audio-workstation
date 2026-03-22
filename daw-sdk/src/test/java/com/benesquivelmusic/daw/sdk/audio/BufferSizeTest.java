package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BufferSizeTest {

    @Test
    void shouldReturnCorrectFrameCounts() {
        assertThat(BufferSize.SAMPLES_32.getFrames()).isEqualTo(32);
        assertThat(BufferSize.SAMPLES_64.getFrames()).isEqualTo(64);
        assertThat(BufferSize.SAMPLES_128.getFrames()).isEqualTo(128);
        assertThat(BufferSize.SAMPLES_256.getFrames()).isEqualTo(256);
        assertThat(BufferSize.SAMPLES_512.getFrames()).isEqualTo(512);
        assertThat(BufferSize.SAMPLES_1024.getFrames()).isEqualTo(1024);
        assertThat(BufferSize.SAMPLES_2048.getFrames()).isEqualTo(2048);
    }

    @Test
    void shouldCalculateLatencyMs() {
        // 512 samples at 44100 Hz = ~11.6 ms
        double latency = BufferSize.SAMPLES_512.latencyMs(44_100.0);
        assertThat(latency).isCloseTo(11.6, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void shouldCalculateLowLatencyMs() {
        // 32 samples at 44100 Hz = ~0.73 ms
        double latency = BufferSize.SAMPLES_32.latencyMs(44_100.0);
        assertThat(latency).isCloseTo(0.73, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> BufferSize.SAMPLES_256.latencyMs(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BufferSize.SAMPLES_256.latencyMs(-44100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldLookUpFromFrames() {
        assertThat(BufferSize.fromFrames(128)).isEqualTo(BufferSize.SAMPLES_128);
        assertThat(BufferSize.fromFrames(1024)).isEqualTo(BufferSize.SAMPLES_1024);
    }

    @Test
    void shouldRejectUnsupportedFrameCount() {
        assertThatThrownBy(() -> BufferSize.fromFrames(100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void shouldHaveSevenStandardSizes() {
        assertThat(BufferSize.values()).hasSize(7);
    }
}
