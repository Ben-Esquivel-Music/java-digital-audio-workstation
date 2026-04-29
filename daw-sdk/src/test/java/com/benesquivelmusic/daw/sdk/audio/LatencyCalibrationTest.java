package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.audio.LatencyCalibration.CalibrationResult;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LatencyCalibration} — the driver round-trip latency
 * calibration tool that plays an impulse, captures it, and measures the
 * actual round-trip vs the driver-reported value.
 */
class LatencyCalibrationTest {

    @Test
    void shouldDetectImpulseLeadingEdgeAtExpectedFrame() {
        // Driver reports 200 frames; the impulse appears at sample 208 in the
        // captured buffer — the user's interface actually has 8 frames of
        // extra latency the driver under-reports.
        float[] captured = new float[1024];
        captured[208] = 1.0f;

        CalibrationResult result = LatencyCalibration.measure(captured, 200);

        assertThat(result.impulseFound()).isTrue();
        assertThat(result.measuredFrames()).isEqualTo(208);
        assertThat(result.reportedFrames()).isEqualTo(200);
        assertThat(result.deltaFrames()).isEqualTo(8);
    }

    @Test
    void silentBufferShouldReturnInconclusiveResult() {
        float[] captured = new float[1024]; // all zero
        CalibrationResult result = LatencyCalibration.measure(captured, 200);

        assertThat(result.impulseFound()).isFalse();
        assertThat(result.measuredFrames()).isZero();
        assertThat(result.overrideFrames()).isEmpty();
    }

    @Test
    void shouldNotifyWhenDeltaExceedsThreshold() {
        // Driver reports 100 frames but real round-trip is 250.
        float[] captured = new float[512];
        captured[250] = 0.9f;
        CalibrationResult result = LatencyCalibration.measure(captured, 100);

        // Delta = 150 > 64 default threshold → notify.
        assertThat(result.shouldNotify(LatencyCalibration.DEFAULT_NOTIFICATION_THRESHOLD_FRAMES))
                .isTrue();
        assertThat(result.deltaFrames()).isEqualTo(150);
    }

    @Test
    void shouldNotNotifyWhenDeltaIsWithinThreshold() {
        // Driver reports 200 frames and real round-trip is 210 — within
        // the 64-frame tolerance, so no notification.
        float[] captured = new float[512];
        captured[210] = 1.0f;
        CalibrationResult result = LatencyCalibration.measure(captured, 200);

        assertThat(result.deltaFrames()).isEqualTo(10);
        assertThat(result.shouldNotify(LatencyCalibration.DEFAULT_NOTIFICATION_THRESHOLD_FRAMES))
                .isFalse();
    }

    @Test
    void shouldNotNotifyWhenInconclusive() {
        // A silent capture must never trigger a notification — that would
        // surface a misleading "0-sample" override to the user.
        float[] captured = new float[512];
        CalibrationResult result = LatencyCalibration.measure(captured, 200);

        assertThat(result.shouldNotify(0)).isFalse();
    }

    @Test
    void overrideFramesShouldEmitMeasuredValueWhenImpulseFound() {
        float[] captured = new float[512];
        captured[64] = 1.0f;
        CalibrationResult result = LatencyCalibration.measure(captured, 0);
        assertThat(result.overrideFrames()).contains(64);
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> LatencyCalibration.measure(null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LatencyCalibration.measure(new float[1], -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencyCalibration.measure(new float[1], 0, -0.1, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencyCalibration.measure(new float[1], 0, 0.01, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateImpulseShouldProduceUnitImpulse() {
        float[] impulse = LatencyCalibration.generateImpulse(64);
        assertThat(impulse).hasSize(64);
        assertThat(impulse[0]).isEqualTo(1.0f);
        for (int i = 1; i < impulse.length; i++) {
            assertThat(impulse[i]).isZero();
        }
    }

    @Test
    void shouldHandleNoisyCaptureByLockingOntoPeakLeadingEdge() {
        // A small amount of background noise plus a clearly louder impulse
        // — the detector should lock onto the impulse, not the noise.
        float[] captured = new float[1024];
        for (int i = 0; i < captured.length; i++) {
            captured[i] = (float) (Math.sin(i * 0.1) * 0.02); // -34 dBFS noise
        }
        captured[150] = 0.95f;
        CalibrationResult result = LatencyCalibration.measure(captured, 0);
        assertThat(result.impulseFound()).isTrue();
        assertThat(result.measuredFrames()).isEqualTo(150);
    }
}
