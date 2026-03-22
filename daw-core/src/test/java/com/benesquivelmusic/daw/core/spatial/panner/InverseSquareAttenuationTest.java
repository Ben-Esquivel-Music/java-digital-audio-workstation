package com.benesquivelmusic.daw.core.spatial.panner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class InverseSquareAttenuationTest {

    // ---- Construction & Validation ----

    @Test
    void shouldCreateWithDefaultRolloff() {
        var model = new InverseSquareAttenuation(1.0, 50.0);
        assertThat(model.getReferenceDistance()).isEqualTo(1.0);
        assertThat(model.getMaxDistance()).isEqualTo(50.0);
    }

    @Test
    void shouldCreateWithCustomRolloff() {
        var model = new InverseSquareAttenuation(1.0, 50.0, 1.5);
        assertThat(model.getReferenceDistance()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectNonPositiveReferenceDistance() {
        assertThatThrownBy(() -> new InverseSquareAttenuation(0, 50.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referenceDistance");
    }

    @Test
    void shouldRejectMaxDistanceLessOrEqualToRef() {
        assertThatThrownBy(() -> new InverseSquareAttenuation(10, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDistance");
    }

    @Test
    void shouldRejectNonPositiveRolloff() {
        assertThatThrownBy(() -> new InverseSquareAttenuation(1, 50, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rolloffExponent");
    }

    // ---- Gain Computation ----

    @Test
    void shouldReturnUnityGainAtReferenceDistance() {
        var model = new InverseSquareAttenuation(1.0, 100.0);
        assertThat(model.computeGain(1.0)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldReturnUnityGainWithinReferenceDistance() {
        var model = new InverseSquareAttenuation(2.0, 100.0);
        assertThat(model.computeGain(0.5)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldReturnZeroGainBeyondMaxDistance() {
        var model = new InverseSquareAttenuation(1.0, 50.0);
        assertThat(model.computeGain(50.0)).isEqualTo(0.0);
        assertThat(model.computeGain(100.0)).isEqualTo(0.0);
    }

    @Test
    void shouldFollowInverseSquareLaw() {
        var model = new InverseSquareAttenuation(1.0, 100.0, 2.0);
        // At distance 2: gain = (1/2)^2 = 0.25
        assertThat(model.computeGain(2.0)).isCloseTo(0.25, within(1e-10));
        // At distance 4: gain = (1/4)^2 = 0.0625
        assertThat(model.computeGain(4.0)).isCloseTo(0.0625, within(1e-10));
    }

    @Test
    void shouldFollowCustomRolloff() {
        var model = new InverseSquareAttenuation(1.0, 100.0, 1.0);
        // Linear rolloff: at distance 2, gain = 1/2 = 0.5
        assertThat(model.computeGain(2.0)).isCloseTo(0.5, within(1e-10));
    }

    // ---- HF Rolloff ----

    @Test
    void shouldReturnFullHfAtReferenceDistance() {
        var model = new InverseSquareAttenuation(1.0, 51.0);
        assertThat(model.computeHighFrequencyRolloff(1.0)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldReturnZeroHfBeyondMaxDistance() {
        var model = new InverseSquareAttenuation(1.0, 51.0);
        assertThat(model.computeHighFrequencyRolloff(51.0)).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldInterpolateHfLinearly() {
        var model = new InverseSquareAttenuation(1.0, 11.0);
        // Midpoint: (6 - 1) / (11 - 1) = 0.5 → HF = 0.5
        assertThat(model.computeHighFrequencyRolloff(6.0)).isCloseTo(0.5, within(1e-10));
    }

    // ---- Reverb Send ----

    @Test
    void shouldReturnZeroReverbAtReferenceDistance() {
        var model = new InverseSquareAttenuation(1.0, 51.0);
        assertThat(model.computeReverbSend(1.0)).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void shouldReturnFullReverbBeyondMaxDistance() {
        var model = new InverseSquareAttenuation(1.0, 51.0);
        assertThat(model.computeReverbSend(51.0)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldInterpolateReverbLinearly() {
        var model = new InverseSquareAttenuation(1.0, 11.0);
        assertThat(model.computeReverbSend(6.0)).isCloseTo(0.5, within(1e-10));
    }
}
