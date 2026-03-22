package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
