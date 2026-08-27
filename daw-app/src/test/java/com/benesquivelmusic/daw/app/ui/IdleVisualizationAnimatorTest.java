package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdleVisualizationAnimator}. The pure-math
 * spectrum helper is exercised directly without instantiating the JavaFX
 * display — these assertions were not possible before extraction
 * because {@code AnimationController} required a live JavaFX scene.
 *
 * <p>Story 318 deleted the synthetic RMS/peak level push (and its
 * {@code computeLevelData} tests): the Peak / RMS display is now a real
 * consumer of the metering tap bus.</p>
 */
class IdleVisualizationAnimatorTest {

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
}
