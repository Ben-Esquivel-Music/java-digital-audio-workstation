package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.visualization.MultibandCompressorData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class MultibandCompressorProcessorTest {

    private static final double SAMPLE_RATE = 44100.0;

    // --- Construction and band count ---

    @Test
    void shouldCreateTwoBandCompressor() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(2, SAMPLE_RATE,
                new double[]{1000.0});
        assertThat(mbc.getBandCount()).isEqualTo(2);
        assertThat(mbc.getInputChannelCount()).isEqualTo(2);
        assertThat(mbc.getOutputChannelCount()).isEqualTo(2);
        assertThat(mbc.getCrossoverFrequencies()).containsExactly(1000.0);
    }

    @Test
    void shouldCreateThreeBandCompressor() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(2, SAMPLE_RATE,
                new double[]{200.0, 5000.0});
        assertThat(mbc.getBandCount()).isEqualTo(3);
        assertThat(mbc.getCrossoverFrequencies()).containsExactly(200.0, 5000.0);
    }

    @Test
    void shouldCreateFourBandCompressor() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(2, SAMPLE_RATE,
                new double[]{200.0, 2000.0, 8000.0});
        assertThat(mbc.getBandCount()).isEqualTo(4);
        assertThat(mbc.getCrossoverFrequencies()).containsExactly(200.0, 2000.0, 8000.0);
    }

    @Test
    void shouldCreateFiveBandCompressor() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(2, SAMPLE_RATE,
                new double[]{120.0, 500.0, 2500.0, 8000.0});
        assertThat(mbc.getBandCount()).isEqualTo(5);
        assertThat(mbc.getCrossoverFrequencies()).containsExactly(120.0, 500.0, 2500.0, 8000.0);
    }

    // --- Pass-through behavior ---

    @Test
    void shouldPassThroughWhenNoCompression() {
        // With threshold at 0 dB, no compression should be applied
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});
        for (int band = 0; band < mbc.getBandCount(); band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        int numFrames = 16384;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        // Multi-frequency signal
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * 200 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 5000 * i / SAMPLE_RATE)) * 0.3f;
        }

        mbc.process(input, output, numFrames);

        // After settling, RMS energy should be conserved (crossover reconstruction)
        double inputRms = rms(input[0], 4096, numFrames);
        double outputRms = rms(output[0], 4096, numFrames);
        assertThat(outputRms).isCloseTo(inputRms, offset(inputRms * 0.1));
    }

    // --- Per-band compression independence ---

    @Test
    void shouldCompressLowBandIndependently() {
        // 2-band with crossover at 4000 Hz
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{4000.0});

        // Aggressively compress only the low band
        mbc.getBandCompressor(0).setThresholdDb(-30.0);
        mbc.getBandCompressor(0).setRatio(20.0);
        mbc.getBandCompressor(0).setAttackMs(0.01);
        mbc.getBandCompressor(0).setKneeDb(0.0);

        // Leave high band uncompressed
        mbc.getBandCompressor(1).setThresholdDb(0.0);

        int numFrames = 8192;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        // Generate low-frequency signal (200 Hz)
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2 * Math.PI * 200 * i / SAMPLE_RATE));
        }

        mbc.process(input, output, numFrames);

        // Low band should show gain reduction
        assertThat(mbc.getBandGainReductionDb(0)).isLessThan(0.0);

        // Output RMS should be less than input RMS (compression)
        double inputRms = rms(input[0], 4096, numFrames);
        double outputRms = rms(output[0], 4096, numFrames);
        assertThat(outputRms).isLessThan(inputRms);
    }

    @Test
    void shouldCompressHighBandIndependently() {
        // 2-band with crossover at 1000 Hz
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});

        // Leave low band uncompressed
        mbc.getBandCompressor(0).setThresholdDb(0.0);

        // Aggressively compress the high band
        mbc.getBandCompressor(1).setThresholdDb(-30.0);
        mbc.getBandCompressor(1).setRatio(20.0);
        mbc.getBandCompressor(1).setAttackMs(0.01);
        mbc.getBandCompressor(1).setKneeDb(0.0);

        int numFrames = 8192;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        // Generate high-frequency signal (10000 Hz)
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2 * Math.PI * 10000 * i / SAMPLE_RATE));
        }

        mbc.process(input, output, numFrames);

        // High band should show gain reduction
        assertThat(mbc.getBandGainReductionDb(1)).isLessThan(0.0);

        // Output RMS should be less than input RMS
        double inputRms = rms(input[0], 4096, numFrames);
        double outputRms = rms(output[0], 4096, numFrames);
        assertThat(outputRms).isLessThan(inputRms);
    }

    // --- Solo / Bypass ---

    @Test
    void shouldBypassBandCompression() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});

        // Compress the low band aggressively
        mbc.getBandCompressor(0).setThresholdDb(-30.0);
        mbc.getBandCompressor(0).setRatio(20.0);
        mbc.getBandCompressor(0).setAttackMs(0.01);
        mbc.getBandCompressor(0).setKneeDb(0.0);
        mbc.getBandCompressor(1).setThresholdDb(0.0);

        // Now bypass the low band
        mbc.setBandBypassed(0, true);
        assertThat(mbc.isBandBypassed(0)).isTrue();

        int numFrames = 16384;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2 * Math.PI * 200 * i / SAMPLE_RATE));
        }

        mbc.process(input, output, numFrames);

        // With bypass on, RMS energy should be conserved (no compression)
        double inputRms = rms(input[0], 4096, numFrames);
        double outputRms = rms(output[0], 4096, numFrames);
        assertThat(outputRms).isCloseTo(inputRms, offset(inputRms * 0.1));
    }

    @Test
    void shouldSoloBand() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{4000.0});
        for (int band = 0; band < mbc.getBandCount(); band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        // Solo the low band
        mbc.setBandSoloed(0, true);
        assertThat(mbc.isBandSoloed(0)).isTrue();

        int numFrames = 8192;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        // Generate a signal with both low and high frequency content
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2 * Math.PI * 200 * i / SAMPLE_RATE)
                    + 0.5 * Math.sin(2 * Math.PI * 10000 * i / SAMPLE_RATE));
        }

        mbc.process(input, output, numFrames);

        // Output should only contain low frequency content (high band muted by solo)
        // Measure energy at high frequency by checking that high content is suppressed
        double outputRms = rms(output[0], 2048, numFrames);
        assertThat(outputRms).isGreaterThan(0.0); // We should still have low-freq output

        // Process the full signal without solo for comparison
        mbc.setBandSoloed(0, false);
        mbc.reset();
        float[][] fullOutput = new float[1][numFrames];
        mbc.process(input, fullOutput, numFrames);

        double fullRms = rms(fullOutput[0], 2048, numFrames);
        // Soloed output should have less energy than the full output
        assertThat(outputRms).isLessThan(fullRms);
    }

    // --- Per-band makeup gain ---

    @Test
    void shouldApplyPerBandMakeupGain() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});
        for (int band = 0; band < mbc.getBandCount(); band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        mbc.setBandMakeupGainDb(0, 6.0); // +6 dB on low band
        assertThat(mbc.getBandMakeupGainDb(0)).isEqualTo(6.0);
        assertThat(mbc.getBandMakeupGainDb(1)).isEqualTo(0.0);
    }

    // --- Metering ---

    @Test
    void shouldProvideMeteringData() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{200.0, 5000.0});

        // Process some loud signal
        int numFrames = 4096;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = 0.9f;
        }
        mbc.process(input, output, numFrames);

        MultibandCompressorData data = mbc.getMeteringData();
        assertThat(data.bandCount()).isEqualTo(3);
        assertThat(data.bandGainReductionDb()).hasSize(3);
        assertThat(data.crossoverFrequencies()).containsExactly(200.0, 5000.0);
    }

    @Test
    void shouldReportPerBandGainReduction() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});

        // Compress both bands
        for (int band = 0; band < 2; band++) {
            mbc.getBandCompressor(band).setThresholdDb(-20.0);
            mbc.getBandCompressor(band).setRatio(4.0);
            mbc.getBandCompressor(band).setAttackMs(0.01);
        }

        int numFrames = 4096;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = 0.9f;
        }
        mbc.process(input, output, numFrames);

        // Both bands should report gain reduction
        for (int band = 0; band < 2; band++) {
            assertThat(mbc.getBandGainReductionDb(band)).isLessThanOrEqualTo(0.0);
        }
    }

    // --- Reset ---

    @Test
    void shouldResetAllBands() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});

        float[][] buf = new float[1][1024];
        java.util.Arrays.fill(buf[0], 0.9f);
        mbc.process(buf, new float[1][1024], 1024);

        mbc.reset();

        // After reset, gain reduction should be zero for all bands
        for (int band = 0; band < mbc.getBandCount(); band++) {
            assertThat(mbc.getBandGainReductionDb(band)).isEqualTo(0.0);
        }
    }

    // --- Multi-channel ---

    @Test
    void shouldProcessStereoSignal() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(2, SAMPLE_RATE,
                new double[]{1000.0});

        int numFrames = 4096;
        float[][] input = new float[2][numFrames];
        float[][] output = new float[2][numFrames];

        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
            input[1][i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE));
        }

        mbc.process(input, output, numFrames);

        // Both channels should have output
        assertThat(rms(output[0], 1024, numFrames)).isGreaterThan(0.0);
        assertThat(rms(output[1], 1024, numFrames)).isGreaterThan(0.0);
    }

    // --- Three-band processing ---

    @Test
    void shouldProcessThreeBands() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{300.0, 5000.0});
        for (int band = 0; band < 3; band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        int numFrames = 16384;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * 100 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 1000 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 10000 * i / SAMPLE_RATE)) * 0.3f;
        }

        mbc.process(input, output, numFrames);

        // Output RMS should approximate input RMS (energy conservation)
        double inputRms = rms(input[0], 8192, numFrames);
        double outputRms = rms(output[0], 8192, numFrames);
        assertThat(outputRms).isCloseTo(inputRms, offset(inputRms * 0.15));
    }

    // --- Four-band processing ---

    @Test
    void shouldProcessFourBands() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{200.0, 2000.0, 8000.0});
        for (int band = 0; band < 4; band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        int numFrames = 16384;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * 50 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 800 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 5000 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 15000 * i / SAMPLE_RATE)) * 0.2f;
        }

        mbc.process(input, output, numFrames);

        // Output RMS should approximate input RMS (energy conservation)
        double inputRms = rms(input[0], 8192, numFrames);
        double outputRms = rms(output[0], 8192, numFrames);
        assertThat(outputRms).isCloseTo(inputRms, offset(inputRms * 0.2));
    }

    // --- Five-band processing ---

    @Test
    void shouldProcessFiveBands() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{120.0, 500.0, 2500.0, 8000.0});
        for (int band = 0; band < 5; band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        int numFrames = 16384;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * 50 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 300 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 1500 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 5000 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 15000 * i / SAMPLE_RATE)) * 0.18f;
        }

        mbc.process(input, output, numFrames);

        // Output RMS should approximate input RMS (energy conservation)
        double inputRms = rms(input[0], 8192, numFrames);
        double outputRms = rms(output[0], 8192, numFrames);
        assertThat(outputRms).isCloseTo(inputRms, offset(inputRms * 0.25));
    }

    // --- Validation ---

    @Test
    void shouldRejectInvalidChannels() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(0, SAMPLE_RATE, new double[]{1000.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(1, 0, new double[]{1000.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullCrossoverFrequencies() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(1, SAMPLE_RATE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyCrossoverFrequencies() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(1, SAMPLE_RATE, new double[]{}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTooManyCrossoverFrequencies() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(1, SAMPLE_RATE,
                        new double[]{200, 500, 2000, 8000, 16000}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonAscendingCrossoverFrequencies() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(1, SAMPLE_RATE,
                        new double[]{5000.0, 1000.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectCrossoverAboveNyquist() {
        assertThatThrownBy(() ->
                new MultibandCompressorProcessor(1, SAMPLE_RATE,
                        new double[]{30000.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBandIndex() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});
        assertThatThrownBy(() -> mbc.getBandCompressor(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> mbc.getBandCompressor(2))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // --- Buffer size adaptation ---

    @Test
    void shouldHandleVaryingBufferSizes() {
        MultibandCompressorProcessor mbc = new MultibandCompressorProcessor(1, SAMPLE_RATE,
                new double[]{1000.0});
        for (int band = 0; band < 2; band++) {
            mbc.getBandCompressor(band).setThresholdDb(0.0);
        }

        // Process with a small buffer first
        float[][] input1 = new float[1][128];
        float[][] output1 = new float[1][128];
        for (int i = 0; i < 128; i++) {
            input1[0][i] = 0.1f;
        }
        mbc.process(input1, output1, 128);

        // Then process with a larger buffer
        float[][] input2 = new float[1][2048];
        float[][] output2 = new float[1][2048];
        for (int i = 0; i < 2048; i++) {
            input2[0][i] = 0.1f;
        }
        mbc.process(input2, output2, 2048);

        // Should not throw and should produce output
        assertThat(rms(output2[0], 1024, 2048)).isGreaterThan(0.0);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
