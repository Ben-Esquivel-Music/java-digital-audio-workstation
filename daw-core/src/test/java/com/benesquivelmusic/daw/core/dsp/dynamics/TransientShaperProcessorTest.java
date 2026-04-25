package com.benesquivelmusic.daw.core.dsp.dynamics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for {@link TransientShaperProcessor}.
 *
 * <p>The signal-shape tests construct a synthetic kick-drum-style impulse
 * (short transient onset followed by an exponentially decaying tail) and
 * verify the algorithm's hallmark guarantees:</p>
 * <ul>
 *   <li>{@code attack=+100%} measurably increases the peak-to-RMS ratio,
 *       i.e., makes the transient stand prouder against the body.</li>
 *   <li>{@code attack=-100%} measurably reduces the peak-to-RMS ratio,
 *       i.e., softens the transient onset.</li>
 *   <li>{@code sustain=+100%} extends the decay tail, measured as a higher
 *       RMS energy in the tail region.</li>
 *   <li>The detection is level-independent — halving the input magnitude
 *       should not materially change the relative gain trajectory.</li>
 * </ul>
 */
class TransientShaperProcessorTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int    BLOCK       = 4096;

    // ── Construction & parameter validation ──────────────────────────────────

    @Test
    void shouldUseNeutralDefaults() {
        TransientShaperProcessor t = new TransientShaperProcessor(2, SAMPLE_RATE);
        assertThat(t.getInputChannelCount()).isEqualTo(2);
        assertThat(t.getOutputChannelCount()).isEqualTo(2);
        assertThat(t.getAttackPercent()).isEqualTo(0.0);
        assertThat(t.getSustainPercent()).isEqualTo(0.0);
        assertThat(t.getOutputDb()).isEqualTo(0.0);
        assertThat(t.isInputMonitor()).isFalse();
        assertThat(t.getChannelLink()).isEqualTo(1.0);
        assertThat(t.getSampleRate()).isEqualTo(SAMPLE_RATE);
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new TransientShaperProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransientShaperProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectOutOfRangeParameters() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        assertThatThrownBy(() -> t.setAttackPercent(-101.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setAttackPercent(101.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setSustainPercent(101.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setOutputDb(13.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setOutputDb(-13.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setChannelLink(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setChannelLink(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptParameterRangeBoundaries() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        t.setAttackPercent(-100.0);   t.setAttackPercent(100.0);
        t.setSustainPercent(-100.0);  t.setSustainPercent(100.0);
        t.setOutputDb(-12.0);         t.setOutputDb(12.0);
        t.setChannelLink(0.0);        t.setChannelLink(1.0);
        assertThat(t.getChannelLink()).isEqualTo(1.0);
        assertThat(t.getOutputDb()).isEqualTo(12.0);
    }

    // ── Bypass / pass-through ────────────────────────────────────────────────

    @Test
    void shouldPassThroughAtNeutralSettings() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        float[][] in  = kickImpulse(1, BLOCK);
        float[][] out = new float[1][BLOCK];

        t.process(in, out, BLOCK);

        // With attack=sustain=0% and output=0 dB the shaper is unity-gain (clamped).
        for (int i = 0; i < BLOCK; i++) {
            assertThat(out[0][i]).isCloseTo(in[0][i], org.assertj.core.data.Offset.offset(1.0e-6f));
        }
    }

    // ── Transient shaping behaviour on a kick-drum-style impulse ────────────

    @Test
    void positiveAttackShouldIncreasePeakToRmsRatio() {
        double dryRatio    = peakToRmsRatio(processWith(  0.0,   0.0));
        double pluckedUp   = peakToRmsRatio(processWith(100.0,   0.0));

        assertThat(pluckedUp)
                .as("attack=+100%% must boost transient relative to body")
                .isGreaterThan(dryRatio * 1.05);  // at least 5 % more punch
    }

    @Test
    void negativeAttackShouldReducePeakToRmsRatio() {
        double dryRatio   = peakToRmsRatio(processWith(   0.0,   0.0));
        double softened   = peakToRmsRatio(processWith(-100.0,   0.0));

        assertThat(softened)
                .as("attack=-100%% must soften the transient relative to body")
                .isLessThan(dryRatio * 0.98);  // at least a small reduction
    }

    @Test
    void positiveSustainShouldExtendDecayTail() {
        float[] dry      = processWith(0.0, 0.0);
        float[] extended = processWith(0.0, 100.0);

        // Measure RMS in the late tail region (after the body has decayed).
        int tailStart = BLOCK / 2;
        double dryTailRms      = rms(dry,      tailStart, BLOCK);
        double extendedTailRms = rms(extended, tailStart, BLOCK);

        assertThat(extendedTailRms)
                .as("sustain=+100%% must extend the decay tail RMS energy")
                .isGreaterThan(dryTailRms * 1.05);
    }

    @Test
    void detectionShouldBeLevelIndependent() {
        // Run +100% attack on a hot signal and on a -20 dB scaled copy of the
        // same signal. The peak-to-RMS *ratio* (a level-independent metric)
        // should be very close because the algorithm tracks envelope shape.
        float[] hot    = processWith(100.0, 0.0, 1.0);
        float[] quiet  = processWith(100.0, 0.0, 0.1);
        double hotRatio   = peakToRmsRatio(hot);
        double quietRatio = peakToRmsRatio(quiet);

        // Allow generous tolerance; pure envelope ratios should differ by < 5 %.
        assertThat(quietRatio).isCloseTo(hotRatio,
                org.assertj.core.data.Offset.offset(hotRatio * 0.05));
    }

    // ── Output gain / monitor / link ────────────────────────────────────────

    @Test
    void outputGainShouldScaleOutputLinearly() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        t.setOutputDb(6.0);  // ≈ 2× linear

        float[][] in  = constSignal(1, BLOCK, 0.25f);
        float[][] out = new float[1][BLOCK];
        t.process(in, out, BLOCK);

        // Settle past initial envelope rise — verify mid-buffer sample.
        float observed = out[0][BLOCK - 1];
        assertThat((double) observed)
                .isCloseTo(0.25 * Math.pow(10.0, 6.0 / 20.0),
                        org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void inputMonitorShouldBypassDryAudioAndExposeDetectionEnvelope() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        t.setInputMonitor(true);

        float[][] in  = kickImpulse(1, BLOCK);
        float[][] out = new float[1][BLOCK];
        t.process(in, out, BLOCK);

        // A non-trivial fraction of the buffer must be non-zero (the detection
        // envelope is audible whenever a transient is present).
        int nonZero = 0;
        for (float v : out[0]) if (Math.abs(v) > 1.0e-4f) nonZero++;
        assertThat(nonZero).isGreaterThan(BLOCK / 16);

        // ...but the output is NOT identical to the dry input.
        boolean differs = false;
        for (int i = 0; i < BLOCK; i++) {
            if (Math.abs(out[0][i] - in[0][i]) > 1.0e-3f) { differs = true; break; }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void resetShouldClearEnvelopeState() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        t.setAttackPercent(100.0);
        float[][] in  = kickImpulse(1, BLOCK);
        float[][] out = new float[1][BLOCK];
        t.process(in, out, BLOCK);

        t.reset();
        assertThat(t.getTransientDetectionDb()).isEqualTo(0.0);
        assertThat(t.getMeterSnapshot().inputLevelDb()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void meterSnapshotShouldReportPeakLevels() {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        float[][] in  = kickImpulse(1, BLOCK);
        float[][] out = new float[1][BLOCK];
        t.process(in, out, BLOCK);

        var snap = t.getMeterSnapshot();
        assertThat(snap.inputLevelDb()).isGreaterThan(Double.NEGATIVE_INFINITY);
        assertThat(snap.outputLevelDb()).isGreaterThan(Double.NEGATIVE_INFINITY);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static float[] processWith(double attackPct, double sustainPct) {
        return processWith(attackPct, sustainPct, 1.0);
    }

    private static float[] processWith(double attackPct, double sustainPct, double scale) {
        TransientShaperProcessor t = new TransientShaperProcessor(1, SAMPLE_RATE);
        t.setAttackPercent(attackPct);
        t.setSustainPercent(sustainPct);

        float[][] in  = kickImpulse(1, BLOCK);
        if (scale != 1.0) {
            for (int i = 0; i < BLOCK; i++) in[0][i] *= (float) scale;
        }
        float[][] out = new float[1][BLOCK];
        t.process(in, out, BLOCK);
        return out[0];
    }

    /**
     * Generates a kick-drum-style impulse: a short attack burst followed by an
     * exponentially decaying low-frequency sine tail.
     */
    private static float[][] kickImpulse(int channels, int frames) {
        float[][] data = new float[channels][frames];
        for (int n = 0; n < frames; n++) {
            // A 3-ms onset burst (white-noise click) then a 60 Hz decaying sine.
            double tSec    = n / SAMPLE_RATE;
            double clickEnv = Math.exp(-tSec / 0.001);          // ~1 ms decay
            double click    = clickEnv * (((n * 2654435761L) & 0xFFFF) / 32768.0 - 1.0);
            double bodyEnv  = Math.exp(-tSec / 0.10);           // ~100 ms decay
            double body     = bodyEnv * Math.sin(2.0 * Math.PI * 60.0 * tSec);
            double s        = 0.25 * click + 0.25 * body;
            for (int ch = 0; ch < channels; ch++) {
                data[ch][n] = (float) s;
            }
        }
        return data;
    }

    private static float[][] constSignal(int channels, int frames, float value) {
        float[][] data = new float[channels][frames];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) data[ch][i] = value;
        }
        return data;
    }

    private static double peakToRmsRatio(float[] x) {
        double peak = 0.0;
        for (float v : x) {
            double a = Math.abs(v);
            if (a > peak) peak = a;
        }
        double rms = rms(x, 0, x.length);
        return (rms > 0) ? peak / rms : 0.0;
    }

    private static double rms(float[] x, int from, int to) {
        double sum = 0.0;
        int n = to - from;
        for (int i = from; i < to; i++) sum += x[i] * (double) x[i];
        return (n > 0) ? Math.sqrt(sum / n) : 0.0;
    }
}
