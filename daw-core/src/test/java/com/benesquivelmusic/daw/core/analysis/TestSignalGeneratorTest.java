package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class TestSignalGeneratorTest {

    private static final double SAMPLE_RATE = 44100.0;

    // ---------------------------------------------------------------
    // Logarithmic sweep tests
    // ---------------------------------------------------------------

    @Test
    void logarithmicSweepShouldProduceCorrectLength() {
        float[] sweep = TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 1.0, 20.0, 20000.0);

        assertThat(sweep.length).isEqualTo(44100);
    }

    @Test
    void logarithmicSweepShouldStayWithinUnitRange() {
        float[] sweep = TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 1.0, 20.0, 20000.0);

        for (float sample : sweep) {
            assertThat(sample).isBetween(-1.0f, 1.0f);
        }
    }

    @Test
    void logarithmicSweepShouldStartWithLowFrequency() {
        // A 20 Hz sweep at 44100 Hz should have a long initial period
        float[] sweep = TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 2.0, 20.0, 20000.0);

        // Count zero crossings in first 4410 samples (0.1 s) — should be low
        int zeroCrossingsEarly = countZeroCrossings(sweep, 0, 4410);
        // Count zero crossings in last 4410 samples — should be much higher
        int zeroCrossingsLate = countZeroCrossings(sweep, sweep.length - 4410, sweep.length);

        assertThat(zeroCrossingsLate).isGreaterThan(zeroCrossingsEarly);
    }

    @Test
    void logarithmicSweepWithFadeShouldStartAndEndNearZero() {
        float[] sweep = TestSignalGenerator.logarithmicSweep(
                SAMPLE_RATE, 1.0, 100.0, 10000.0, 0.05);

        // First and last samples should be near zero
        assertThat(Math.abs(sweep[0])).isCloseTo(0.0f, offset(0.01f));
        assertThat(Math.abs(sweep[sweep.length - 1])).isCloseTo(0.0f, offset(0.01f));
    }

    @Test
    void logarithmicSweepShouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> TestSignalGenerator.logarithmicSweep(0, 1.0, 20.0, 20000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.logarithmicSweep(-44100, 1.0, 20.0, 20000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logarithmicSweepShouldRejectInvalidDuration() {
        assertThatThrownBy(() -> TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 0, 20.0, 20000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, -1.0, 20.0, 20000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logarithmicSweepShouldRejectStartFrequencyAboveEnd() {
        assertThatThrownBy(() -> TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 1.0, 20000.0, 20.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logarithmicSweepShouldRejectEndFrequencyAboveNyquist() {
        assertThatThrownBy(() -> TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 1.0, 20.0, 30000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logarithmicSweepShouldRejectNegativeFade() {
        assertThatThrownBy(() ->
                TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 1.0, 20.0, 20000.0, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logarithmicSweepShouldRejectFadeExceedingDuration() {
        assertThatThrownBy(() ->
                TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 1.0, 20.0, 20000.0, 0.6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Linear sweep tests
    // ---------------------------------------------------------------

    @Test
    void linearSweepShouldProduceCorrectLength() {
        float[] sweep = TestSignalGenerator.linearSweep(SAMPLE_RATE, 1.0, 20.0, 20000.0);

        assertThat(sweep.length).isEqualTo(44100);
    }

    @Test
    void linearSweepShouldStayWithinUnitRange() {
        float[] sweep = TestSignalGenerator.linearSweep(SAMPLE_RATE, 1.0, 20.0, 20000.0);

        for (float sample : sweep) {
            assertThat(sample).isBetween(-1.0f, 1.0f);
        }
    }

    @Test
    void linearSweepShouldIncreaseFrequencyOverTime() {
        float[] sweep = TestSignalGenerator.linearSweep(SAMPLE_RATE, 2.0, 100.0, 10000.0);

        int zeroCrossingsEarly = countZeroCrossings(sweep, 0, 4410);
        int zeroCrossingsLate = countZeroCrossings(sweep, sweep.length - 4410, sweep.length);

        assertThat(zeroCrossingsLate).isGreaterThan(zeroCrossingsEarly);
    }

    @Test
    void linearSweepWithFadeShouldStartAndEndNearZero() {
        float[] sweep = TestSignalGenerator.linearSweep(
                SAMPLE_RATE, 1.0, 100.0, 10000.0, 0.05);

        assertThat(Math.abs(sweep[0])).isCloseTo(0.0f, offset(0.01f));
        assertThat(Math.abs(sweep[sweep.length - 1])).isCloseTo(0.0f, offset(0.01f));
    }

    @Test
    void linearSweepShouldRejectInvalidParameters() {
        assertThatThrownBy(() -> TestSignalGenerator.linearSweep(0, 1.0, 20.0, 20000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.linearSweep(SAMPLE_RATE, 1.0, 20000.0, 20.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // White noise tests
    // ---------------------------------------------------------------

    @Test
    void whiteNoiseShouldProduceCorrectLength() {
        float[] noise = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 1.0);

        assertThat(noise.length).isEqualTo(44100);
    }

    @Test
    void whiteNoiseShouldHaveZeroMean() {
        float[] noise = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 2.0);

        double mean = 0.0;
        for (float sample : noise) {
            mean += sample;
        }
        mean /= noise.length;

        // Mean should be close to zero for uniform distribution
        assertThat((float) mean).isCloseTo(0.0f, offset(0.02f));
    }

    @Test
    void whiteNoiseShouldBeReproducibleWithSeed() {
        float[] noise1 = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.1, 42);
        float[] noise2 = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.1, 42);

        assertThat(noise1).isEqualTo(noise2);
    }

    @Test
    void whiteNoiseShouldDifferWithDifferentSeeds() {
        float[] noise1 = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.1, 42);
        float[] noise2 = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.1, 99);

        assertThat(noise1).isNotEqualTo(noise2);
    }

    @Test
    void whiteNoiseShouldStayWithinRange() {
        float[] noise = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 1.0, 0);

        for (float sample : noise) {
            assertThat(sample).isBetween(-1.0f, 1.0f);
        }
    }

    @Test
    void whiteNoiseShouldRejectInvalidParameters() {
        assertThatThrownBy(() -> TestSignalGenerator.whiteNoise(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Pink noise tests
    // ---------------------------------------------------------------

    @Test
    void pinkNoiseShouldProduceCorrectLength() {
        float[] noise = TestSignalGenerator.pinkNoise(SAMPLE_RATE, 1.0);

        assertThat(noise.length).isEqualTo(44100);
    }

    @Test
    void pinkNoiseShouldBeNormalized() {
        float[] noise = TestSignalGenerator.pinkNoise(SAMPLE_RATE, 1.0, 42);

        float maxAbs = 0.0f;
        for (float sample : noise) {
            float abs = Math.abs(sample);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }

        // After normalization, peak should be at or very near 1.0
        assertThat(maxAbs).isCloseTo(1.0f, offset(0.001f));
    }

    @Test
    void pinkNoiseShouldHaveLessHighFrequencyEnergyThanWhite() {
        // Pink noise should have less energy in the upper frequency bins
        // compared to white noise. We verify this with a simple spectral check.
        float[] pink = TestSignalGenerator.pinkNoise(SAMPLE_RATE, 1.0, 0);
        float[] white = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 1.0, 0);

        // Compute high-frequency RMS (second half of spectrum via simple difference)
        double pinkHighRms = computeHighFrequencyRms(pink);
        double whiteHighRms = computeHighFrequencyRms(white);

        // Pink noise should have less high-frequency energy
        assertThat(pinkHighRms).isLessThan(whiteHighRms);
    }

    @Test
    void pinkNoiseShouldBeReproducibleWithSeed() {
        float[] noise1 = TestSignalGenerator.pinkNoise(SAMPLE_RATE, 0.1, 42);
        float[] noise2 = TestSignalGenerator.pinkNoise(SAMPLE_RATE, 0.1, 42);

        assertThat(noise1).isEqualTo(noise2);
    }

    @Test
    void pinkNoiseShouldRejectInvalidParameters() {
        assertThatThrownBy(() -> TestSignalGenerator.pinkNoise(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.pinkNoise(SAMPLE_RATE, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Impulse tests
    // ---------------------------------------------------------------

    @Test
    void impulseShouldProduceCorrectLength() {
        float[] impulse = TestSignalGenerator.impulse(SAMPLE_RATE, 1.0);

        assertThat(impulse.length).isEqualTo(44100);
    }

    @Test
    void impulseShouldHaveUnitValueAtPositionZero() {
        float[] impulse = TestSignalGenerator.impulse(SAMPLE_RATE, 0.1);

        assertThat(impulse[0]).isEqualTo(1.0f);

        // All other samples should be zero
        for (int i = 1; i < impulse.length; i++) {
            assertThat(impulse[i]).isEqualTo(0.0f);
        }
    }

    @Test
    void impulseShouldPlaceImpulseAtSpecifiedPosition() {
        int position = 100;
        float[] impulse = TestSignalGenerator.impulse(SAMPLE_RATE, 0.1, position);

        assertThat(impulse[position]).isEqualTo(1.0f);

        // Verify all other samples are zero
        for (int i = 0; i < impulse.length; i++) {
            if (i != position) {
                assertThat(impulse[i]).isEqualTo(0.0f);
            }
        }
    }

    @Test
    void impulseShouldRejectNegativePosition() {
        assertThatThrownBy(() -> TestSignalGenerator.impulse(SAMPLE_RATE, 0.1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void impulseShouldRejectPositionBeyondLength() {
        assertThatThrownBy(() -> TestSignalGenerator.impulse(SAMPLE_RATE, 0.1, 44100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void impulseShouldRejectInvalidParameters() {
        assertThatThrownBy(() -> TestSignalGenerator.impulse(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.impulse(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Windowed impulse tests
    // ---------------------------------------------------------------

    @Test
    void windowedImpulseShouldProduceCorrectLength() {
        float[] impulse = TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, 100, 21);

        assertThat(impulse.length).isEqualTo(4410);
    }

    @Test
    void windowedImpulseShouldPeakAtCenter() {
        int center = 200;
        float[] impulse = TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, center, 21);

        // Find peak position
        int peakIndex = 0;
        float peakValue = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < impulse.length; i++) {
            if (impulse[i] > peakValue) {
                peakValue = impulse[i];
                peakIndex = i;
            }
        }

        assertThat(peakIndex).isEqualTo(center);
        assertThat(peakValue).isCloseTo(1.0f, offset(0.01f));
    }

    @Test
    void windowedImpulseShouldBeZeroOutsideWindow() {
        int center = 200;
        int width = 21;
        float[] impulse = TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, center, width);

        // Samples well outside the window should be zero
        assertThat(impulse[0]).isEqualTo(0.0f);
        assertThat(impulse[impulse.length - 1]).isEqualTo(0.0f);
    }

    @Test
    void windowedImpulseShouldRejectInvalidWidth() {
        assertThatThrownBy(() ->
                TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, 100, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowedImpulseShouldRejectInvalidCenter() {
        assertThatThrownBy(() ->
                TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, -1, 21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                TestSignalGenerator.windowedImpulse(SAMPLE_RATE, 0.1, 44100, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Multi-tone tests
    // ---------------------------------------------------------------

    @Test
    void multiToneShouldProduceCorrectLength() {
        double[] frequencies = {440.0, 880.0, 1320.0};
        double[] amplitudes = {0.3, 0.3, 0.3};
        float[] signal = TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, frequencies, amplitudes);

        assertThat(signal.length).isEqualTo(44100);
    }

    @Test
    void multiToneShouldContainAllFrequencies() {
        double[] frequencies = {1000.0, 5000.0};
        double[] amplitudes = {0.5, 0.5};
        float[] signal = TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, frequencies, amplitudes);

        // Verify via FFT that peaks exist near both target frequencies
        int fftSize = 4096;
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            real[i] = signal[i];
            imag[i] = 0.0;
        }
        FftUtils.fft(real, imag);

        // Find bin for 1000 Hz
        int bin1000 = (int) Math.round(1000.0 * fftSize / SAMPLE_RATE);
        double mag1000 = Math.sqrt(real[bin1000] * real[bin1000] + imag[bin1000] * imag[bin1000]);

        // Find bin for 5000 Hz
        int bin5000 = (int) Math.round(5000.0 * fftSize / SAMPLE_RATE);
        double mag5000 = Math.sqrt(real[bin5000] * real[bin5000] + imag[bin5000] * imag[bin5000]);

        // Both peaks should have significant energy
        assertThat(mag1000).isGreaterThan(100.0);
        assertThat(mag5000).isGreaterThan(100.0);
    }

    @Test
    void multiToneEqualAmplitudeShouldSumNearUnity() {
        double[] frequencies = {440.0, 880.0, 1320.0, 1760.0};
        float[] signal = TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, frequencies);

        // With equal amplitude = 1/N, the peak should be at most 1.0
        float maxAbs = 0.0f;
        for (float sample : signal) {
            float abs = Math.abs(sample);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }
        assertThat(maxAbs).isLessThanOrEqualTo(1.0f);
    }

    @Test
    void multiToneShouldRejectNullFrequencies() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, null, new double[]{0.5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiToneShouldRejectEmptyFrequencies() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, new double[]{}, new double[]{}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiToneShouldRejectMismatchedArrayLengths() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0,
                        new double[]{440.0}, new double[]{0.5, 0.5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiToneShouldRejectFrequencyAboveNyquist() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0,
                        new double[]{30000.0}, new double[]{0.5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiToneShouldRejectNegativeAmplitude() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0,
                        new double[]{440.0}, new double[]{-0.5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiToneEqualAmplitudeShouldRejectNullFrequencies() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, (double[]) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiToneEqualAmplitudeShouldRejectEmptyFrequencies() {
        assertThatThrownBy(() ->
                TestSignalGenerator.multiTone(SAMPLE_RATE, 1.0, new double[]{}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Silence tests
    // ---------------------------------------------------------------

    @Test
    void silenceShouldProduceCorrectLength() {
        float[] silence = TestSignalGenerator.silence(SAMPLE_RATE, 1.0);

        assertThat(silence.length).isEqualTo(44100);
    }

    @Test
    void silenceShouldBeAllZeros() {
        float[] silence = TestSignalGenerator.silence(SAMPLE_RATE, 0.5);

        for (float sample : silence) {
            assertThat(sample).isEqualTo(0.0f);
        }
    }

    @Test
    void silenceShouldRejectInvalidParameters() {
        assertThatThrownBy(() -> TestSignalGenerator.silence(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSignalGenerator.silence(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Stereo conversion tests
    // ---------------------------------------------------------------

    @Test
    void toStereoShouldProduceTwoChannels() {
        float[] mono = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.1, 0);
        float[][] stereo = TestSignalGenerator.toStereo(mono);

        assertThat(stereo.length).isEqualTo(2);
        assertThat(stereo[0].length).isEqualTo(mono.length);
        assertThat(stereo[1].length).isEqualTo(mono.length);
    }

    @Test
    void toStereoChannelsShouldBeIdentical() {
        float[] mono = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.1, 0);
        float[][] stereo = TestSignalGenerator.toStereo(mono);

        assertThat(stereo[0]).isEqualTo(stereo[1]);
        assertThat(stereo[0]).isEqualTo(mono);
    }

    @Test
    void toStereoShouldNotShareArrayReferences() {
        float[] mono = {0.5f, -0.5f, 0.0f};
        float[][] stereo = TestSignalGenerator.toStereo(mono);

        // Modifying the original should not affect the stereo output
        mono[0] = 0.0f;
        assertThat(stereo[0][0]).isEqualTo(0.5f);
        assertThat(stereo[1][0]).isEqualTo(0.5f);

        // Modifying one channel should not affect the other
        stereo[0][1] = 1.0f;
        assertThat(stereo[1][1]).isEqualTo(-0.5f);
    }

    @Test
    void toStereoShouldRejectNull() {
        assertThatThrownBy(() -> TestSignalGenerator.toStereo(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // Cross-signal integration tests
    // ---------------------------------------------------------------

    @Test
    void sweepsShouldHaveNonZeroRms() {
        float[] logSweep = TestSignalGenerator.logarithmicSweep(SAMPLE_RATE, 0.5, 100.0, 10000.0);
        float[] linSweep = TestSignalGenerator.linearSweep(SAMPLE_RATE, 0.5, 100.0, 10000.0);

        assertThat(rms(logSweep)).isGreaterThan(0.1f);
        assertThat(rms(linSweep)).isGreaterThan(0.1f);
    }

    @Test
    void noiseSignalsShouldHaveNonZeroRms() {
        float[] white = TestSignalGenerator.whiteNoise(SAMPLE_RATE, 0.5, 0);
        float[] pink = TestSignalGenerator.pinkNoise(SAMPLE_RATE, 0.5, 0);

        assertThat(rms(white)).isGreaterThan(0.1f);
        assertThat(rms(pink)).isGreaterThan(0.1f);
    }

    @Test
    void impulseShouldHaveCorrectRms() {
        float[] impulse = TestSignalGenerator.impulse(SAMPLE_RATE, 0.1);
        // RMS of a single unit impulse in N samples = 1/sqrt(N)
        double expectedRms = 1.0 / Math.sqrt(impulse.length);
        assertThat(rms(impulse)).isCloseTo((float) expectedRms, offset(0.0001f));
    }

    @Test
    void silenceShouldHaveZeroRms() {
        float[] silence = TestSignalGenerator.silence(SAMPLE_RATE, 0.1);

        assertThat(rms(silence)).isEqualTo(0.0f);
    }

    @Test
    void differentSampleRatesShouldProduceDifferentLengths() {
        float[] at44100 = TestSignalGenerator.silence(44100.0, 1.0);
        float[] at48000 = TestSignalGenerator.silence(48000.0, 1.0);
        float[] at96000 = TestSignalGenerator.silence(96000.0, 1.0);

        assertThat(at44100.length).isEqualTo(44100);
        assertThat(at48000.length).isEqualTo(48000);
        assertThat(at96000.length).isEqualTo(96000);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static float rms(float[] samples) {
        double sumSquares = 0.0;
        for (float sample : samples) {
            sumSquares += (double) sample * sample;
        }
        return (float) Math.sqrt(sumSquares / samples.length);
    }

    private static int countZeroCrossings(float[] samples, int start, int end) {
        int crossings = 0;
        for (int i = start + 1; i < end; i++) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) || (samples[i - 1] < 0 && samples[i] >= 0)) {
                crossings++;
            }
        }
        return crossings;
    }

    /**
     * Computes a simple high-frequency energy estimate by differencing
     * adjacent samples (approximating a first-order high-pass filter)
     * and taking the RMS of the result.
     */
    private static double computeHighFrequencyRms(float[] samples) {
        double sumSquares = 0.0;
        for (int i = 1; i < samples.length; i++) {
            double diff = samples[i] - samples[i - 1];
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares / (samples.length - 1));
    }
}
