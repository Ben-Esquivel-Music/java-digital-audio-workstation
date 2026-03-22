package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DspUtilsTest {

    @Test
    void envelopeCoefficient_positiveTime_returnsValueBetweenZeroAndOne() {
        double coeff = DspUtils.envelopeCoefficient(10.0, 44100.0);
        assertThat(coeff).isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    void envelopeCoefficient_zeroTime_returnsZero() {
        double coeff = DspUtils.envelopeCoefficient(0.0, 44100.0);
        assertThat(coeff).isEqualTo(0.0);
    }

    @Test
    void envelopeCoefficient_negativeTime_returnsZero() {
        double coeff = DspUtils.envelopeCoefficient(-5.0, 44100.0);
        assertThat(coeff).isEqualTo(0.0);
    }

    @Test
    void envelopeCoefficient_longerTime_yieldsLargerCoefficient() {
        double shortCoeff = DspUtils.envelopeCoefficient(1.0, 44100.0);
        double longCoeff = DspUtils.envelopeCoefficient(100.0, 44100.0);
        assertThat(longCoeff).isGreaterThan(shortCoeff);
    }

    @Test
    void envelopeCoefficient_higherSampleRate_yieldsLargerCoefficient() {
        double lowRate = DspUtils.envelopeCoefficient(10.0, 22050.0);
        double highRate = DspUtils.envelopeCoefficient(10.0, 96000.0);
        assertThat(highRate).isGreaterThan(lowRate);
    }

    @Test
    void envelopeCoefficient_matchesManualCalculation() {
        double timeMs = 50.0;
        double sampleRate = 48000.0;
        double expected = Math.exp(-1.0 / (timeMs * 0.001 * sampleRate));
        double actual = DspUtils.envelopeCoefficient(timeMs, sampleRate);
        assertThat(actual).isEqualTo(expected);
    }

    // --- Half-band filter design tests ---

    @Test
    void designHalfBandCoefficients_shouldReturnOddLength() {
        double[] h = DspUtils.designHalfBandCoefficients(31);
        assertThat(h.length).isEqualTo(31);
    }

    @Test
    void designHalfBandCoefficients_shouldForceOddLength() {
        double[] h = DspUtils.designHalfBandCoefficients(30);
        assertThat(h.length % 2).isEqualTo(1);
    }

    @Test
    void designHalfBandCoefficients_centerTapShouldBeHalf() {
        double[] h = DspUtils.designHalfBandCoefficients(31);
        int center = (h.length - 1) / 2;
        assertThat(h[center]).isEqualTo(0.5);
    }

    @Test
    void designHalfBandCoefficients_evenOffsetsShouldBeZero() {
        double[] h = DspUtils.designHalfBandCoefficients(31);
        int center = (h.length - 1) / 2;
        for (int n = 0; n < h.length; n++) {
            int k = n - center;
            if (k != 0 && k % 2 == 0) {
                assertThat(h[n])
                        .as("h[%d] (offset %d from center) should be zero", n, k)
                        .isEqualTo(0.0);
            }
        }
    }

    @Test
    void designHalfBandCoefficients_shouldBeSymmetric() {
        double[] h = DspUtils.designHalfBandCoefficients(31);
        int center = (h.length - 1) / 2;
        for (int n = 0; n < center; n++) {
            assertThat(h[n]).isCloseTo(h[h.length - 1 - n],
                    org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    void designHalfBandCoefficients_shouldRejectTooFewTaps() {
        assertThatThrownBy(() -> DspUtils.designHalfBandCoefficients(2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void besselI0_atZero_shouldReturnOne() {
        assertThat(DspUtils.besselI0(0.0)).isEqualTo(1.0);
    }

    @Test
    void besselI0_shouldIncreaseMonotonically() {
        assertThat(DspUtils.besselI0(2.0)).isGreaterThan(DspUtils.besselI0(1.0));
        assertThat(DspUtils.besselI0(5.0)).isGreaterThan(DspUtils.besselI0(2.0));
    }
}
