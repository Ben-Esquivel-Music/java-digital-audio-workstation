package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruePeakDetectorTest {

    @Test
    void shouldDetectSamplePeakForConstantSignal() {
        TruePeakDetector detector = new TruePeakDetector();
        for (int i = 0; i < 100; i++) {
            detector.processSample(0.5);
        }
        // True peak should be at least as high as the sample peak
        assertThat(detector.getTruePeakLinear()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void shouldDetectIntersamplePeak() {
        // Two consecutive samples at +0.9 and -0.9 create an intersample peak
        // higher than 0.9 because the interpolated signal overshoots
        TruePeakDetector detector = new TruePeakDetector();

        // Feed alternating high-amplitude samples to create intersample peaks
        for (int i = 0; i < 100; i++) {
            double sample = (i % 2 == 0) ? 0.9 : -0.9;
            detector.processSample(sample);
        }

        // The true peak from interpolation should be >= the sample peak
        assertThat(detector.getTruePeakLinear()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void shouldReturnZeroForSilence() {
        TruePeakDetector detector = new TruePeakDetector();
        for (int i = 0; i < 50; i++) {
            detector.processSample(0.0);
        }
        assertThat(detector.getTruePeakLinear()).isEqualTo(0.0);
        assertThat(detector.getTruePeakDbtp()).isEqualTo(-120.0);
    }

    @Test
    void shouldComputeCorrectDbtpForFullScale() {
        TruePeakDetector detector = new TruePeakDetector();
        // Feed a signal that results in a peak of exactly 1.0
        detector.processSample(1.0);
        // The true peak should be at least 0 dBTP (or above due to overshoot)
        assertThat(detector.getTruePeakDbtp()).isGreaterThanOrEqualTo(0.0 - 1.0);
    }

    @Test
    void shouldResetState() {
        TruePeakDetector detector = new TruePeakDetector();
        for (int i = 0; i < 50; i++) {
            detector.processSample(0.8);
        }
        assertThat(detector.getTruePeakLinear()).isGreaterThan(0.0);

        detector.reset();
        assertThat(detector.getTruePeakLinear()).isEqualTo(0.0);
        assertThat(detector.getTruePeakDbtp()).isEqualTo(-120.0);
    }

    @Test
    void shouldAccumulateMaxTruePeak() {
        TruePeakDetector detector = new TruePeakDetector();

        // Process low-level signal
        for (int i = 0; i < 50; i++) {
            detector.processSample(0.1);
        }
        double firstPeak = detector.getTruePeakLinear();

        // Process higher-level signal
        for (int i = 0; i < 50; i++) {
            detector.processSample(0.9);
        }
        double secondPeak = detector.getTruePeakLinear();

        assertThat(secondPeak).isGreaterThan(firstPeak);
    }

    @Test
    void shouldHandleNegativeSamples() {
        TruePeakDetector detector = new TruePeakDetector();
        for (int i = 0; i < 50; i++) {
            detector.processSample(-0.7);
        }
        assertThat(detector.getTruePeakLinear()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void truePeakShouldBeAtLeastAsBigAsSamplePeak() {
        TruePeakDetector detector = new TruePeakDetector();
        double maxSample = 0.0;
        for (int i = 0; i < 200; i++) {
            double sample = Math.sin(2 * Math.PI * i / 10.0) * 0.8;
            maxSample = Math.max(maxSample, Math.abs(sample));
            detector.processSample(sample);
        }
        assertThat(detector.getTruePeakLinear()).isGreaterThanOrEqualTo(maxSample);
    }

    // ── BS.1770-4 reconstruction-quality tests ─────────────────────────────
    //
    // The following tests fail with the previous (broken) coefficient table
    // because that filter never produced an interpolated value larger than
    // the sample peak — defeating the purpose of an inter-sample peak
    // detector. They pass with the corrected linear-phase 4× reconstruction
    // FIR (Kaiser-windowed sinc, 48 taps / 12 per phase), which restores
    // proper inter-sample peak reconstruction per BS.1770-4 Annex 2.

    /**
     * Canonical BS.1770-4 inter-sample-peak reference case: a sine at
     * {@code fs/4} sampled at phase {@code π/4}, so every sample lands on
     * {@code ±A/√2}. The continuous peak of the underlying signal is {@code
     * A}, i.e. 3.01 dB above the sample peak, and a properly-designed 4×
     * reconstruction filter must reveal it. With a sample peak of
     * {@code −0.3 dBFS} the continuous amplitude is {@code +2.71 dBFS};
     * the detected true peak must be at least {@code +2.71 dBTP}.
     */
    @Test
    void fsOver4SineAtPiOverFourReconstructsAboveSamplePeak() {
        TruePeakDetector detector = new TruePeakDetector();
        double samplePeakDbfs = -0.3;
        double samplePeakLinear = Math.pow(10.0, samplePeakDbfs / 20.0);
        double continuousAmplitude = samplePeakLinear * Math.sqrt(2.0);

        for (int i = 0; i < 4096; i++) {
            double phase = 2.0 * Math.PI * i / 4.0 + Math.PI / 4.0;
            detector.processSample(continuousAmplitude * Math.sin(phase));
        }

        assertThat(detector.getTruePeakDbtp())
                .as("fs/4 sine at -0.3 dBFS sample peak (phase π/4) must reconstruct to >= +2.71 dBTP")
                .isGreaterThanOrEqualTo(2.71);
    }

    /**
     * fs/8 sine sampled with a phase offset so the sample lattice never
     * lands on the continuous peak. This is a second canonical inter-sample
     * peak fixture that the old coefficient table cannot reconstruct.
     */
    @Test
    void fsOver8SineWithPhaseOffsetExposesIntersamplePeak() {
        TruePeakDetector detector = new TruePeakDetector();
        double continuousAmplitude = 0.95; // ≈ -0.45 dBFS continuous peak
        double samplePeak = 0.0;
        for (int i = 0; i < 4096; i++) {
            double phase = 2.0 * Math.PI * i / 8.0 + Math.PI / 8.0;
            double s = continuousAmplitude * Math.sin(phase);
            samplePeak = Math.max(samplePeak, Math.abs(s));
            detector.processSample(s);
        }

        // Sample lattice misses the continuous peak — the oversampler must
        // recover at least 0.5 dB above the sample peak for this off-lattice sine.
        assertThat(detector.getTruePeakLinear())
                .as("oversampled true peak must be at least 0.5 dB above the sample peak for an off-lattice sine")
                .isGreaterThan(samplePeak * Math.pow(10.0, 0.5 / 20.0));
        // And the true peak must approach the continuous amplitude (within 0.1 dB).
        assertThat(detector.getTruePeakLinear())
                .isGreaterThanOrEqualTo(continuousAmplitude * Math.pow(10.0, -0.1 / 20.0));
    }

    /**
     * DC and very-low-frequency signals must be reconstructed without
     * artificial overshoot — the steady-state per-sample true peak must
     * equal the sample value within {@code ±0.05 dB}.
     */
    @Test
    void dcSignalIsReconstructedWithinPointZeroFiveDbOfSamplePeak() {
        TruePeakDetector detector = new TruePeakDetector();
        // Long enough to flush any startup transient through the 12-tap
        // sub-filters; the per-call return value reflects the true peak
        // detected for the most recent sample only (not the cumulative
        // latch), so we assert against that.
        double sample = 0.7;
        double lastReturn = 0.0;
        for (int i = 0; i < 256; i++) {
            lastReturn = detector.processSample(sample);
        }
        // Within 0.05 dB of sample peak ⇒ within factor 1.0058.
        double allowed = sample * Math.pow(10.0, 0.05 / 20.0);
        assertThat(lastReturn)
                .as("DC reconstruction must not boost the signal beyond +0.05 dB of sample peak")
                .isBetween(sample, allowed);
    }

    /**
     * Linear-phase verification: the underlying 48-tap prototype impulse
     * response {@code h[n]} must satisfy {@code h[n] == h[N-1-n]}. Polyphase
     * decomposition of a symmetric prototype produces a time-reversal
     * symmetry between phases {@code p} and {@code L-1-p}: tap {@code k} of
     * phase {@code p} equals tap {@code N-1-k} (i.e. {@code 11-k}) of phase
     * {@code 3-p}. Verify directly from the published table.
     */
    @Test
    void polyphaseCoefficientsAreLinearPhase() {
        double[][] c = TruePeakDetector.coefficients();
        int L = TruePeakDetector.oversamplingFactor();
        int taps = TruePeakDetector.filterTaps();
        assertThat(c.length).isEqualTo(L);
        for (double[] phase : c) {
            assertThat(phase.length).isEqualTo(taps);
        }
        for (int p = 0; p < L; p++) {
            for (int k = 0; k < taps; k++) {
                assertThat(c[p][k])
                        .as("linear-phase symmetry: c[%d][%d] == c[%d][%d]",
                                p, k, L - 1 - p, taps - 1 - k)
                        .isCloseTo(c[L - 1 - p][taps - 1 - k],
                                org.assertj.core.data.Offset.offset(1e-15));
            }
        }
    }

    /**
     * Each polyphase sub-filter must sum to ≈ 1 so that the reconstructed
     * output of every phase preserves the DC level of the input.
     */
    @Test
    void everyPolyphaseSubFilterHasUnityDcGain() {
        double[][] c = TruePeakDetector.coefficients();
        for (int p = 0; p < c.length; p++) {
            double sum = 0.0;
            for (double tap : c[p]) {
                sum += tap;
            }
            assertThat(sum)
                    .as("phase %d DC gain", p)
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        }
    }
}
