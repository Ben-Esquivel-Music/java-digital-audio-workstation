package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaveformDataTest {

    @Test
    void shouldDefensivelyCopyArrays() {
        float[] min = {-0.5f, -0.3f};
        float[] max = {0.5f, 0.3f};
        float[] rms = {0.2f, 0.1f};
        var data = new WaveformData(min, max, rms, 2);

        // Mutating originals should not affect internal state
        min[0] = 99f;
        max[0] = 99f;
        rms[0] = 99f;

        assertThat(data.minValues()[0]).isEqualTo(-0.5f);
        assertThat(data.maxValues()[0]).isEqualTo(0.5f);
        assertThat(data.rmsValues()[0]).isEqualTo(0.2f);
    }

    @Test
    void shouldCreateWithValidParameters() {
        float[] min = {-0.5f, -0.3f};
        float[] max = {0.5f, 0.3f};
        float[] rms = {0.2f, 0.1f};

        var data = new WaveformData(min, max, rms, 2);

        assertThat(data.columns()).isEqualTo(2);
        assertThat(data.minValues()).isEqualTo(min);
        assertThat(data.maxValues()).isEqualTo(max);
        assertThat(data.rmsValues()).isEqualTo(rms);
    }

    @Test
    void shouldRejectMismatchedArrayLengths() {
        assertThatThrownBy(() -> new WaveformData(new float[2], new float[3], new float[2], 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroColumns() {
        assertThatThrownBy(() -> new WaveformData(new float[0], new float[0], new float[0], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullArrays() {
        assertThatThrownBy(() -> new WaveformData(null, new float[1], new float[1], 1))
                .isInstanceOf(NullPointerException.class);
    }
}
