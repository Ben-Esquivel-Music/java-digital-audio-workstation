package com.benesquivelmusic.daw.core.spatial;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AmbienceUpmixerTest {

    private static final double SAMPLE_RATE = 44100.0;
    private static final double TOLERANCE = 1e-3;
    private static final int NUM_FRAMES = 512;

    // ---- Construction ----

    @Test
    void shouldCreateWithDefaultLayout() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        assertThat(upmixer.getInputChannelCount()).isEqualTo(2);
        assertThat(upmixer.getOutputChannelCount()).isEqualTo(12); // 7.1.4
        assertThat(upmixer.getTargetLayout()).isEqualTo(SpeakerLayout.LAYOUT_7_1_4);
        assertThat(upmixer.getAmbientExtraction()).isCloseTo(0.5, within(1e-10));
        assertThat(upmixer.getHeightLevel()).isCloseTo(0.7, within(1e-10));
        assertThat(upmixer.getDecorrelationAmount()).isCloseTo(0.8, within(1e-10));
        assertThat(upmixer.getActiveHeightCount()).isEqualTo(4);
    }

    @Test
    void shouldCreateWithCustomLayout() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE, SpeakerLayout.LAYOUT_5_1_4);
        assertThat(upmixer.getOutputChannelCount()).isEqualTo(10);
        assertThat(upmixer.getActiveHeightCount()).isEqualTo(4);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new AmbienceUpmixer(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
        assertThatThrownBy(() -> new AmbienceUpmixer(-44100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNullLayout() {
        assertThatThrownBy(() -> new AmbienceUpmixer(SAMPLE_RATE, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Parameter Validation ----

    @Test
    void shouldRejectInvalidAmbientExtraction() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        assertThatThrownBy(() -> upmixer.setAmbientExtraction(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upmixer.setAmbientExtraction(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptBoundaryAmbientExtraction() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setAmbientExtraction(0.0);
        assertThat(upmixer.getAmbientExtraction()).isEqualTo(0.0);
        upmixer.setAmbientExtraction(1.0);
        assertThat(upmixer.getAmbientExtraction()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidHeightLevel() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        assertThatThrownBy(() -> upmixer.setHeightLevel(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upmixer.setHeightLevel(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDecorrelation() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        assertThatThrownBy(() -> upmixer.setDecorrelationAmount(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upmixer.setDecorrelationAmount(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidPbaFrequencies() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);

        // Empty
        assertThatThrownBy(() -> upmixer.setPbaFrequencies(new double[0]))
                .isInstanceOf(IllegalArgumentException.class);

        // Out of range
        assertThatThrownBy(() -> upmixer.setPbaFrequencies(new double[]{0.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upmixer.setPbaFrequencies(new double[]{SAMPLE_RATE}))
                .isInstanceOf(IllegalArgumentException.class);

        // Not ascending
        assertThatThrownBy(() -> upmixer.setPbaFrequencies(new double[]{2000, 1000}))
                .isInstanceOf(IllegalArgumentException.class);

        // Null
        assertThatThrownBy(() -> upmixer.setPbaFrequencies(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidPbaWeights() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);

        // Wrong length (default has 3 crossovers → 4 bands)
        assertThatThrownBy(() -> upmixer.setPbaWeights(new double[]{0.5, 0.5}))
                .isInstanceOf(IllegalArgumentException.class);

        // Out of range
        assertThatThrownBy(() -> upmixer.setPbaWeights(new double[]{-0.1, 0.3, 0.7, 1.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> upmixer.setPbaWeights(new double[]{0.0, 0.3, 0.7, 1.1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidPbaWeights() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        double[] weights = {0.0, 0.5, 0.8, 1.0};
        upmixer.setPbaWeights(weights);
        assertThat(upmixer.getPbaWeights()).containsExactly(weights);
    }

    @Test
    void shouldReturnCopiesOfArrays() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        double[] freqs = upmixer.getPbaFrequencies();
        freqs[0] = 9999.0; // mutate the copy
        assertThat(upmixer.getPbaFrequencies()[0]).isNotEqualTo(9999.0);

        double[] weights = upmixer.getPbaWeights();
        weights[0] = 9999.0;
        assertThat(upmixer.getPbaWeights()[0]).isNotEqualTo(9999.0);
    }

    // ---- Layout Change ----

    @Test
    void shouldUpdateTargetLayout() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setTargetLayout(SpeakerLayout.LAYOUT_5_1_4);
        assertThat(upmixer.getTargetLayout()).isEqualTo(SpeakerLayout.LAYOUT_5_1_4);
        assertThat(upmixer.getOutputChannelCount()).isEqualTo(10);
        assertThat(upmixer.getActiveHeightCount()).isEqualTo(4);
    }

    @Test
    void shouldHandleLayoutWithoutHeightChannels() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE, SpeakerLayout.LAYOUT_STEREO);
        assertThat(upmixer.getActiveHeightCount()).isEqualTo(0);
        assertThat(upmixer.getOutputChannelCount()).isEqualTo(2);
    }

    // ---- Processing: Pass-Through ----

    @Test
    void shouldPassThroughAtZeroExtraction() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setAmbientExtraction(0.0);

        float[][] input = wideStereoSignal(440.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];
        upmixer.process(input, output, NUM_FRAMES);

        // L (index 0) and R (index 1) should match original input
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat((double) output[0][i]).as("L frame %d", i)
                    .isCloseTo(input[0][i], within(TOLERANCE));
            assertThat((double) output[1][i]).as("R frame %d", i)
                    .isCloseTo(input[1][i], within(TOLERANCE));
        }

        // Height channels (8-11) should be silent
        for (int ch = 8; ch < 12; ch++) {
            for (int i = 0; i < NUM_FRAMES; i++) {
                assertThat((double) output[ch][i]).as("ch=%d frame=%d", ch, i)
                        .isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    @Test
    void shouldProduceSilenceFromSilence() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        float[][] input = new float[2][NUM_FRAMES];
        float[][] output = new float[12][NUM_FRAMES];
        upmixer.process(input, output, NUM_FRAMES);

        for (float[] ch : output) {
            for (float v : ch) {
                assertThat((double) v).isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    // ---- Processing: Height Channel Content ----

    @Test
    void shouldRouteAmbientToHeightChannels() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setAmbientExtraction(0.8);
        upmixer.setHeightLevel(1.0);

        // Create a wide stereo signal (large side component)
        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];

        // Process multiple blocks to let filters settle
        for (int block = 0; block < 4; block++) {
            upmixer.process(input, output, NUM_FRAMES);
        }

        // Height channels (LTF=8, RTF=9, LTR=10, RTR=11) should have energy
        double heightEnergy = channelEnergy(output, 8, 12, NUM_FRAMES);
        assertThat(heightEnergy).as("height channel energy").isGreaterThan(0.001);
    }

    @Test
    void shouldProduceMoreHeightContentWithHigherExtraction() {
        // Lower extraction
        AmbienceUpmixer upmixerLow = new AmbienceUpmixer(SAMPLE_RATE);
        upmixerLow.setAmbientExtraction(0.2);
        upmixerLow.setHeightLevel(1.0);

        // Higher extraction
        AmbienceUpmixer upmixerHigh = new AmbienceUpmixer(SAMPLE_RATE);
        upmixerHigh.setAmbientExtraction(0.8);
        upmixerHigh.setHeightLevel(1.0);

        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] outputLow = new float[12][NUM_FRAMES];
        float[][] outputHigh = new float[12][NUM_FRAMES];

        // Settle filters
        for (int block = 0; block < 4; block++) {
            upmixerLow.process(input, outputLow, NUM_FRAMES);
            upmixerHigh.process(input, outputHigh, NUM_FRAMES);
        }

        double heightEnergyLow = channelEnergy(outputLow, 8, 12, NUM_FRAMES);
        double heightEnergyHigh = channelEnergy(outputHigh, 8, 12, NUM_FRAMES);

        assertThat(heightEnergyHigh)
                .as("higher extraction → more height content")
                .isGreaterThan(heightEnergyLow);
    }

    @Test
    void shouldScaleHeightOutputByHeightLevel() {
        AmbienceUpmixer upmixerQuiet = new AmbienceUpmixer(SAMPLE_RATE);
        upmixerQuiet.setAmbientExtraction(0.8);
        upmixerQuiet.setHeightLevel(0.3);

        AmbienceUpmixer upmixerLoud = new AmbienceUpmixer(SAMPLE_RATE);
        upmixerLoud.setAmbientExtraction(0.8);
        upmixerLoud.setHeightLevel(1.0);

        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] outputQuiet = new float[12][NUM_FRAMES];
        float[][] outputLoud = new float[12][NUM_FRAMES];

        for (int block = 0; block < 4; block++) {
            upmixerQuiet.process(input, outputQuiet, NUM_FRAMES);
            upmixerLoud.process(input, outputLoud, NUM_FRAMES);
        }

        double heightEnergyQuiet = channelEnergy(outputQuiet, 8, 12, NUM_FRAMES);
        double heightEnergyLoud = channelEnergy(outputLoud, 8, 12, NUM_FRAMES);

        assertThat(heightEnergyLoud)
                .as("higher height level → more height energy")
                .isGreaterThan(heightEnergyQuiet);
    }

    // ---- Processing: PBA Weighting ----

    @Test
    void shouldRouteHighFrequenciesToHeightsMoreThanLow() {
        // High frequencies should produce more height content than low frequencies
        // because PBA weights increase with band index
        AmbienceUpmixer upmixerLow = new AmbienceUpmixer(SAMPLE_RATE);
        upmixerLow.setAmbientExtraction(1.0);
        upmixerLow.setHeightLevel(1.0);
        upmixerLow.setDecorrelationAmount(0.0);

        AmbienceUpmixer upmixerHigh = new AmbienceUpmixer(SAMPLE_RATE);
        upmixerHigh.setAmbientExtraction(1.0);
        upmixerHigh.setHeightLevel(1.0);
        upmixerHigh.setDecorrelationAmount(0.0);

        // Low frequency stereo signal (200 Hz — below first crossover at 500 Hz)
        float[][] inputLow = wideStereoSignal(200.0, NUM_FRAMES);
        // High frequency stereo signal (10 kHz — above last crossover at 8000 Hz)
        float[][] inputHigh = wideStereoSignal(10000.0, NUM_FRAMES);

        float[][] outputLow = new float[12][NUM_FRAMES];
        float[][] outputHigh = new float[12][NUM_FRAMES];

        // Settle filters
        for (int block = 0; block < 8; block++) {
            upmixerLow.process(inputLow, outputLow, NUM_FRAMES);
            upmixerHigh.process(inputHigh, outputHigh, NUM_FRAMES);
        }

        double heightEnergyLowFreq = channelEnergy(outputLow, 8, 12, NUM_FRAMES);
        double heightEnergyHighFreq = channelEnergy(outputHigh, 8, 12, NUM_FRAMES);

        assertThat(heightEnergyHighFreq)
                .as("high freq content should route more to heights (PBA)")
                .isGreaterThan(heightEnergyLowFreq);
    }

    // ---- Processing: Decorrelation ----

    @Test
    void shouldDecorrelateHeightChannels() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setAmbientExtraction(1.0);
        upmixer.setHeightLevel(1.0);
        upmixer.setDecorrelationAmount(1.0);

        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];

        // Settle filters
        for (int block = 0; block < 4; block++) {
            upmixer.process(input, output, NUM_FRAMES);
        }

        // LTF (8) and LTR (10) should not be perfectly correlated
        double correlation = normalizedCrossCorrelation(output[8], output[10], NUM_FRAMES);
        assertThat(Math.abs(correlation))
                .as("front/rear height channels should be decorrelated")
                .isLessThan(0.99);
    }

    // ---- Processing: Layout Without Height Channels ----

    @Test
    void shouldPassThroughStereoWithNoHeightChannels() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(
                SAMPLE_RATE, SpeakerLayout.LAYOUT_STEREO);
        upmixer.setAmbientExtraction(0.5);

        float[][] input = wideStereoSignal(440.0, NUM_FRAMES);
        float[][] output = new float[2][NUM_FRAMES];
        upmixer.process(input, output, NUM_FRAMES);

        // With a stereo-only layout, there are no height channels to synthesize,
        // so ambient extraction should not alter the original L/R program.
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat((double) output[0][i])
                    .as("left output should pass through unchanged at frame %d", i)
                    .isCloseTo(input[0][i], within(TOLERANCE));
            assertThat((double) output[1][i])
                    .as("right output should pass through unchanged at frame %d", i)
                    .isCloseTo(input[1][i], within(TOLERANCE));
        }
    }

    // ---- Processing: Zero Height Level ----

    @Test
    void shouldProduceSilentHeightsAtZeroHeightLevel() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setAmbientExtraction(1.0);
        upmixer.setHeightLevel(0.0);

        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];
        upmixer.process(input, output, NUM_FRAMES);

        double heightEnergy = channelEnergy(output, 8, 12, NUM_FRAMES);
        assertThat(heightEnergy).as("height channels at zero level")
                .isCloseTo(0.0, within(TOLERANCE));
    }

    // ---- Reset ----

    @Test
    void shouldResetState() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);

        // Process some data to change internal state
        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];
        upmixer.process(input, output, NUM_FRAMES);

        upmixer.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[2][NUM_FRAMES];
        float[][] resetOutput = new float[12][NUM_FRAMES];
        upmixer.process(silence, resetOutput, NUM_FRAMES);

        for (int ch = 0; ch < 12; ch++) {
            for (int i = 0; i < NUM_FRAMES; i++) {
                assertThat((double) resetOutput[ch][i]).as("ch=%d frame=%d", ch, i)
                        .isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    // ---- PBA Frequency Configuration ----

    @Test
    void shouldAcceptCustomPbaFrequencies() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        double[] freqs = {1000.0, 4000.0};
        upmixer.setPbaFrequencies(freqs);
        assertThat(upmixer.getPbaFrequencies()).containsExactly(freqs);
        // 2 crossovers → 3 bands → 3 weights
        assertThat(upmixer.getPbaWeights()).hasSize(3);
    }

    @Test
    void shouldProcessWithSingleCrossover() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setPbaFrequencies(new double[]{2000.0});
        upmixer.setAmbientExtraction(0.8);
        upmixer.setHeightLevel(1.0);

        float[][] input = wideStereoSignal(4000.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];

        for (int block = 0; block < 4; block++) {
            upmixer.process(input, output, NUM_FRAMES);
        }

        double heightEnergy = channelEnergy(output, 8, 12, NUM_FRAMES);
        assertThat(heightEnergy).isGreaterThan(0.001);
    }

    // ---- Mono Input Fallback ----

    @Test
    void shouldHandleEmptyInput() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        float[][] emptyInput = new float[0][];
        float[][] output = new float[12][NUM_FRAMES];

        // Should not throw — gracefully returns with zeroed output
        upmixer.process(emptyInput, output, NUM_FRAMES);

        for (int ch = 0; ch < 12; ch++) {
            for (int i = 0; i < NUM_FRAMES; i++) {
                assertThat((double) output[ch][i]).as("ch=%d frame=%d", ch, i)
                        .isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    @Test
    void shouldHandleMonoInput() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        float[][] monoInput = {constantBuffer(0.5f, NUM_FRAMES)};
        float[][] output = new float[12][NUM_FRAMES];

        // Should not throw
        upmixer.process(monoInput, output, NUM_FRAMES);

        // First output channel should have signal
        double energy = channelEnergy(output, 0, 1, NUM_FRAMES);
        assertThat(energy).isGreaterThan(0.0);
    }

    // ---- Direct/Ambient Energy Balance ----

    @Test
    void shouldReduceAmbientInDirectOutputAtFullExtraction() {
        AmbienceUpmixer upmixer = new AmbienceUpmixer(SAMPLE_RATE);
        upmixer.setAmbientExtraction(1.0);

        // Wide stereo signal has a large side component
        float[][] input = wideStereoSignal(1000.0, NUM_FRAMES);
        float[][] output = new float[12][NUM_FRAMES];
        upmixer.process(input, output, NUM_FRAMES);

        // At full extraction, L and R should be identical (pure mid = mono)
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat((double) output[0][i]).as("L==R at full extraction, frame %d", i)
                    .isCloseTo(output[1][i], within(TOLERANCE));
        }
    }

    // ---- Helpers ----

    private static float[] constantBuffer(float value, int size) {
        float[] buffer = new float[size];
        Arrays.fill(buffer, value);
        return buffer;
    }

    private static float[][] stereoTestSignal(double freqHz, int numFrames) {
        float[][] signal = new float[2][numFrames];
        for (int i = 0; i < numFrames; i++) {
            float sample = (float) (0.5 * Math.sin(
                    2.0 * Math.PI * freqHz * i / SAMPLE_RATE));
            signal[0][i] = sample;
            signal[1][i] = sample;
        }
        return signal;
    }

    /**
     * Creates a wide stereo signal with a large side (ambient) component.
     * L and R have different phases → significant L−R difference.
     */
    private static float[][] wideStereoSignal(double freqHz, int numFrames) {
        float[][] signal = new float[2][numFrames];
        for (int i = 0; i < numFrames; i++) {
            double phase = 2.0 * Math.PI * freqHz * i / SAMPLE_RATE;
            signal[0][i] = (float) (0.5 * Math.sin(phase));
            signal[1][i] = (float) (0.5 * Math.sin(phase + Math.PI * 0.5)); // 90° phase offset
        }
        return signal;
    }

    private static double channelEnergy(float[][] buffer, int startCh, int endCh,
                                        int numFrames) {
        double energy = 0;
        for (int ch = startCh; ch < endCh; ch++) {
            for (int i = 0; i < numFrames; i++) {
                energy += buffer[ch][i] * buffer[ch][i];
            }
        }
        return energy;
    }

    private static double normalizedCrossCorrelation(float[] a, float[] b, int length) {
        double sumAB = 0, sumAA = 0, sumBB = 0;
        for (int i = 0; i < length; i++) {
            sumAB += a[i] * b[i];
            sumAA += a[i] * a[i];
            sumBB += b[i] * b[i];
        }
        double denom = Math.sqrt(sumAA * sumBB);
        return denom > 1e-15 ? sumAB / denom : 0.0;
    }
}
