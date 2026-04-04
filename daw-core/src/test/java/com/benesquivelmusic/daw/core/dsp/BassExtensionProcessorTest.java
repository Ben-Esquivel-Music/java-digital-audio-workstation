package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BassExtensionProcessorTest {

    private static final double SAMPLE_RATE = 44100.0;

    @Test
    void shouldCreateWithDefaults() {
        BassExtensionProcessor proc = new BassExtensionProcessor(2, SAMPLE_RATE);
        assertThat(proc.getInputChannelCount()).isEqualTo(2);
        assertThat(proc.getOutputChannelCount()).isEqualTo(2);
        assertThat(proc.getCrossoverHz()).isEqualTo(80.0);
        assertThat(proc.getHarmonicOrder()).isEqualTo(3);
        assertThat(proc.getHarmonicLevel()).isEqualTo(0.5);
        assertThat(proc.getMix()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new BassExtensionProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BassExtensionProcessor(-1, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BassExtensionProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BassExtensionProcessor(2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidCrossoverHz() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setCrossoverHz(39.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setCrossoverHz(121.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidHarmonicOrder() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setHarmonicOrder(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setHarmonicOrder(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidHarmonicLevel() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setHarmonicLevel(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setHarmonicLevel(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> proc.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proc.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setMix(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessSilenceToSilence() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setHarmonicLevel(1.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        proc.process(input, output, 256);

        for (int i = 0; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldGenerateHarmonicsForLowFrequencyInput() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setCrossoverHz(80.0);
        proc.setHarmonicOrder(3);
        proc.setHarmonicLevel(1.0);
        proc.setMix(1.0);

        // Generate a 60 Hz sine wave (below crossover)
        int length = 8192;
        float[][] input = new float[1][length];
        float[][] output = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 60.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, length);

        // Output should have more energy than input due to added harmonics
        // (after filter settling period)
        double inputRms = rms(input[0], length / 2, length);
        double outputRms = rms(output[0], length / 2, length);
        assertThat(outputRms).isGreaterThan(inputRms);
    }

    @Test
    void shouldNotAffectHighFrequencyContent() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setCrossoverHz(80.0);
        proc.setHarmonicLevel(1.0);
        proc.setMix(1.0);

        // 1 kHz sine wave — well above crossover, should pass through mostly unaltered
        int length = 8192;
        float[][] input = new float[1][length];
        float[][] output = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, length);

        // After settling, high-frequency signal should be approximately unchanged
        double inputRms = rms(input[0], length / 2, length);
        double outputRms = rms(output[0], length / 2, length);
        assertThat(outputRms).isCloseTo(inputRms,
                org.assertj.core.data.Offset.offset(inputRms * 0.15));
    }

    @Test
    void higherHarmonicOrderShouldProduceMoreEnergy() {
        int length = 8192;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 60.0 * i / SAMPLE_RATE));
        }

        BassExtensionProcessor proc2 = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc2.setCrossoverHz(80.0);
        proc2.setHarmonicOrder(2);
        proc2.setHarmonicLevel(1.0);

        BassExtensionProcessor proc4 = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc4.setCrossoverHz(80.0);
        proc4.setHarmonicOrder(4);
        proc4.setHarmonicLevel(1.0);

        float[][] output2 = new float[1][length];
        float[][] output4 = new float[1][length];
        proc2.process(input, output2, length);
        proc4.process(input, output4, length);

        double rms2 = rms(output2[0], length / 2, length);
        double rms4 = rms(output4[0], length / 2, length);

        // Higher harmonic order should produce more harmonic energy
        assertThat(rms4).isGreaterThan(rms2);
    }

    @Test
    void harmonicLevelShouldScaleHarmonicContent() {
        int length = 8192;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 60.0 * i / SAMPLE_RATE));
        }

        BassExtensionProcessor procLow = new BassExtensionProcessor(1, SAMPLE_RATE);
        procLow.setHarmonicLevel(0.2);

        BassExtensionProcessor procHigh = new BassExtensionProcessor(1, SAMPLE_RATE);
        procHigh.setHarmonicLevel(1.0);

        float[][] outputLow = new float[1][length];
        float[][] outputHigh = new float[1][length];
        procLow.process(input, outputLow, length);
        procHigh.process(input, outputHigh, length);

        double rmsLow = rms(outputLow[0], length / 2, length);
        double rmsHigh = rms(outputHigh[0], length / 2, length);

        // Higher harmonic level should produce more energy
        assertThat(rmsHigh).isGreaterThan(rmsLow);
    }

    @Test
    void zeroHarmonicLevelShouldPassthroughOriginal() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setHarmonicLevel(0.0);
        proc.setMix(1.0);

        int length = 4096;
        float[][] input = new float[1][length];
        float[][] output = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 60.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, length);

        // With zero harmonic level, output should equal input
        for (int i = 0; i < length; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldSupportAllHarmonicOrders() {
        for (int order = BassExtensionProcessor.MIN_HARMONIC_ORDER;
             order <= BassExtensionProcessor.MAX_HARMONIC_ORDER; order++) {
            BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
            proc.setHarmonicOrder(order);
            proc.setHarmonicLevel(1.0);

            assertThat(proc.getHarmonicOrder()).isEqualTo(order);

            int length = 4096;
            float[][] input = new float[1][length];
            float[][] output = new float[1][length];
            for (int i = 0; i < length; i++) {
                input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 60.0 * i / SAMPLE_RATE));
            }
            proc.process(input, output, length);

            double outputRms = rms(output[0], length / 2, length);
            assertThat(outputRms)
                    .as("Harmonic order %d should produce output", order)
                    .isGreaterThan(0.01);
        }
    }

    @Test
    void shouldResetState() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setHarmonicLevel(1.0);

        // Process loud bass signal to fill filter state
        float[][] input = new float[1][1024];
        float[][] output = new float[1][1024];
        for (int i = 0; i < 1024; i++) {
            input[0][i] = 0.9f;
        }
        proc.process(input, output, 1024);

        proc.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][512];
        float[][] resetOutput = new float[1][512];
        proc.process(silence, resetOutput, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        BassExtensionProcessor proc = new BassExtensionProcessor(2, SAMPLE_RATE);
        proc.setHarmonicLevel(1.0);

        int length = 8192;
        float[][] input = new float[2][length];
        float[][] output = new float[2][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 50.0 * i / SAMPLE_RATE));
            input[1][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 70.0 * i / SAMPLE_RATE));
        }
        proc.process(input, output, length);

        // Both channels should have signal
        assertThat(rms(output[0], length / 2, length)).isGreaterThan(0.01);
        assertThat(rms(output[1], length / 2, length)).isGreaterThan(0.01);
    }

    @Test
    void shouldSupportParameterChanges() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setCrossoverHz(60.0);
        proc.setHarmonicOrder(4);
        proc.setHarmonicLevel(0.8);
        proc.setMix(0.5);

        assertThat(proc.getCrossoverHz()).isEqualTo(60.0);
        assertThat(proc.getHarmonicOrder()).isEqualTo(4);
        assertThat(proc.getHarmonicLevel()).isEqualTo(0.8);
        assertThat(proc.getMix()).isEqualTo(0.5);
    }

    @Test
    void mixShouldBlendDryAndEnhanced() {
        int length = 8192;
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 60.0 * i / SAMPLE_RATE));
        }

        BassExtensionProcessor fullWet = new BassExtensionProcessor(1, SAMPLE_RATE);
        fullWet.setMix(1.0);
        fullWet.setHarmonicLevel(1.0);

        BassExtensionProcessor halfMix = new BassExtensionProcessor(1, SAMPLE_RATE);
        halfMix.setMix(0.5);
        halfMix.setHarmonicLevel(1.0);

        float[][] outputFull = new float[1][length];
        float[][] outputHalf = new float[1][length];
        fullWet.process(input, outputFull, length);
        halfMix.process(input, outputHalf, length);

        // Half mix should differ from full wet
        boolean differs = false;
        for (int i = length / 2; i < length; i++) {
            if (Math.abs(outputFull[0][i] - outputHalf[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void crossoverChangeShouldAffectProcessing() {
        int length = 8192;
        // 100 Hz tone — above 60 Hz crossover but below 120 Hz crossover
        float[][] input = new float[1][length];
        for (int i = 0; i < length; i++) {
            input[0][i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 100.0 * i / SAMPLE_RATE));
        }

        BassExtensionProcessor procLow = new BassExtensionProcessor(1, SAMPLE_RATE);
        procLow.setCrossoverHz(60.0);
        procLow.setHarmonicLevel(1.0);

        BassExtensionProcessor procHigh = new BassExtensionProcessor(1, SAMPLE_RATE);
        procHigh.setCrossoverHz(120.0);
        procHigh.setHarmonicLevel(1.0);

        float[][] outputLow = new float[1][length];
        float[][] outputHigh = new float[1][length];
        procLow.process(input, outputLow, length);
        procHigh.process(input, outputHigh, length);

        // Higher crossover should capture more of the 100 Hz tone and produce
        // more harmonic content
        double rmsLow = rms(outputLow[0], length / 2, length);
        double rmsHigh = rms(outputHigh[0], length / 2, length);
        assertThat(rmsHigh).isGreaterThan(rmsLow);
    }

    @Test
    void shouldAcceptBoundaryCrossoverValues() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setCrossoverHz(40.0);
        assertThat(proc.getCrossoverHz()).isEqualTo(40.0);
        proc.setCrossoverHz(120.0);
        assertThat(proc.getCrossoverHz()).isEqualTo(120.0);
    }

    @Test
    void shouldAcceptBoundaryHarmonicOrderValues() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setHarmonicOrder(2);
        assertThat(proc.getHarmonicOrder()).isEqualTo(2);
        proc.setHarmonicOrder(4);
        assertThat(proc.getHarmonicOrder()).isEqualTo(4);
    }

    @Test
    void shouldAcceptBoundaryMixAndLevelValues() {
        BassExtensionProcessor proc = new BassExtensionProcessor(1, SAMPLE_RATE);
        proc.setMix(0.0);
        assertThat(proc.getMix()).isEqualTo(0.0);
        proc.setMix(1.0);
        assertThat(proc.getMix()).isEqualTo(1.0);
        proc.setHarmonicLevel(0.0);
        assertThat(proc.getHarmonicLevel()).isEqualTo(0.0);
        proc.setHarmonicLevel(1.0);
        assertThat(proc.getHarmonicLevel()).isEqualTo(1.0);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
