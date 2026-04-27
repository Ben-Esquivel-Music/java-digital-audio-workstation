package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link IdleVisualizationAnimator}. The pure-math
 * helpers are exercised directly without instantiating the JavaFX
 * displays — these assertions were not possible before extraction
 * because {@code AnimationController} required a live JavaFX scene.
 */
class IdleVisualizationAnimatorTest {

    private static final double EPS = 1e-6;

    @Test
    void spectrumBinsAreClampedToDecibelFloorAndShapedAsPinkNoise() {
        float[] bins = new float[IdleVisualizationAnimator.BIN_COUNT];

        IdleVisualizationAnimator.computeSpectrumBins(0.0, bins);

        // Floor: never below -90 dB
        for (float bin : bins) {
            assertThat(bin).isGreaterThanOrEqualTo(-90.0f);
        }
        // Pink-noise downward slope: low bins should generally exceed
        // very-high bins (last bin is heavily attenuated).
        assertThat(bins[10]).isGreaterThan(bins[bins.length - 1]);
        // bin[0] is mirrored from bin[1]
        assertThat(bins[0]).isEqualTo(bins[1]);
    }

    @Test
    void spectrumBinsAreDeterministicForAFixedPhase() {
        float[] a = new float[IdleVisualizationAnimator.BIN_COUNT];
        float[] b = new float[IdleVisualizationAnimator.BIN_COUNT];

        IdleVisualizationAnimator.computeSpectrumBins(1.234, a);
        IdleVisualizationAnimator.computeSpectrumBins(1.234, b);

        assertThat(a).containsExactly(b);
    }

    @Test
    void spectrumBufferTooSmallThrows() {
        float[] tooShort = new float[10];

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IdleVisualizationAnimator.computeSpectrumBins(0.0, tooShort));
    }

    @Test
    void levelDataRmsAndPeakStayWithinDocumentedBreathingRange() {
        // Sweep through several phases — the breathing values must remain
        // within the documented [0.18, 0.30] (RMS) and (0, 0.85] (peak)
        // ranges.
        double minRms = Double.MAX_VALUE;
        double maxRms = -Double.MAX_VALUE;
        double maxPeak = -Double.MAX_VALUE;

        for (int i = 0; i < 200; i++) {
            double phase = i * 0.05;
            LevelData level = IdleVisualizationAnimator.computeLevelData(phase);
            assertThat(level.rmsLinear()).isBetween(0.18 - EPS, 0.30 + EPS);
            assertThat(level.peakLinear()).isLessThanOrEqualTo(0.85 + EPS);
            assertThat(level.peakLinear()).isGreaterThanOrEqualTo(level.rmsLinear() - EPS);
            assertThat(level.clipping()).isFalse();
            minRms = Math.min(minRms, level.rmsLinear());
            maxRms = Math.max(maxRms, level.rmsLinear());
            maxPeak = Math.max(maxPeak, level.peakLinear());
        }
        // The breathing visibly varies (not flat).
        assertThat(maxRms - minRms).isGreaterThan(0.05);
        assertThat(maxPeak).isGreaterThan(0.3);
    }

    @Test
    void levelDataAtPhaseZeroExactlyMatchesFormula() {
        LevelData level = IdleVisualizationAnimator.computeLevelData(0.0);
        // sin(0)=0 → rms = 0.18, peakBoost = 1.0, peak = 0.18 * 1.0 * 1.3 = 0.234
        assertThat(level.rmsLinear()).isCloseTo(0.18, offset(EPS));
        assertThat(level.peakLinear()).isCloseTo(0.234, offset(EPS));
    }

    @Test
    void peakIsClampedToCeiling() {
        // Phase that maximizes both sin(0.75 * t) and sin(1.8 * t) approximately
        // pi/2 / 0.75 = 2.094 and pi/2 / 1.8 = 0.873.
        // Just verify the cap holds for a sweep of phases.
        for (int i = 0; i < 1000; i++) {
            LevelData level = IdleVisualizationAnimator.computeLevelData(i * 0.013);
            assertThat(level.peakLinear()).isLessThanOrEqualTo(0.85 + EPS);
        }
    }
}
