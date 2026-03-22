package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpectrumDataTest {

    @Test
    void shouldCreateWithValidParameters() {
        float[] magnitudes = new float[]{-60f, -40f, -20f, 0f};
        var data = new SpectrumData(magnitudes, 8, 44100.0);

        assertThat(data.magnitudesDb()).isEqualTo(magnitudes);
        assertThat(data.fftSize()).isEqualTo(8);
        assertThat(data.sampleRate()).isEqualTo(44100.0);
        assertThat(data.binCount()).isEqualTo(4);
    }

    @Test
    void shouldCalculateFrequencyOfBin() {
        var data = new SpectrumData(new float[512], 1024, 44100.0);

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
}
