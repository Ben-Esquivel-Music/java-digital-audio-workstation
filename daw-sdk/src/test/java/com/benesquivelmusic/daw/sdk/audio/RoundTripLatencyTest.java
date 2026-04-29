package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RoundTripLatency} — the driver-reported round-trip
 * latency record used by the recording pipeline to align recorded takes with
 * the cue the user heard.
 */
class RoundTripLatencyTest {

    @Test
    void totalFramesShouldBeSumOfAllThreeComponents() {
        // The issue's worked example: 64 input + 128 output + 16 safety = 208 frames.
        RoundTripLatency latency = new RoundTripLatency(64, 128, 16);
        assertThat(latency.totalFrames()).isEqualTo(208);
    }

    @Test
    void totalMillisShouldComputeFromTotalFramesAndSampleRate() {
        // 208 frames @ 48 kHz = 4.333... ms — matches the issue's "I/O 5.3 ms"
        // ballpark for typical buffer sizes.
        RoundTripLatency latency = new RoundTripLatency(64, 128, 16);
        assertThat(latency.totalMillis(48_000.0))
                .isEqualTo(208 * 1000.0 / 48_000.0);
    }

    @Test
    void totalMillisShouldRejectNonPositiveSampleRate() {
        RoundTripLatency latency = new RoundTripLatency(0, 0, 0);
        assertThatThrownBy(() -> latency.totalMillis(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRateHz");
        assertThatThrownBy(() -> latency.totalMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSentinelShouldHaveZeroLatency() {
        assertThat(RoundTripLatency.UNKNOWN.totalFrames()).isZero();
        assertThat(RoundTripLatency.UNKNOWN.totalMillis(48_000.0)).isZero();
    }

    @Test
    void shouldRejectNegativeComponents() {
        assertThatThrownBy(() -> new RoundTripLatency(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputFrames");
        assertThatThrownBy(() -> new RoundTripLatency(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputFrames");
        assertThatThrownBy(() -> new RoundTripLatency(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safetyOffsetFrames");
    }

    @Test
    void factoryShouldDefaultSafetyOffsetToZero() {
        // Convenience for backends with no safety-offset concept (WASAPI / JACK).
        RoundTripLatency latency = RoundTripLatency.of(64, 128);
        assertThat(latency.safetyOffsetFrames()).isZero();
        assertThat(latency.totalFrames()).isEqualTo(192);
    }

    @Test
    void mockAudioBackendShouldReportConfiguredLatency() {
        // The deliverable test from the issue: a MockAudioBackend reporting
        // RoundTripLatency(64, 128, 16) should expose totalFrames() == 208
        // through the AudioBackend.reportedLatency() contract.
        try (MockAudioBackend backend = new MockAudioBackend()) {
            assertThat(backend.reportedLatency())
                    .isEqualTo(RoundTripLatency.UNKNOWN);

            backend.setReportedLatency(new RoundTripLatency(64, 128, 16));

            assertThat(backend.reportedLatency().totalFrames()).isEqualTo(208);
            assertThat(backend.reportedLatency().inputFrames()).isEqualTo(64);
            assertThat(backend.reportedLatency().outputFrames()).isEqualTo(128);
            assertThat(backend.reportedLatency().safetyOffsetFrames()).isEqualTo(16);
        }
    }

    @Test
    void mockAudioBackendShouldRejectNullLatency() {
        try (MockAudioBackend backend = new MockAudioBackend()) {
            assertThatThrownBy(() -> backend.setReportedLatency(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
