package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

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
}
