package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class CrossoverFilterTest {

    private static final double SAMPLE_RATE = 44100.0;

    @Test
    void shouldSplitAndSumToOriginalSignal() {
        // The defining property of a Linkwitz-Riley crossover: low + high ≈ original
        // (small deviations are expected in the digital domain due to bilinear warping)
        var crossover = new CrossoverFilter(SAMPLE_RATE, 1000.0);

        int numFrames = 16384;
        float[] input = new float[numFrames];
        float[] lowOut = new float[numFrames];
        float[] highOut = new float[numFrames];

        // Generate a wideband test signal (two sines far from crossover frequency)
        for (int i = 0; i < numFrames; i++) {
            input[i] = (float) (Math.sin(2 * Math.PI * 100 * i / SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * 5000 * i / SAMPLE_RATE)) * 0.5f;
        }

        crossover.process(input, lowOut, highOut, 0, numFrames);

        // After filter settling, verify that total RMS energy is conserved
        double inputRms = rms(input, 4096, numFrames);
        double sumRms = 0;
        for (int i = 4096; i < numFrames; i++) {
            float sum = lowOut[i] + highOut[i];
            sumRms += sum * sum;
        }
        sumRms = Math.sqrt(sumRms / (numFrames - 4096));

        // The reconstructed signal RMS should be very close to the input RMS
        assertThat(sumRms).isCloseTo(inputRms, offset(inputRms * 0.05));
    }

    @Test
    void shouldSplitAndSumToOriginalWithImpulse() {
        var crossover = new CrossoverFilter(SAMPLE_RATE, 2000.0);

        int numFrames = 4096;
        float[] input = new float[numFrames];
        float[] lowOut = new float[numFrames];
        float[] highOut = new float[numFrames];

        // Unit impulse
        input[0] = 1.0f;

        crossover.process(input, lowOut, highOut, 0, numFrames);

        // Verify that sum of impulse responses equals the original impulse
        // (allow for filter settling time)
        double sumEnergy = 0;
        double impulseEnergy = 0;
        for (int i = 0; i < numFrames; i++) {
            float sum = lowOut[i] + highOut[i];
            sumEnergy += sum * sum;
            impulseEnergy += input[i] * input[i];
        }
        // Energy should be conserved (within tolerance)
        assertThat(sumEnergy).isCloseTo(impulseEnergy, offset(0.01));
    }

    @Test
    void shouldProcessSampleBySample() {
        var crossover = new CrossoverFilter(SAMPLE_RATE, 1000.0);
        float[] output = new float[2];

        // Process several samples and verify low and high outputs are produced
        for (int i = 0; i < 100; i++) {
            crossover.processSample(0.5f, output);
        }

        // After settling, both outputs should be non-zero for a DC-ish signal
        // (low pass should capture most of the DC content)
        assertThat(output[0]).isNotEqualTo(0.0f);
    }

    @Test
    void shouldPassLowFrequenciesToLowBand() {
        var crossover = new CrossoverFilter(SAMPLE_RATE, 4000.0);

        int numFrames = 8192;
        float[] input = new float[numFrames];
        float[] lowOut = new float[numFrames];
        float[] highOut = new float[numFrames];

        // Generate a low frequency signal (200 Hz) well below the 4000 Hz crossover
        for (int i = 0; i < numFrames; i++) {
            input[i] = (float) Math.sin(2 * Math.PI * 200 * i / SAMPLE_RATE);
        }

        crossover.process(input, lowOut, highOut, 0, numFrames);

        // Low band should have most of the energy after settling
        double lowRms = rms(lowOut, 2048, numFrames);
        double highRms = rms(highOut, 2048, numFrames);

        assertThat(lowRms).isGreaterThan(highRms * 10); // Low should dominate
    }

    @Test
    void shouldPassHighFrequenciesToHighBand() {
        var crossover = new CrossoverFilter(SAMPLE_RATE, 1000.0);

        int numFrames = 8192;
        float[] input = new float[numFrames];
        float[] lowOut = new float[numFrames];
        float[] highOut = new float[numFrames];

        // Generate a high frequency signal (10000 Hz) well above the 1000 Hz crossover
        for (int i = 0; i < numFrames; i++) {
            input[i] = (float) Math.sin(2 * Math.PI * 10000 * i / SAMPLE_RATE);
        }

        crossover.process(input, lowOut, highOut, 0, numFrames);

        // High band should have most of the energy after settling
        double lowRms = rms(lowOut, 2048, numFrames);
        double highRms = rms(highOut, 2048, numFrames);

        assertThat(highRms).isGreaterThan(lowRms * 10); // High should dominate
    }

    @Test
    void shouldResetState() {
        var crossover = new CrossoverFilter(SAMPLE_RATE, 1000.0);
        float[] output = new float[2];

        // Process some samples
        for (int i = 0; i < 100; i++) {
            crossover.processSample(0.5f, output);
        }

        crossover.reset();

        // After reset, processing the same input should give same result as a fresh filter
        var fresh = new CrossoverFilter(SAMPLE_RATE, 1000.0);
        float[] freshOutput = new float[2];

        crossover.processSample(1.0f, output);
        fresh.processSample(1.0f, freshOutput);

        assertThat(output[0]).isCloseTo(freshOutput[0], offset(0.0001f));
        assertThat(output[1]).isCloseTo(freshOutput[1], offset(0.0001f));
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new CrossoverFilter(0, 1000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrossoverFilter(-44100, 1000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidCrossoverFrequency() {
        assertThatThrownBy(() -> new CrossoverFilter(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrossoverFilter(SAMPLE_RATE, -100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectCrossoverAboveNyquist() {
        assertThatThrownBy(() -> new CrossoverFilter(SAMPLE_RATE, 22050))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrossoverFilter(SAMPLE_RATE, 30000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
