package com.benesquivelmusic.daw.sdk.mastering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoudnessSnapshotTest {

    @Test
    void silenceShouldHaveNegativeInfinityLoudness() {
        LoudnessSnapshot s = LoudnessSnapshot.SILENCE;
        assertThat(s.momentaryLufs()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(s.shortTermLufs()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(s.integratedLufs()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(s.loudnessRangeLu()).isEqualTo(0.0);
        assertThat(s.samplePeakDbfs()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void targetDeltaShouldReturnSignedDifferenceInLu() {
        // -10 LUFS integrated against a -14 LUFS target = +4 LU louder.
        LoudnessSnapshot louder = new LoudnessSnapshot(-9.0, -10.0, -10.0, 6.0, -1.0);
        assertThat(louder.targetDeltaLu(-14.0)).isEqualTo(4.0);

        // -20 LUFS integrated against a -14 LUFS target = -6 LU quieter.
        LoudnessSnapshot quieter = new LoudnessSnapshot(-19.0, -20.0, -20.0, 4.0, -3.0);
        assertThat(quieter.targetDeltaLu(-14.0)).isEqualTo(-6.0);
    }

    @Test
    void targetDeltaShouldBeNanWhenIntegratedNotMeasurable() {
        // Negative infinity (no signal at all).
        assertThat(LoudnessSnapshot.SILENCE.targetDeltaLu(-14.0)).isNaN();

        // Below or at the EBU R128 absolute gating threshold (no gated
        // blocks accumulated yet — the meter reports a finite floor like
        // -120 LUFS in that case).
        LoudnessSnapshot belowGate = new LoudnessSnapshot(-90.0, -90.0, -120.0, 0.0, -90.0);
        assertThat(belowGate.targetDeltaLu(-14.0)).isNaN();

        LoudnessSnapshot atGate = new LoudnessSnapshot(-70.0, -70.0, -70.0, 0.0, -70.0);
        assertThat(atGate.targetDeltaLu(-14.0)).isNaN();

        // Just above the gate is measurable.
        LoudnessSnapshot aboveGate = new LoudnessSnapshot(-65.0, -65.0, -65.0, 0.5, -60.0);
        assertThat(aboveGate.targetDeltaLu(-14.0)).isEqualTo(-65.0 - -14.0);
    }
}
