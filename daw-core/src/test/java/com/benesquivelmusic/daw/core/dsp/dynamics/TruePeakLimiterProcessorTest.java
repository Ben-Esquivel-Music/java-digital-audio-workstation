package com.benesquivelmusic.daw.core.dsp.dynamics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TruePeakLimiterProcessor}.
 *
 * <p>Validates parameter ranges, the brickwall guarantee on a known
 * inter-sample peak (ISP) test signal at 4× ISR, and the bit-exact bypass
 * path.</p>
 */
class TruePeakLimiterProcessorTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final double EPS_DB = 0.1;

    @Test
    void shouldUseMasteringFriendlyDefaults() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        assertThat(l.getInputChannelCount()).isEqualTo(2);
        assertThat(l.getOutputChannelCount()).isEqualTo(2);
        assertThat(l.getCeilingDb()).isEqualTo(-1.0);
        assertThat(l.getReleaseMs()).isEqualTo(50.0);
        assertThat(l.getLookaheadMs()).isEqualTo(5.0);
        assertThat(l.getIsr()).isEqualTo(4);
        assertThat(l.getChannelLinkPercent()).isEqualTo(100.0);
        assertThat(l.isBypass()).isFalse();
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new TruePeakLimiterProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TruePeakLimiterProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParametersOutOfRange() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        assertThatThrownBy(() -> l.setCeilingDb(0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setCeilingDb(-3.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setReleaseMs(0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setReleaseMs(2000.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setLookaheadMs(0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setLookaheadMs(20.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setChannelLinkPercent(-1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> l.setChannelLinkPercent(150.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isrSnapsToSupportedFactors() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        l.setIsr(3); assertThat(l.getIsr()).isEqualTo(2);
        l.setIsr(5); assertThat(l.getIsr()).isEqualTo(4);
        l.setIsr(7); assertThat(l.getIsr()).isEqualTo(8);
    }

    @Test
    void bypassIsBitExact() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        l.setBypass(true);
        int n = 1024;
        float[][] in  = new float[2][n];
        float[][] out = new float[2][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = (float) Math.sin(2 * Math.PI * 1000.0 * i / SAMPLE_RATE);
            in[1][i] = (float) (-0.5 * Math.cos(2 * Math.PI * 500.0 * i / SAMPLE_RATE));
        }
        l.process(in, out, n);
        for (int i = 0; i < n; i++) {
            assertThat(out[0][i]).isEqualTo(in[0][i]);
            assertThat(out[1][i]).isEqualTo(in[1][i]);
        }
    }

    /**
     * On a synthetic stereo signal whose 4×-oversampled true peak is
     * +0.8 dBTP (sample peak ≈ -2.2 dBFS), the limiter must hold the
     * output at or below the configured ceiling (here -1.0 dBTP) within
     * 0.1 dB after the lookahead transient.
     */
    @Test
    void heldsOutputBelowCeilingOnIspSignal() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        l.setCeilingDb(-1.0);
        l.setIsr(4);
        // A cosine at fs/4 with phase π/4 has samples at ±A·cos(π/4) ≈ ±0.707 A
        // but a true peak of A (the inter-sample maxima fall halfway between
        // samples). Choosing A = 10^(0.8/20) ≈ 1.0965 gives true peak +0.8 dBTP
        // and sample peak ≈ -2.2 dBFS — well below 0 dBFS, so a sample-peak
        // limiter would never engage.
        double a = Math.pow(10.0, 0.8 / 20.0);
        int n = 8192;
        float[][] in  = new float[2][n];
        float[][] out = new float[2][n];
        for (int i = 0; i < n; i++) {
            double v = a * Math.cos(2 * Math.PI * 0.25 * i + Math.PI / 4.0);
            in[0][i] = (float) v;
            in[1][i] = (float) v;
        }
        l.process(in, out, n);

        // Measure the 4×-oversampled true peak of the OUTPUT using an
        // independent detector (a fresh limiter in bypass with a separate
        // detection-only pass). A simple sanity check: sample peak must
        // also be ≤ ceiling (an upper bound on the true-peak detector).
        double samplePeak = 0.0;
        // Skip the lookahead transient at the start.
        int skip = l.getLookaheadSamples() + 32;
        for (int i = skip; i < n; i++) {
            samplePeak = Math.max(samplePeak, Math.max(
                    Math.abs(out[0][i]), Math.abs(out[1][i])));
        }
        double samplePeakDb = 20.0 * Math.log10(samplePeak);
        assertThat(samplePeakDb).isLessThanOrEqualTo(-1.0 + EPS_DB);

        // Independent oversampled detection: convolve a Hann-windowed sinc
        // half-sample interpolator and check the inter-sample peak.
        double truePeak = oversampledPeak(out[0], skip);
        double truePeakDb = 20.0 * Math.log10(truePeak);
        assertThat(truePeakDb).isLessThanOrEqualTo(-1.0 + EPS_DB);
    }

    @Test
    void reportsGainReductionWhileLimiting() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        l.setCeilingDb(-1.0);
        int n = 4096;
        float[][] in  = new float[2][n];
        float[][] out = new float[2][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = 0.95f; // DC well above the ceiling
            in[1][i] = 0.95f;
        }
        l.process(in, out, n);
        assertThat(l.getGainReductionDb()).isLessThan(-0.5);
        var snap = l.getMeterSnapshot();
        assertThat(snap.gainReductionDb()).isEqualTo(l.getGainReductionDb());
        assertThat(snap.inputLevelDb()).isGreaterThan(snap.outputLevelDb());
    }

    @Test
    void noReductionWhenInputBelowCeiling() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        l.setCeilingDb(-1.0);
        int n = 4096;
        float[][] in  = new float[2][n];
        float[][] out = new float[2][n];
        // -12 dBFS sine — well below the ceiling.
        for (int i = 0; i < n; i++) {
            float v = (float) (Math.pow(10, -12.0 / 20.0)
                    * Math.sin(2 * Math.PI * 1000.0 * i / SAMPLE_RATE));
            in[0][i] = v;
            in[1][i] = v;
        }
        l.process(in, out, n);
        assertThat(l.getGainReductionDb()).isGreaterThanOrEqualTo(-0.001);
    }

    @Test
    void resetClearsState() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        int n = 1024;
        float[][] in  = new float[2][n];
        float[][] out = new float[2][n];
        for (int i = 0; i < n; i++) { in[0][i] = 0.99f; in[1][i] = 0.99f; }
        l.process(in, out, n);
        assertThat(l.getGainReductionDb()).isLessThan(0.0);
        l.reset();
        assertThat(l.getGainReductionDb()).isEqualTo(0.0);
    }

    @Test
    void exposesLookaheadInSamplesForPdc() {
        var l = new TruePeakLimiterProcessor(2, SAMPLE_RATE);
        l.setLookaheadMs(5.0);
        // 5 ms × 48 kHz = 240 samples.
        assertThat(l.getLookaheadSamples()).isEqualTo(240);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Independent oversampled peak detector — half-sample interpolation via
     * Hann-windowed sinc — used to verify the limiter from outside without
     * leaning on its own detector.
     */
    private static double oversampledPeak(float[] x, int from) {
        final int K = 16; // oversampling factor for verification
        final int taps = 32;
        double[] proto = new double[taps];
        double centre = (taps - 1) / 2.0;
        // Sub-sample at fractional offsets {0, 1/K, 2/K, ..., (K-1)/K}.
        double peak = 0.0;
        for (int frac = 0; frac < K; frac++) {
            double off = (double) frac / K;
            double sum = 0.0;
            for (int t = 0; t < taps; t++) {
                double n = (t - centre) - off;
                double sinc = (Math.abs(n) < 1e-12) ? 1.0 : Math.sin(Math.PI * n) / (Math.PI * n);
                double w = 0.5 * (1.0 - Math.cos(2 * Math.PI * t / (taps - 1)));
                proto[t] = sinc * w;
                sum += proto[t];
            }
            // normalize unity DC
            for (int t = 0; t < taps; t++) proto[t] /= sum;
            // Convolve along x and take the maximum |y|.
            for (int i = from; i < x.length - taps; i++) {
                double y = 0.0;
                for (int t = 0; t < taps; t++) y += proto[t] * x[i + t];
                if (Math.abs(y) > peak) peak = Math.abs(y);
            }
        }
        return peak;
    }
}
