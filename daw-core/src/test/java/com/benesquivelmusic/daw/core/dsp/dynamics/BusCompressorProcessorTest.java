package com.benesquivelmusic.daw.core.dsp.dynamics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for {@link BusCompressorProcessor}.
 *
 * <p>Covers parameter validation, the null-mix bit-exact test mandated by the
 * feature story, steady-state gain-reduction tracking, and sidechain routing.</p>
 */
class BusCompressorProcessorTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @Test
    void shouldUseSslStyleDefaults() {
        BusCompressorProcessor c = new BusCompressorProcessor(2, SAMPLE_RATE);
        assertThat(c.getInputChannelCount()).isEqualTo(2);
        assertThat(c.getOutputChannelCount()).isEqualTo(2);
        assertThat(c.getThresholdDb()).isEqualTo(-10.0);
        assertThat(c.getRatio()).isEqualTo(4.0);
        assertThat(c.getAttackMs()).isEqualTo(10.0);
        assertThat(c.getReleaseS()).isEqualTo(0.6);
        assertThat(c.getMakeupGainDb()).isEqualTo(0.0);
        assertThat(c.getMix()).isEqualTo(1.0);
        assertThat(c.isReleaseAuto()).isFalse();
        assertThat(c.isDrive()).isFalse();
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new BusCompressorProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusCompressorProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSnapRatioToNearestStep() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setRatio(3.2);
        assertThat(c.getRatio()).isEqualTo(4.0);
        c.setRatio(1.6);
        assertThat(c.getRatio()).isEqualTo(1.5);
        c.setRatio(9.0);
        assertThat(c.getRatio()).isEqualTo(10.0);
    }

    @Test
    void shouldSnapAttackAndReleaseToNearestStep() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setAttackMs(5.0);
        assertThat(c.getAttackMs()).isEqualTo(3.0);
        c.setReleaseS(0.5);
        assertThat(c.getReleaseS()).isEqualTo(0.6);
    }

    @Test
    void shouldRejectOutOfRangeParameters() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> c.setThresholdDb(5.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setThresholdDb(-100.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setRatio(0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setAttackMs(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setReleaseS(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setMakeupGainDb(-1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setMakeupGainDb(30.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setMix(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.setMix(1.1)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Null-mix golden test: with {@code mix = 0} the compressor must reproduce
     * the input bit-exact, regardless of compression parameters.
     */
    @Test
    void nullTestMixZeroIsBitExact() {
        BusCompressorProcessor c = new BusCompressorProcessor(2, SAMPLE_RATE);
        c.setThresholdDb(-30.0);
        c.setRatio(10.0);
        c.setMakeupGainDb(12.0);
        c.setDrive(true);
        c.setMix(0.0);

        int n = 2048;
        float[][] in = sineStereo(1_000.0, 0.5f, n);
        float[][] expected = deepCopy(in);
        float[][] out = new float[2][n];

        c.process(in, out, n);

        for (int ch = 0; ch < 2; ch++) {
            assertThat(out[ch]).as("channel %d", ch).containsExactly(expected[ch]);
        }
    }

    /**
     * Steady-state gain-reduction golden test. Feeds a steady 1 kHz sine tone
     * at {@code -10 dBFS} peak into the compressor configured with
     * threshold {@code -20 dB} and ratio {@code 4:1}. The processor should
     * settle to a stable, reproducible gain-reduction value, validated here
     * within the 0.1 dB tolerance mandated by the feature story.
     *
     * <p>The numeric target ({@value EXPECTED_GR_DB} dB) is the golden value
     * produced by this implementation's feedforward peak detector and
     * SSL-style soft-knee gain computer; a regression in either of those
     * stages will move the measured reduction outside the tolerance.</p>
     */
    @Test
    void steadyStateGainReductionAtKnownOperatingPoint() {
        final double EXPECTED_GR_DB = -7.4;

        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setThresholdDb(-20.0);
        c.setRatio(4.0);
        c.setAttackMs(0.1);  // fast attack so the envelope settles quickly
        c.setReleaseS(0.1);
        c.setMakeupGainDb(0.0);
        c.setMix(1.0);

        int warmup = (int) (0.2 * SAMPLE_RATE); // 200 ms warm-up
        int measure = 4096;
        float[][] warmBuf = sineMono(1_000.0, dbToLinear(-10.0), warmup);
        float[][] out     = new float[1][warmup];
        c.process(warmBuf, out, warmup);

        float[][] measBuf = sineMono(1_000.0, dbToLinear(-10.0), measure);
        float[][] measOut = new float[1][measure];
        c.process(measBuf, measOut, measure);

        assertThat(c.getGainReductionDb())
                .as("steady-state GR at 1 kHz/-10 dBFS into threshold -20 dB / 4:1")
                .isCloseTo(EXPECTED_GR_DB, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void shouldReportZeroGainReductionBelowThreshold() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setThresholdDb(0.0);
        float[][] quiet = new float[1][1024];
        java.util.Arrays.fill(quiet[0], 0.01f);
        c.process(quiet, new float[1][1024], 1024);
        assertThat(c.getGainReductionDb()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void sidechainDrivesDetectionWhileMainPassesThrough() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setThresholdDb(-20.0);
        c.setRatio(10.0);
        c.setAttackMs(0.1);

        int n = 4096;
        float[][] main = sineMono(1_000.0, 0.05f, n);   // quiet main
        float[][] sc   = sineMono(1_000.0, 0.9f,  n);   // loud sidechain
        float[][] out  = new float[1][n];

        c.processSidechain(main, sc, out, n);

        // Loud sidechain must cause gain reduction even though main is quiet.
        assertThat(c.getGainReductionDb()).isLessThan(-1.0);
    }

    @Test
    void driveAddsHarmonicColoration() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setThresholdDb(0.0);  // no compression
        c.setDrive(true);

        int n = 512;
        float[][] in = sineMono(1_000.0, 0.9f, n);
        float[][] out = new float[1][n];
        c.process(in, out, n);

        // With drive enabled the output diverges from input at high amplitudes.
        boolean differs = false;
        for (int i = 0; i < n; i++) {
            if (Math.abs(out[0][i] - in[0][i]) > 1e-4) { differs = true; break; }
        }
        assertThat(differs).as("drive should add harmonic coloration").isTrue();
    }

    @Test
    void resetClearsEnvelopeAndGrState() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setAttackMs(0.1);
        int n = 8192;
        float[][] loud = new float[1][n];
        java.util.Arrays.fill(loud[0], 0.9f);
        c.process(loud, new float[1][n], n);
        assertThat(c.getGainReductionDb()).isLessThan(0.0);

        c.reset();
        assertThat(c.getGainReductionDb()).isZero();
        var snap = c.getMeterSnapshot();
        assertThat(snap.gainReductionDb()).isZero();
        assertThat(snap.inputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(snap.outputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void meterSnapshotCapturesInputAndOutputLevels() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setThresholdDb(-20.0);
        c.setRatio(4.0);
        c.setAttackMs(0.1);

        int n = 4096;
        float[][] in = sineMono(1_000.0, dbToLinear(-10.0), n);
        c.process(in, new float[1][n], n);

        var snap = c.getMeterSnapshot();
        assertThat(snap.gainReductionDb()).isLessThan(0.0);
        assertThat(snap.inputLevelDb())
                .as("input peak level should be close to -10 dBFS")
                .isCloseTo(-10.0, org.assertj.core.data.Offset.offset(0.5));
        // Output is compressed + (by default makeup=0) so it's below input.
        assertThat(snap.outputLevelDb()).isLessThan(snap.inputLevelDb());
    }

    @Test
    void supportsDoublePrecisionProcessing() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        c.setThresholdDb(-20.0);
        c.setAttackMs(0.1);
        assertThat(c.supportsDouble()).isTrue();

        int n = 2048;
        double[][] in = new double[1][n];
        for (int i = 0; i < n; i++) {
            in[0][i] = 0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * i / SAMPLE_RATE);
        }
        double[][] out = new double[1][n];
        c.processDouble(in, out, n);
        assertThat(c.getGainReductionDb()).isLessThan(0.0);
    }

    @Test
    void autoReleaseFlagToggles() {
        BusCompressorProcessor c = new BusCompressorProcessor(1, SAMPLE_RATE);
        assertThat(c.isReleaseAuto()).isFalse();
        c.setReleaseAuto(true);
        assertThat(c.isReleaseAuto()).isTrue();

        // Processing should still run cleanly with AUTO release engaged.
        float[][] buf = sineMono(1_000.0, 0.8f, 1024);
        c.process(buf, new float[1][1024], 1024);
        assertThat(c.getGainReductionDb()).isLessThanOrEqualTo(0.0);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    private static float[][] sineMono(double freqHz, double amplitude, int n) {
        float[][] buf = new float[1][n];
        for (int i = 0; i < n; i++) {
            buf[0][i] = (float) (amplitude * Math.sin(2.0 * Math.PI * freqHz * i / SAMPLE_RATE));
        }
        return buf;
    }

    private static float[][] sineStereo(double freqHz, double amplitude, int n) {
        float[][] buf = new float[2][n];
        for (int i = 0; i < n; i++) {
            double s = amplitude * Math.sin(2.0 * Math.PI * freqHz * i / SAMPLE_RATE);
            buf[0][i] = (float) s;
            buf[1][i] = (float) s;
        }
        return buf;
    }

    private static float[][] deepCopy(float[][] src) {
        float[][] copy = new float[src.length][];
        for (int ch = 0; ch < src.length; ch++) {
            copy[ch] = src[ch].clone();
        }
        return copy;
    }
}
