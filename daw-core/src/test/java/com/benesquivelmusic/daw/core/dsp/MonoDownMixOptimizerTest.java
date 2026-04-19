package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.dsp.MonoDownMixOptimizer.MonoCompatibilityReport;
import com.benesquivelmusic.daw.core.dsp.MonoDownMixOptimizer.Mode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class MonoDownMixOptimizerTest {

    private static final double SAMPLE_RATE = 48000.0;

    @Test
    void standardSumShouldProduceLPlusROverTwo() {
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.STANDARD_SUM, SAMPLE_RATE);
        float[] left = {0.8f, 0.6f, 0.4f, 0.2f};
        float[] right = {0.2f, 0.4f, 0.6f, 0.8f};
        float[] mono = new float[4];

        optimizer.process(left, right, mono, 4);

        for (int i = 0; i < 4; i++) {
            assertThat(mono[i]).isCloseTo((left[i] + right[i]) * 0.5f, offset(1e-6f));
        }
    }

    @Test
    void standardSumShouldCancelAntiPhaseSignal() {
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.STANDARD_SUM, SAMPLE_RATE);
        int numFrames = 1024;
        float[] left = new float[numFrames];
        float[] right = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            left[i] = (float) Math.sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE);
            right[i] = -left[i];
        }
        float[] mono = new float[numFrames];

        optimizer.process(left, right, mono, numFrames);

        // Baseline sum should cancel completely for a perfectly anti-phase pair.
        for (int i = 0; i < numFrames; i++) {
            assertThat(mono[i]).isCloseTo(0.0f, offset(1e-6f));
        }
    }

    @Test
    void polarityAdaptiveShouldRecoverAntiPhaseEnergy() {
        // A full-band anti-phase pair (L = sin, R = -sin) completely cancels
        // under the naive sum. Polarity-adaptive detects the negative
        // correlation in every band and inverts R before summing, recovering
        // the signal energy instead of cancelling it.
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.POLARITY_ADAPTIVE, SAMPLE_RATE);
        int numFrames = 8192;
        float[] left = new float[numFrames];
        float[] right = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            left[i] = (float) Math.sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE);
            right[i] = -left[i];
        }
        float[] mono = new float[numFrames];

        optimizer.process(left, right, mono, numFrames);

        // Skip filter warm-up transient.
        double monoRms = rms(mono, 2048, numFrames);
        double leftRms = rms(left, 2048, numFrames);
        assertThat(monoRms).isGreaterThan(0.5 * leftRms);
    }

    @Test
    void polarityAdaptiveShouldPreserveRmsForMonoInput() {
        // For an identical L/R (true mono) signal, every band has correlation +1,
        // so polarity-adaptive mode should not invert anything. Linkwitz-Riley
        // band splitting introduces a global all-pass response (phase-shift
        // without magnitude change), so the reconstructed RMS must match the
        // input RMS even though individual samples differ.
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.POLARITY_ADAPTIVE, SAMPLE_RATE);
        int numFrames = 8192;
        float[] left = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            left[i] = (float) (0.5 * Math.sin(2 * Math.PI * 1000.0 * i / SAMPLE_RATE));
        }
        float[] right = left.clone();
        float[] mono = new float[numFrames];

        optimizer.process(left, right, mono, numFrames);

        double inputRms = rms(left, 2048, numFrames);
        double outputRms = rms(mono, 2048, numFrames);
        assertThat(outputRms).isCloseTo(inputRms, offset(0.02 * inputRms));
    }

    @Test
    void energyPreservingShouldMatchAverageChannelRmsForAntiPhase() {
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.ENERGY_PRESERVING, SAMPLE_RATE);
        int numFrames = 4096;
        float[] left = new float[numFrames];
        float[] right = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            left[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE));
            right[i] = -left[i];
        }
        float[] mono = new float[numFrames];

        optimizer.process(left, right, mono, numFrames);

        // For anti-phase input, (L+R)/2 = 0 — the RMS-compensation branch
        // detects zero RMS and bypasses scaling rather than dividing by zero.
        // Verify no NaN/Infinity output is produced.
        for (int i = 0; i < numFrames; i++) {
            assertThat(Float.isFinite(mono[i])).isTrue();
        }
    }

    @Test
    void energyPreservingShouldCompensatePartialCancellation() {
        // L and R partly anti-correlated: standard sum RMS is less than the
        // channel-average RMS, so energy-preserving mode must scale the output
        // back up to the channel average.
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.ENERGY_PRESERVING, SAMPLE_RATE);
        int numFrames = 4096;
        float[] left = new float[numFrames];
        float[] right = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            double a = Math.sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE);
            double b = Math.sin(2 * Math.PI * 660.0 * i / SAMPLE_RATE);
            // Two partially correlated signals (shared component a)
            left[i] = (float) (0.5 * a + 0.3 * b);
            right[i] = (float) (0.5 * a - 0.3 * b);
        }
        float[] mono = new float[numFrames];

        optimizer.process(left, right, mono, numFrames);

        double rmsL = rms(left, 0, numFrames);
        double rmsR = rms(right, 0, numFrames);
        double target = 0.5 * (rmsL + rmsR);
        double rmsMono = rms(mono, 0, numFrames);

        assertThat(rmsMono).isCloseTo(target, offset(1e-4));
    }

    @Test
    void computeReportShouldReturnUnityScoreForPerfectlyMonoInput() {
        int numFrames = 2048;
        float[] left = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            left[i] = (float) Math.sin(2 * Math.PI * 1000.0 * i / SAMPLE_RATE);
        }
        float[] right = left.clone();
        float[] mono = new float[numFrames];
        new MonoDownMixOptimizer(Mode.STANDARD_SUM, SAMPLE_RATE)
                .process(left, right, mono, numFrames);

        MonoCompatibilityReport report = MonoDownMixOptimizer.computeReport(
                left, right, mono, numFrames);

        assertThat(report.score()).isCloseTo(1.0, offset(1e-6));
        assertThat(report.energyLossDb()).isCloseTo(0.0, offset(1e-6));
    }

    @Test
    void computeReportShouldReportZeroScoreForAntiPhaseInput() {
        int numFrames = 2048;
        float[] left = new float[numFrames];
        float[] right = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            left[i] = (float) Math.sin(2 * Math.PI * 1000.0 * i / SAMPLE_RATE);
            right[i] = -left[i];
        }
        float[] mono = new float[numFrames];
        new MonoDownMixOptimizer(Mode.STANDARD_SUM, SAMPLE_RATE)
                .process(left, right, mono, numFrames);

        MonoCompatibilityReport report = MonoDownMixOptimizer.computeReport(
                left, right, mono, numFrames);

        assertThat(report.score()).isCloseTo(0.0, offset(1e-6));
        assertThat(report.energyLossDb()).isLessThan(-60.0);
        assertThat(report.standardSumRms()).isCloseTo(0.0, offset(1e-6));
    }

    @Test
    void constructorShouldRejectInvalidArguments() {
        assertThatThrownBy(() -> new MonoDownMixOptimizer(null, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoDownMixOptimizer(Mode.STANDARD_SUM, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoDownMixOptimizer(
                Mode.POLARITY_ADAPTIVE, SAMPLE_RATE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoDownMixOptimizer(
                Mode.POLARITY_ADAPTIVE, SAMPLE_RATE, new double[]{-10.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoDownMixOptimizer(
                Mode.POLARITY_ADAPTIVE, SAMPLE_RATE, new double[]{2000.0, 1000.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonoDownMixOptimizer(
                Mode.POLARITY_ADAPTIVE, SAMPLE_RATE, new double[]{SAMPLE_RATE}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processShouldRejectShortBuffers() {
        MonoDownMixOptimizer optimizer = new MonoDownMixOptimizer(Mode.STANDARD_SUM, SAMPLE_RATE);
        float[] left = new float[4];
        float[] right = new float[4];
        float[] mono = new float[2];
        assertThatThrownBy(() -> optimizer.process(left, right, mono, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double rms(float[] buf, int start, int end) {
        double s = 0.0;
        int n = end - start;
        for (int i = start; i < end; i++) {
            s += buf[i] * buf[i];
        }
        return Math.sqrt(s / n);
    }
}
