package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaveformGeneratorTest {

    @Test
    void shouldGenerateCorrectNumberOfColumns() {
        float[] samples = new float[1000];
        WaveformData data = WaveformGenerator.generate(samples, 100);

        assertThat(data.columns()).isEqualTo(100);
        assertThat(data.minValues().length).isEqualTo(100);
        assertThat(data.maxValues().length).isEqualTo(100);
        assertThat(data.rmsValues().length).isEqualTo(100);
    }

    @Test
    void shouldDetectPeaksCorrectly() {
        float[] samples = new float[100];
        samples[25] = 0.8f;
        samples[75] = -0.6f;

        WaveformData data = WaveformGenerator.generate(samples, 10);

        // The column containing sample 25 should have max near 0.8
        float maxOverall = Float.NEGATIVE_INFINITY;
        float minOverall = Float.MAX_VALUE;
        for (int i = 0; i < data.columns(); i++) {
            if (data.maxValues()[i] > maxOverall) maxOverall = data.maxValues()[i];
            if (data.minValues()[i] < minOverall) minOverall = data.minValues()[i];
        }
        assertThat(maxOverall).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(minOverall).isCloseTo(-0.6f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void shouldProduceZeroForSilence() {
        float[] samples = new float[100];
        WaveformData data = WaveformGenerator.generate(samples, 10);

        for (int i = 0; i < data.columns(); i++) {
            assertThat(data.minValues()[i]).isEqualTo(0.0f);
            assertThat(data.maxValues()[i]).isEqualTo(0.0f);
            assertThat(data.rmsValues()[i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldCalculateRmsCorrectly() {
        // All samples at 0.5 → RMS should be 0.5
        float[] samples = new float[100];
        java.util.Arrays.fill(samples, 0.5f);
        WaveformData data = WaveformGenerator.generate(samples, 1);

        assertThat(data.rmsValues()[0]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void shouldHandlePartialSamples() {
        float[] samples = new float[100];
        samples[0] = 1.0f;
        // Process only first 50 samples
        WaveformData data = WaveformGenerator.generate(samples, 50, 10);

        assertThat(data.columns()).isEqualTo(10);
    }

    @Test
    void shouldRejectNullSamples() {
        assertThatThrownBy(() -> WaveformGenerator.generate(null, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroColumns() {
        assertThatThrownBy(() -> WaveformGenerator.generate(new float[100], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroSamples() {
        assertThatThrownBy(() -> WaveformGenerator.generate(new float[100], 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleMoreColumnsThanSamples() {
        float[] samples = new float[10];
        java.util.Arrays.fill(samples, 0.5f);
        WaveformData data = WaveformGenerator.generate(samples, 100);

        assertThat(data.columns()).isEqualTo(100);
    }
}
