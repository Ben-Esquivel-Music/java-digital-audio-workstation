package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyInfoTest {

    @Test
    void shouldComputeRoundTripLatency() {
        LatencyInfo info = LatencyInfo.of(5.0, 7.0, 256, 44100.0);
        assertThat(info.inputLatencyMs()).isEqualTo(5.0);
        assertThat(info.outputLatencyMs()).isEqualTo(7.0);
        assertThat(info.roundTripLatencyMs()).isEqualTo(12.0);
        assertThat(info.bufferSizeFrames()).isEqualTo(256);
        assertThat(info.sampleRateHz()).isEqualTo(44100.0);
    }

    @Test
    void shouldRejectNegativeInputLatency() {
        assertThatThrownBy(() -> new LatencyInfo(-1.0, 5.0, 4.0, 256, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeOutputLatency() {
        assertThatThrownBy(() -> new LatencyInfo(5.0, -1.0, 4.0, 256, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeRoundTrip() {
        assertThatThrownBy(() -> new LatencyInfo(5.0, 5.0, -1.0, 256, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowZeroLatency() {
        LatencyInfo info = LatencyInfo.of(0.0, 0.0, 64, 96000.0);
        assertThat(info.roundTripLatencyMs()).isEqualTo(0.0);
    }
}
