package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpectrumDataTest {

    @Test
    void shouldDefensivelyCopyMagnitudesArray() {
        float[] magnitudes = new float[]{-60f, -40f, -20f, 0f};
        SpectrumData data = new SpectrumData(magnitudes, 8, 44100.0);

        // Mutating the original array should not affect the record's internal state
        magnitudes[0] = 99f;
        assertThat(data.magnitudesDb()[0]).isEqualTo(-60f);
    }

    @Test
    void shouldCreateWithValidParameters() {
        float[] magnitudes = new float[]{-60f, -40f, -20f, 0f};
        SpectrumData data = new SpectrumData(magnitudes, 8, 44100.0);

        assertThat(data.magnitudesDb()).isEqualTo(magnitudes);
        assertThat(data.fftSize()).isEqualTo(8);
        assertThat(data.sampleRate()).isEqualTo(44100.0);
        assertThat(data.binCount()).isEqualTo(4);
    }

    @Test
    void shouldCalculateFrequencyOfBin() {
        SpectrumData data = new SpectrumData(new float[512], 1024, 44100.0);

        assertThat(data.frequencyOfBin(0)).isEqualTo(0.0);
        assertThat(data.frequencyOfBin(1)).isCloseTo(43.066, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void shouldRejectNullMagnitudes() {
        assertThatThrownBy(() -> new SpectrumData(null, 1024, 44100.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidFftSize() {
        assertThatThrownBy(() -> new SpectrumData(new float[0], 0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new SpectrumData(new float[0], 1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateWithPeakHoldData() {
        float[] magnitudes = new float[]{-60f, -40f, -20f, 0f};
        float[] peakHold = new float[]{-50f, -30f, -10f, 0f};
        SpectrumData data = new SpectrumData(magnitudes, peakHold, 8, 44100.0);

        assertThat(data.magnitudesDb()).isEqualTo(magnitudes);
        assertThat(data.peakHoldDb()).isEqualTo(peakHold);
        assertThat(data.hasPeakHold()).isTrue();
    }

    @Test
    void shouldCreateWithoutPeakHoldViaThreeArgConstructor() {
        float[] magnitudes = new float[]{-60f, -40f, -20f, 0f};
        SpectrumData data = new SpectrumData(magnitudes, 8, 44100.0);

        assertThat(data.peakHoldDb()).isNull();
        assertThat(data.hasPeakHold()).isFalse();
    }

    @Test
    void shouldDefensivelyCopyPeakHoldArray() {
        float[] magnitudes = new float[]{-60f, -40f};
        float[] peakHold = new float[]{-50f, -30f};
        SpectrumData data = new SpectrumData(magnitudes, peakHold, 4, 44100.0);

        peakHold[0] = 99f;
        assertThat(data.peakHoldDb()[0]).isEqualTo(-50f);
    }
}
