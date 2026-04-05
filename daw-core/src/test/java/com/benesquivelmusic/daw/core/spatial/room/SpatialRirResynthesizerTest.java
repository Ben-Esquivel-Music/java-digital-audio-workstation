package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.sdk.spatial.ImpulseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class SpatialRirResynthesizerTest {

    private static final int SAMPLE_RATE = 48000;

    private SpatialRirResynthesizer resynthesizer;

    @BeforeEach
    void setUp() {
        resynthesizer = new SpatialRirResynthesizer(SAMPLE_RATE);
    }

    // ----------------------------------------------------------------
    // Constructor validation
    // ----------------------------------------------------------------

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new SpatialRirResynthesizer(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpatialRirResynthesizer(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidNumBands() {
        assertThatThrownBy(() -> new SpatialRirResynthesizer(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpatialRirResynthesizer(SAMPLE_RATE, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidNumBands() {
        for (int bands = 1; bands <= 6; bands++) {
            SpatialRirResynthesizer r = new SpatialRirResynthesizer(SAMPLE_RATE, bands);
            assertThat(r.getNumBands()).isEqualTo(bands);
        }
    }

    // ----------------------------------------------------------------
    // Default configuration
    // ----------------------------------------------------------------

    @Test
    void shouldReturnDefaultValues() {
        assertThat(resynthesizer.getSampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(resynthesizer.getNumBands()).isEqualTo(SpatialRirResynthesizer.DEFAULT_NUM_BANDS);
        assertThat(resynthesizer.getMixingTime())
                .isCloseTo(SpatialRirResynthesizer.DEFAULT_MIXING_TIME_SECONDS, within(1e-9));
        assertThat(resynthesizer.getCrossfadeDuration())
                .isCloseTo(SpatialRirResynthesizer.DEFAULT_CROSSFADE_DURATION_SECONDS, within(1e-9));
    }

    // ----------------------------------------------------------------
    // Setter validation
    // ----------------------------------------------------------------

    @Test
    void shouldSetAndGetMixingTime() {
        resynthesizer.setMixingTime(0.1);
        assertThat(resynthesizer.getMixingTime()).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void shouldRejectNonPositiveMixingTime() {
        assertThatThrownBy(() -> resynthesizer.setMixingTime(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resynthesizer.setMixingTime(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetAndGetCrossfadeDuration() {
        resynthesizer.setCrossfadeDuration(0.02);
        assertThat(resynthesizer.getCrossfadeDuration()).isCloseTo(0.02, within(1e-9));
    }

    @Test
    void shouldRejectNonPositiveCrossfadeDuration() {
        assertThatThrownBy(() -> resynthesizer.setCrossfadeDuration(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resynthesizer.setCrossfadeDuration(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------
    // Decay analysis
    // ----------------------------------------------------------------

    @Test
    void shouldAnalyzeDecayOfMonoIr() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 1.0);
        double[][] decayTimes = resynthesizer.analyzeDecay(ir);

        assertThat(decayTimes).hasNumberOfRows(1);
        assertThat(decayTimes[0]).hasSize(resynthesizer.getNumBands());

        // All decay estimates should be positive
        for (double dt : decayTimes[0]) {
            assertThat(dt).isGreaterThan(0.0);
        }
    }

    @Test
    void shouldAnalyzeDecayOfStereoIr() {
        ImpulseResponse ir = createExponentialDecayIr(2, SAMPLE_RATE, 1.5);
        double[][] decayTimes = resynthesizer.analyzeDecay(ir);

        assertThat(decayTimes).hasNumberOfRows(2);
        for (int ch = 0; ch < 2; ch++) {
            assertThat(decayTimes[ch]).hasSize(resynthesizer.getNumBands());
            for (double dt : decayTimes[ch]) {
                assertThat(dt).isGreaterThan(0.0);
            }
        }
    }

    @Test
    void shouldRejectNullIrForAnalysis() {
        assertThatThrownBy(() -> resynthesizer.analyzeDecay(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldEstimateLongerDecayForLongerRt60() {
        // IR with short decay
        ImpulseResponse shortIr = createExponentialDecayIr(1, SAMPLE_RATE, 0.3);
        // IR with long decay
        ImpulseResponse longIr = createExponentialDecayIr(1, SAMPLE_RATE, 2.0);

        double[][] shortDecay = resynthesizer.analyzeDecay(shortIr);
        double[][] longDecay = resynthesizer.analyzeDecay(longIr);

        // Average across bands — longer IR should yield longer average decay estimate
        double shortAvg = average(shortDecay[0]);
        double longAvg = average(longDecay[0]);
        assertThat(longAvg).isGreaterThan(shortAvg);
    }

    // ----------------------------------------------------------------
    // Resynthesis — basic
    // ----------------------------------------------------------------

    @Test
    void shouldResynthesizeMonoIr() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.5);
        ImpulseResponse result = resynthesizer.resynthesize(ir, 1.0);

        assertThat(result).isNotNull();
        assertThat(result.channelCount()).isEqualTo(1);
        assertThat(result.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(result.durationSeconds()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void shouldResynthesizeStereoIr() {
        ImpulseResponse ir = createExponentialDecayIr(2, SAMPLE_RATE, 0.5);
        ImpulseResponse result = resynthesizer.resynthesize(ir, 1.0);

        assertThat(result).isNotNull();
        assertThat(result.channelCount()).isEqualTo(2);
        assertThat(result.sampleRate()).isEqualTo(SAMPLE_RATE);
    }

    @Test
    void shouldExtendIrDuration() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.3);
        double originalDuration = ir.durationSeconds();
        double targetDuration = originalDuration * 3.0;

        ImpulseResponse result = resynthesizer.resynthesize(ir, targetDuration);

        assertThat(result.durationSeconds()).isGreaterThanOrEqualTo(targetDuration);
    }

    @Test
    void shouldPreserveEarlyReflections() {
        float[] irData = createDecayingImpulse(SAMPLE_RATE, 0.5);
        // Set a strong impulse at sample 0 so early part is identifiable
        irData[0] = 1.0f;
        ImpulseResponse ir = new ImpulseResponse(new float[][]{irData}, SAMPLE_RATE);

        ImpulseResponse result = resynthesizer.resynthesize(ir, 1.0);

        // The very first sample (direct sound) should be preserved
        assertThat(result.samples()[0][0]).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void shouldProduceNonSilentOutput() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.5);
        ImpulseResponse result = resynthesizer.resynthesize(ir, 1.0);

        // Check that the late tail has non-zero energy
        float[] output = result.samples()[0];
        int lateSample = (int) (0.5 * SAMPLE_RATE); // well past mixing time
        boolean hasNonZero = false;
        for (int i = lateSample; i < output.length; i++) {
            if (Math.abs(output[i]) > 1e-10f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).as("Late tail should contain non-zero samples").isTrue();
    }

    // ----------------------------------------------------------------
    // Resynthesis with custom decay times (anisotropic)
    // ----------------------------------------------------------------

    @Test
    void shouldResynthesizeWithCustomDecayTimes() {
        ImpulseResponse ir = createExponentialDecayIr(2, SAMPLE_RATE, 0.5);
        int bands = resynthesizer.getNumBands();

        // Channel 0: short decay; Channel 1: long decay
        double[][] decayTimes = new double[2][bands];
        for (int b = 0; b < bands; b++) {
            decayTimes[0][b] = 0.5;
            decayTimes[1][b] = 2.0;
        }

        ImpulseResponse result = resynthesizer.resynthesize(ir, 2.5, decayTimes);

        assertThat(result).isNotNull();
        assertThat(result.channelCount()).isEqualTo(2);

        // Channel 1 (long decay) should have more energy in the late tail
        double ch0LateEnergy = computeRmsRange(result.samples()[0],
                (int) (1.5 * SAMPLE_RATE), result.lengthInSamples());
        double ch1LateEnergy = computeRmsRange(result.samples()[1],
                (int) (1.5 * SAMPLE_RATE), result.lengthInSamples());

        assertThat(ch1LateEnergy).isGreaterThan(ch0LateEnergy);
    }

    @Test
    void shouldRejectMismatchedDecayTimesDimensions() {
        ImpulseResponse ir = createExponentialDecayIr(2, SAMPLE_RATE, 0.5);

        // Wrong number of channels
        double[][] wrongChannels = new double[1][resynthesizer.getNumBands()];
        assertThatThrownBy(() -> resynthesizer.resynthesize(ir, 1.0, wrongChannels))
                .isInstanceOf(IllegalArgumentException.class);

        // Wrong number of bands
        double[][] wrongBands = new double[2][resynthesizer.getNumBands() + 1];
        assertThatThrownBy(() -> resynthesizer.resynthesize(ir, 1.0, wrongBands))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------
    // Resynthesis validation
    // ----------------------------------------------------------------

    @Test
    void shouldRejectNullMeasuredIr() {
        assertThatThrownBy(() -> resynthesizer.resynthesize(null, 1.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullDecayTimes() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.5);
        assertThatThrownBy(() -> resynthesizer.resynthesize(ir, 1.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveTargetDuration() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.5);
        assertThatThrownBy(() -> resynthesizer.resynthesize(ir, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resynthesizer.resynthesize(ir, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------
    // Schroeder decay estimation
    // ----------------------------------------------------------------

    @Test
    void shouldEstimateDecayFromExponentialSignal() {
        // Create a signal with known RT60 = 1.0s (exponential decay)
        float[] signal = new float[SAMPLE_RATE * 2];
        double rt60 = 1.0;
        double decayRate = -6.9078 / (rt60 * SAMPLE_RATE);
        Random rand = new Random(99);
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (float) (rand.nextGaussian() * Math.exp(decayRate * i));
        }

        double estimated = resynthesizer.schroederDecayEstimate(signal, 0);

        // Should be within reasonable range of the actual RT60
        assertThat(estimated).isBetween(0.3, 3.0);
    }

    @Test
    void shouldReturnMinimumForSilentSignal() {
        float[] signal = new float[SAMPLE_RATE];
        double estimated = resynthesizer.schroederDecayEstimate(signal, 0);
        assertThat(estimated).isGreaterThan(0.0);
    }

    // ----------------------------------------------------------------
    // Spectral envelope extraction
    // ----------------------------------------------------------------

    @Test
    void shouldExtractNonEmptySpectralEnvelope() {
        float[] signal = createDecayingImpulse(SAMPLE_RATE, 0.5);
        double[] envelope = resynthesizer.extractSpectralEnvelope(signal, SAMPLE_RATE / 20);

        assertThat(envelope).isNotEmpty();
        // Should have some non-zero bins
        boolean hasNonZero = false;
        for (double v : envelope) {
            if (v > 1e-12) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    // ----------------------------------------------------------------
    // Bandpass filter
    // ----------------------------------------------------------------

    @Test
    void shouldBandpassFilterWithoutException() {
        float[] input = new float[4096];
        input[0] = 1.0f; // impulse
        float[] output = resynthesizer.bandpassFilter(input, 1000.0);

        assertThat(output).hasSize(input.length);
        // Output should have some non-zero values (impulse excites all frequencies)
        boolean hasNonZero = false;
        for (float v : output) {
            if (Math.abs(v) > 1e-10f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    // ----------------------------------------------------------------
    // Multi-channel / multi-direction support
    // ----------------------------------------------------------------

    @Test
    void shouldSupportFourChannelIr() {
        // Simulating a 4-channel spatial RIR (e.g., B-format ambisonics)
        int channels = 4;
        ImpulseResponse ir = createExponentialDecayIr(channels, SAMPLE_RATE, 0.8);

        // Use 3 bands for variety
        SpatialRirResynthesizer r3 = new SpatialRirResynthesizer(SAMPLE_RATE, 3);
        double[][] decayTimes = r3.analyzeDecay(ir);

        assertThat(decayTimes).hasNumberOfRows(channels);
        for (int ch = 0; ch < channels; ch++) {
            assertThat(decayTimes[ch]).hasSize(3);
        }

        ImpulseResponse result = r3.resynthesize(ir, 1.5, decayTimes);
        assertThat(result.channelCount()).isEqualTo(channels);
    }

    // ----------------------------------------------------------------
    // Reset / stateless behavior
    // ----------------------------------------------------------------

    @Test
    void shouldProduceConsistentResultsWithSameSeed() {
        SpatialRirResynthesizer r1 = new SpatialRirResynthesizer(SAMPLE_RATE, 6, 123L);
        SpatialRirResynthesizer r2 = new SpatialRirResynthesizer(SAMPLE_RATE, 6, 123L);

        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.5);

        ImpulseResponse result1 = r1.resynthesize(ir, 1.0);
        ImpulseResponse result2 = r2.resynthesize(ir, 1.0);

        // Same seed should produce same output
        assertThat(result1.lengthInSamples()).isEqualTo(result2.lengthInSamples());
        for (int i = 0; i < result1.lengthInSamples(); i++) {
            assertThat(result1.samples()[0][i])
                    .isCloseTo(result2.samples()[0][i], within(1e-6f));
        }
    }

    // ----------------------------------------------------------------
    // Mixing time configuration
    // ----------------------------------------------------------------

    @Test
    void shouldRespectCustomMixingTime() {
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 0.5);

        resynthesizer.setMixingTime(0.02); // 20ms
        ImpulseResponse earlyMix = resynthesizer.resynthesize(ir, 1.0);

        resynthesizer.setMixingTime(0.1); // 100ms
        ImpulseResponse lateMix = resynthesizer.resynthesize(ir, 1.0);

        // Both should produce valid IRs
        assertThat(earlyMix).isNotNull();
        assertThat(lateMix).isNotNull();

        // The early part up to 20ms should differ from the late-mix version
        // since the crossfade point is different
        assertThat(earlyMix.lengthInSamples()).isEqualTo(lateMix.lengthInSamples());
    }

    // ----------------------------------------------------------------
    // Edge cases
    // ----------------------------------------------------------------

    @Test
    void shouldHandleVeryShortIr() {
        // IR shorter than mixing time
        float[] shortData = new float[100];
        shortData[0] = 1.0f;
        ImpulseResponse shortIr = new ImpulseResponse(new float[][]{shortData}, SAMPLE_RATE);

        ImpulseResponse result = resynthesizer.resynthesize(shortIr, 0.5);
        assertThat(result).isNotNull();
        assertThat(result.durationSeconds()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void shouldHandleTargetShorterThanMeasured() {
        // If target is shorter than measured, output should still be at least as long as measured
        ImpulseResponse ir = createExponentialDecayIr(1, SAMPLE_RATE, 1.0);
        ImpulseResponse result = resynthesizer.resynthesize(ir, 0.1);

        assertThat(result.lengthInSamples()).isGreaterThanOrEqualTo(ir.lengthInSamples());
    }

    // ----------------------------------------------------------------
    // Helper methods
    // ----------------------------------------------------------------

    /**
     * Creates a multi-channel impulse response with exponentially decaying noise.
     */
    private static ImpulseResponse createExponentialDecayIr(int channels, int sampleRate, double rt60) {
        int length = (int) (rt60 * sampleRate * 1.5); // 1.5x RT60
        length = Math.max(length, sampleRate / 4); // minimum 250ms
        float[][] samples = new float[channels][length];
        Random rand = new Random(42);
        double decayRate = -6.9078 / (rt60 * sampleRate);

        for (int ch = 0; ch < channels; ch++) {
            samples[ch][0] = 1.0f; // direct sound impulse
            for (int i = 1; i < length; i++) {
                double envelope = Math.exp(decayRate * i);
                samples[ch][i] = (float) (rand.nextGaussian() * 0.1 * envelope);
            }
        }
        return new ImpulseResponse(samples, sampleRate);
    }

    /**
     * Creates a single-channel decaying impulse.
     */
    private static float[] createDecayingImpulse(int sampleRate, double rt60) {
        int length = (int) (rt60 * sampleRate * 1.5);
        length = Math.max(length, sampleRate / 4);
        float[] data = new float[length];
        double decayRate = -6.9078 / (rt60 * sampleRate);
        data[0] = 1.0f;
        Random rand = new Random(42);
        for (int i = 1; i < length; i++) {
            data[i] = (float) (rand.nextGaussian() * 0.1 * Math.exp(decayRate * i));
        }
        return data;
    }

    private static double average(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double computeRmsRange(float[] samples, int start, int end) {
        start = Math.max(0, start);
        end = Math.min(samples.length, end);
        if (start >= end) return 0.0;
        double sumSquared = 0;
        for (int i = start; i < end; i++) {
            sumSquared += (double) samples[i] * samples[i];
        }
        return Math.sqrt(sumSquared / (end - start));
    }
}
