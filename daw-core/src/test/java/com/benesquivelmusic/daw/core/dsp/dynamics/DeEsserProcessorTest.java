package com.benesquivelmusic.daw.core.dsp.dynamics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link DeEsserProcessor}.
 *
 * <p>Validates parameter ranges, the split-band null property (when no
 * sibilance is detected, the output is bit-exact identical to the input),
 * the wideband ducking property, and the gain-reduction meter response on
 * a sibilant test tone.</p>
 */
class DeEsserProcessorTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @Test
    void shouldUseVocalFriendlyDefaults() {
        DeEsserProcessor d = new DeEsserProcessor(2, SAMPLE_RATE);
        assertThat(d.getInputChannelCount()).isEqualTo(2);
        assertThat(d.getOutputChannelCount()).isEqualTo(2);
        assertThat(d.getFrequencyHz()).isEqualTo(6500.0);
        assertThat(d.getQ()).isEqualTo(1.4);
        assertThat(d.getThresholdDb()).isEqualTo(-30.0);
        assertThat(d.getRangeDb()).isEqualTo(12.0);
        assertThat(d.getMode()).isEqualTo(DeEsserProcessor.Mode.SPLIT_BAND);
        assertThat(d.isListen()).isFalse();
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new DeEsserProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeEsserProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidParameterValues() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> d.setFrequencyHz(1000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setFrequencyHz(20000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setQ(0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setQ(10.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setThresholdDb(-100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setThresholdDb(5.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setRangeDb(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setRangeDb(50.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.setMode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * In split-band mode, when no sibilance is present (signal level below the
     * threshold of the detection band), the de-esser must not modify the
     * signal at all — output must be bit-exact identical to input.
     */
    @Test
    void splitBandShouldBeBitExactWhenNoSibilance() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        d.setMode(DeEsserProcessor.Mode.SPLIT_BAND);
        d.setThresholdDb(-6.0);          // very high threshold — never triggers
        d.setRangeDb(20.0);

        int n = 4096;
        // 200 Hz sine — far below the 6.5 kHz detection band.
        float[] in = sineBuffer(200.0, 0.5, n);
        float[][] input = { in };
        float[][] output = { new float[n] };
        d.process(input, output, n);

        // The threshold above is so high that gain reduction stays at 0 dB
        // throughout the buffer, so the split-band formula
        //   out = in + sibilantBand · (g − 1)
        // collapses to out = in for every sample — including the band-pass
        // settling transient at the start of the buffer (because the (g − 1)
        // factor is exactly zero, the sibilant-band value is irrelevant).
        for (int i = 0; i < n; i++) {
            assertThat(output[0][i]).isEqualTo(input[0][i]);
        }
        assertThat(d.getGainReductionDb()).isEqualTo(0.0);
    }

    /**
     * In wideband mode, a tone at the detection frequency loud enough to cross
     * the threshold must produce signal-wide attenuation (output level &lt;
     * input level on every channel).
     */
    @Test
    void widebandShouldDuckFullSignalAboveThreshold() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        d.setMode(DeEsserProcessor.Mode.WIDEBAND);
        d.setFrequencyHz(6500.0);
        d.setQ(1.4);
        d.setThresholdDb(-40.0);
        d.setRangeDb(20.0);

        int n = 8192;
        // Strong sine at 6.5 kHz — squarely in the detection band.
        float[] in = sineBuffer(6500.0, 0.8, n);
        float[][] input = { in };
        float[][] output = { new float[n] };
        d.process(input, output, n);

        // After the envelope settles, output peak must be measurably lower.
        double inPeak  = peak(in,  n / 2, n);
        double outPeak = peak(output[0], n / 2, n);
        assertThat(outPeak).isLessThan(inPeak * 0.95);
        assertThat(d.getGainReductionDb()).isLessThan(-1.0);
    }

    /**
     * Range parameter must clamp the maximum applied attenuation. With a very
     * loud detection signal and {@code range = 6 dB}, the gain reduction must
     * not exceed -6 dB (within a small tolerance for log-domain rounding).
     */
    @Test
    void rangeShouldClampMaximumAttenuation() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        d.setMode(DeEsserProcessor.Mode.WIDEBAND);
        d.setThresholdDb(-60.0);
        d.setRangeDb(6.0);

        int n = 16384;
        float[] in = sineBuffer(6500.0, 0.95, n);
        float[][] input = { in };
        float[][] output = { new float[n] };
        d.process(input, output, n);

        assertThat(d.getGainReductionDb()).isGreaterThanOrEqualTo(-6.0 - 1e-9);
    }

    /**
     * Listen mode must replace the output with the detection (sibilant) band.
     * For a signal that is the sum of a low-frequency tone (200 Hz) and a
     * sibilant-band tone (6.5 kHz), the listen output must contain only the
     * sibilant component — confirmed by the residual at 200 Hz being near zero.
     */
    @Test
    void listenModeShouldOutputDetectionBand() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        d.setListen(true);
        d.setFrequencyHz(6500.0);
        d.setQ(1.4);

        int n = 4096;
        float[] low = sineBuffer(200.0, 0.5, n);
        float[] hi  = sineBuffer(6500.0, 0.5, n);
        float[] in = new float[n];
        for (int i = 0; i < n; i++) in[i] = low[i] + hi[i];

        float[][] input = { in };
        float[][] output = { new float[n] };
        d.process(input, output, n);

        // Last quarter should be steady-state. RMS of output should be much
        // closer to the 6.5 kHz tone's RMS than to the input's RMS.
        double outRms = rms(output[0], 3 * n / 4, n);
        double hiRms  = rms(hi,        3 * n / 4, n);
        // Listen output is the BAND_PASS output of the input — it isolates the
        // 6.5 kHz tone and rejects the 200 Hz tone, so its RMS must be close
        // to the high-tone RMS, not to the (twice-as-loud) input.
        assertThat(outRms).isCloseTo(hiRms, within(hiRms * 0.2));
    }

    /**
     * Frequency / Q changes must take effect on the next buffer (validates the
     * cached-coefficient refresh path) without throwing.
     */
    @Test
    void shouldRefreshCoefficientsOnParameterChange() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        int n = 256;
        float[][] in = { new float[n] };
        float[][] out = { new float[n] };
        d.process(in, out, n);
        d.setFrequencyHz(8000.0);
        d.setQ(2.0);
        d.process(in, out, n);
        assertThat(d.getFrequencyHz()).isEqualTo(8000.0);
        assertThat(d.getQ()).isEqualTo(2.0);
    }

    @Test
    void resetShouldClearMetersAndEnvelope() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        d.setMode(DeEsserProcessor.Mode.WIDEBAND);
        d.setThresholdDb(-60.0);
        int n = 1024;
        float[] in = sineBuffer(6500.0, 0.9, n);
        d.process(new float[][] { in }, new float[][] { new float[n] }, n);
        assertThat(d.getGainReductionDb()).isLessThan(0.0);

        d.reset();
        assertThat(d.getGainReductionDb()).isEqualTo(0.0);
        assertThat(d.getMeterSnapshot().inputLevelDb())
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void meterSnapshotShouldReflectProcessing() {
        DeEsserProcessor d = new DeEsserProcessor(1, SAMPLE_RATE);
        d.setMode(DeEsserProcessor.Mode.WIDEBAND);
        d.setThresholdDb(-40.0);
        int n = 4096;
        float[] in = sineBuffer(6500.0, 0.7, n);
        d.process(new float[][] { in }, new float[][] { new float[n] }, n);
        var snap = d.getMeterSnapshot();
        assertThat(snap.gainReductionDb()).isLessThanOrEqualTo(0.0);
        assertThat(snap.inputLevelDb()).isGreaterThan(Double.NEGATIVE_INFINITY);
        assertThat(snap.outputLevelDb()).isGreaterThan(Double.NEGATIVE_INFINITY);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static float[] sineBuffer(double freqHz, double amp, int n) {
        float[] out = new float[n];
        double w = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
        for (int i = 0; i < n; i++) {
            out[i] = (float) (amp * Math.sin(w * i));
        }
        return out;
    }

    private static double peak(float[] buf, int from, int to) {
        double p = 0.0;
        for (int i = from; i < to; i++) {
            double a = Math.abs(buf[i]);
            if (a > p) p = a;
        }
        return p;
    }

    private static double rms(float[] buf, int from, int to) {
        double s = 0.0;
        int n = to - from;
        for (int i = from; i < to; i++) {
            s += (double) buf[i] * buf[i];
        }
        return Math.sqrt(s / n);
    }
}
