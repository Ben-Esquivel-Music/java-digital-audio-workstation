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
        assertThat(s.truePeakDbtp()).isEqualTo(Double.NEGATIVE_INFINITY);
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
        LoudnessSnapshot s = LoudnessSnapshot.SILENCE;
        assertThat(s.targetDeltaLu(-14.0)).isNaN();
    }
}
