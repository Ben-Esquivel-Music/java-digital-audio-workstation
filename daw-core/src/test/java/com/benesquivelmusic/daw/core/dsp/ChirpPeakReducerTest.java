package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChirpPeakReducerTest {

    @Test
    void shouldCreateWithDefaults() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(2, 44100.0);
        assertThat(reducer.getInputChannelCount()).isEqualTo(2);
        assertThat(reducer.getOutputChannelCount()).isEqualTo(2);
        assertThat(reducer.getThresholdDb()).isEqualTo(-6.0);
        assertThat(reducer.getChirpDurationMs()).isEqualTo(2.0);
        assertThat(reducer.getChirpBandwidthHz()).isEqualTo(8000.0);
        assertThat(reducer.getMix()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new ChirpPeakReducer(0, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChirpPeakReducer(-1, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChirpPeakReducer(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChirpPeakReducer(2, -44100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidThreshold() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        assertThatThrownBy(() -> reducer.setThresholdDb(1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reducer.setThresholdDb(-61.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptBoundaryThresholdValues() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(0.0);
        assertThat(reducer.getThresholdDb()).isEqualTo(0.0);
        reducer.setThresholdDb(-60.0);
        assertThat(reducer.getThresholdDb()).isEqualTo(-60.0);
    }

    @Test
    void shouldRejectInvalidChirpDuration() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        assertThatThrownBy(() -> reducer.setChirpDurationMs(0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reducer.setChirpDurationMs(6.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidChirpBandwidth() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        assertThatThrownBy(() -> reducer.setChirpBandwidthHz(500.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reducer.setChirpBandwidthHz(21000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        assertThatThrownBy(() -> reducer.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reducer.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPassThroughWhenMixIsZero() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setMix(0.0);

        int blockSize = 256;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.9f;
        }
        reducer.process(input, output, blockSize);

        for (int i = 0; i < blockSize; i++) {
            assertThat(output[0][i]).isEqualTo(input[0][i]);
        }
    }

    @Test
    void shouldPassThroughBelowThreshold() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(0.0); // Threshold at 0 dB — nothing above it

        int blockSize = 256;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.5f;
        }
        reducer.process(input, output, blockSize);

        // Below-threshold signal should pass through with only overlap-add residue
        for (int i = 0; i < blockSize; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    @Test
    void shouldReducePeakAboveThreshold() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-6.0); // ~0.5 linear
        reducer.setChirpDurationMs(2.0);

        int blockSize = 1024;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Create a signal with a sharp transient peak
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.1f; // quiet background
        }
        // Single sharp peak
        input[0][512] = 0.95f;

        reducer.process(input, output, blockSize);

        // The peak at sample 512 should be reduced compared to the original
        assertThat(Math.abs(output[0][512])).isLessThan(0.95f);
    }

    @Test
    void shouldPreserveEnergyForIsolatedPeak() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-6.0); // ~0.5 linear
        reducer.setChirpDurationMs(2.0);

        int blockSize = 4096;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Single isolated peak in a quiet signal — ideal case for chirp spreading
        input[0][2048] = 0.95f;

        reducer.process(input, output, blockSize);

        // Energy should be approximately preserved: the peak energy is spread
        // across the chirp kernel length rather than concentrated in one sample
        double inputEnergy = computeEnergy(input[0], 0, blockSize);
        double outputEnergy = computeEnergy(output[0], 0, blockSize);

        // Allow reasonable tolerance for windowing and edge effects
        assertThat(outputEnergy).isGreaterThan(inputEnergy * 0.3);
        assertThat(outputEnergy).isLessThan(inputEnergy * 3.0);
    }

    @Test
    void shouldReduceCrestFactor() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-12.0);
        reducer.setChirpDurationMs(3.0);
        reducer.setChirpBandwidthHz(10000.0);

        int blockSize = 4096;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Create a signal with high crest factor: quiet base + sharp peaks
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * i / 200.0) * 0.2);
        }
        // Add sharp peaks
        for (int i = 256; i < blockSize; i += 512) {
            input[0][i] = 0.95f;
        }

        reducer.process(input, output, blockSize);

        // Crest factor should be reduced (peak / RMS should be lower)
        double inputCrest = crestFactor(input[0], 0, blockSize);
        double outputCrest = crestFactor(output[0], 0, blockSize);
        assertThat(outputCrest).isLessThan(inputCrest);
    }

    @Test
    void shouldHandleStereoInput() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(2, 44100.0);
        reducer.setThresholdDb(-6.0);

        int blockSize = 512;
        float[][] input = new float[2][blockSize];
        float[][] output = new float[2][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.8f;
            input[1][i] = -0.8f;
        }
        reducer.process(input, output, blockSize);

        // Should process both channels without error
        boolean anyNonZero = false;
        for (int i = 0; i < blockSize; i++) {
            if (output[0][i] != 0.0f || output[1][i] != 0.0f) {
                anyNonZero = true;
                break;
            }
        }
        assertThat(anyNonZero).isTrue();
    }

    @Test
    void shouldResetState() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-6.0);

        float[][] input = {{0.9f, 0.95f, 0.8f}};
        reducer.process(input, new float[1][3], 3);

        reducer.reset();

        // After reset, processing a silent buffer should produce silence
        int blockSize = 256;
        float[][] silentInput = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        reducer.process(silentInput, output, blockSize);

        for (int i = 0; i < blockSize; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldUpdateChirpDuration() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setChirpDurationMs(3.0);
        assertThat(reducer.getChirpDurationMs()).isEqualTo(3.0);

        // Latency should correspond to chirp duration
        int expectedSamples = (int) (3.0 * 0.001 * 44100.0);
        assertThat(reducer.getLatencySamples()).isEqualTo(expectedSamples);
    }

    @Test
    void shouldUpdateChirpBandwidth() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setChirpBandwidthHz(12000.0);
        assertThat(reducer.getChirpBandwidthHz()).isEqualTo(12000.0);
    }

    @Test
    void shouldReportLatency() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        assertThat(reducer.getLatencySamples()).isGreaterThan(0);
        // Default 2 ms at 44100 Hz → 88 samples
        int expectedSamples = (int) (2.0 * 0.001 * 44100.0);
        assertThat(reducer.getLatencySamples()).isEqualTo(expectedSamples);
    }

    @Test
    void shouldHandleNegativePeaks() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-6.0);

        int blockSize = 1024;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Create a negative peak
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.1f;
        }
        input[0][512] = -0.95f;

        reducer.process(input, output, blockSize);

        // The negative peak should also be spread
        assertThat(Math.abs(output[0][512])).isLessThan(0.95f);
    }

    @Test
    void shouldProcessMultipleBlocks() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-6.0);

        int blockSize = 256;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Process multiple blocks to verify overlap-add state is maintained
        for (int block = 0; block < 4; block++) {
            for (int i = 0; i < blockSize; i++) {
                input[0][i] = (float) (Math.sin(2 * Math.PI * (block * blockSize + i) / 50.0) * 0.8);
            }
            reducer.process(input, output, blockSize);
        }

        // Should complete without errors and produce output
        boolean anyNonZero = false;
        for (int i = 0; i < blockSize; i++) {
            if (output[0][i] != 0.0f) {
                anyNonZero = true;
                break;
            }
        }
        assertThat(anyNonZero).isTrue();
    }

    @Test
    void shouldHandleSilentInput() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        int blockSize = 256;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        reducer.process(input, output, blockSize);

        for (int i = 0; i < blockSize; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldApplyPartialMix() {
        ChirpPeakReducer reducer = new ChirpPeakReducer(1, 44100.0);
        reducer.setThresholdDb(-6.0);
        reducer.setMix(0.5);

        int blockSize = 1024;
        float[][] input = new float[1][blockSize];
        float[][] output50 = new float[1][blockSize];
        float[][] output100 = new float[1][blockSize];

        // Signal with a peak
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.1f;
        }
        input[0][512] = 0.95f;

        // Process at 50% mix
        reducer.process(input, output50, blockSize);

        // Reset and process at 100% mix for comparison
        reducer.reset();
        reducer.setMix(1.0);
        reducer.process(input, output100, blockSize);

        // At 50% mix, the peak reduction should be less aggressive than at 100%
        // The peak sample at 512 should be between the original and the 100% processed
        float original = input[0][512];
        float mixed50 = output50[0][512];
        float mixed100 = output100[0][512];

        assertThat(Math.abs(mixed50)).isGreaterThanOrEqualTo(Math.abs(mixed100));
        assertThat(Math.abs(mixed50)).isLessThanOrEqualTo(original);
    }

    private static double computeEnergy(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return sum;
    }

    private static double crestFactor(float[] buffer, int start, int end) {
        double peak = 0;
        double sumSq = 0;
        for (int i = start; i < end; i++) {
            double abs = Math.abs(buffer[i]);
            if (abs > peak) peak = abs;
            sumSq += (double) buffer[i] * buffer[i];
        }
        double rms = Math.sqrt(sumSq / (end - start));
        return (rms > 0) ? peak / rms : 0;
    }
}
