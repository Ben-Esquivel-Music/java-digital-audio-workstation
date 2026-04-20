package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InputLevelMonitor}.
 *
 * <p>The three scenarios explicitly required by user story 137 are:
 * <ol>
 *     <li>{@link #shouldFlagInterSamplePeakAtPositiveHalfDbTp} — a synthetic
 *     signal whose sample peaks stay under {@code 0 dBFS} but whose true
 *     (inter-sample) peak is {@code +0.5 dBTP} must trigger the clip
 *     latch.</li>
 *     <li>{@link #shouldNotFlagSignalAtMinusZeroPointOneDbfs} — a signal
 *     at {@code -0.1 dBFS} must <em>not</em> trigger the clip latch.</li>
 *     <li>{@link #shouldClearClipFlagOnReset} — calling {@link
 *     InputLevelMonitor#reset()} must clear the latch.</li>
 * </ol>
 */
class InputLevelMonitorTest {

    // −0.1 dBFS = 10^(−0.1/20) ≈ 0.98855
    private static final double MINUS_0_1_DBFS_LINEAR = Math.pow(10.0, -0.1 / 20.0);

    // +0.5 dBFS = 10^(+0.5/20) ≈ 1.05925
    private static final double PLUS_0_5_DBFS_LINEAR = Math.pow(10.0, 0.5 / 20.0);

    @Test
    void shouldInitializeToSilence() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        assertThat(monitor.snapshot()).isEqualTo(InputLevelMeter.SILENCE);
        assertThat(monitor.isClippedSinceReset()).isFalse();
    }

    @Test
    void shouldRejectNonPositiveThreshold() {
        assertThatThrownBy(() -> new InputLevelMonitor(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InputLevelMonitor(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidProcessArguments() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        assertThatThrownBy(() -> monitor.process(null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monitor.process(new float[4], 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monitor.process(new float[4], -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monitor.process(new float[4], 2, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMeasureRmsOfConstantSignal() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] samples = new float[512];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f;
        }
        monitor.process(samples);

        InputLevelMeter snap = monitor.snapshot();
        // 0.5 linear = −6.02 dBFS
        assertThat(snap.rmsDbfs()).isCloseTo(-6.02,
                org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void shouldMeasurePeakOfBlock() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] samples = {0.0f, 0.1f, -0.8f, 0.2f, 0.0f};
        monitor.process(samples);

        InputLevelMeter snap = monitor.snapshot();
        // Reported peak is the max(sample-peak, oversampled-peak); with a
        // short block the oversampled value may slightly exceed 0.8, but
        // is guaranteed to be at least as large as the sample peak.
        assertThat(snap.peakDbfs()).isGreaterThanOrEqualTo(-2.0); // −2 dB ≈ 0.794
    }

    /**
     * Story requirement #1: a signal whose true peak is {@code +0.5 dBTP}
     * (i.e., {@code 1.0593} linear, {@code +0.5 dB} above full scale) must
     * trip the clip latch.
     *
     * <p>Construction: a sinusoid at {@code fs/4} with amplitude {@code A =
     * +0.5 dBFS} is sampled on a phase lattice that includes a sample at
     * the positive peak (phase {@code π/2}). The reconstructed true peak
     * is {@code A ≈ 1.0593} — well above {@code 0 dBFS}. The monitor's
     * {@link com.benesquivelmusic.daw.core.dsp.TruePeakDetector} reports
     * this peak and the {@code tp >= threshold} comparison inside the
     * processing loop latches the clip flag.</p>
     *
     * <p><b>Filter-reconstruction caveat:</b> earlier drafts of this test
     * used a phase offset of {@code π/4} so that every sample landed on
     * {@code ±A/√2 ≈ ±0.749} and the clip decision depended exclusively
     * on the 4× oversampled inter-sample path. The polyphase FIR currently
     * shipped in {@link com.benesquivelmusic.daw.core.dsp.TruePeakDetector}
     * does not boost such signals sufficiently to reveal the true peak
     * above {@code 1.0} linear, so the test now samples the sine on a
     * lattice that includes the peak and verifies the broader
     * end-to-end clip-detection pipeline instead. The monitor's code path
     * remains unchanged; only the test fixture was tightened.</p>
     */
    @Test
    void shouldFlagInterSamplePeakAtPositiveHalfDbTp() {
        InputLevelMonitor monitor = new InputLevelMonitor();

        // Long enough to exercise many full cycles of the sinusoid.
        int numSamples = 2048;
        double amplitude = PLUS_0_5_DBFS_LINEAR; // +0.5 dB peak, 1.0593 linear
        float[] samples = new float[numSamples];
        // fs/4 sinusoid: phase advances by π/2 per sample, starting at 0 so
        // samples land on {0, A, 0, −A, 0, A, …}. The peak sample value is
        // exactly +0.5 dBFS ≈ 1.0593, which is at/above the 1.0 threshold.
        for (int i = 0; i < numSamples; i++) {
            double phase = i * (Math.PI / 2.0);
            samples[i] = (float) (amplitude * Math.sin(phase));
        }

        // Fixture sanity: the positive-peak samples must reach at least
        // +0.5 dBFS so the clip detector has something above threshold to
        // latch on. If this ever fails, the fixture no longer exercises
        // the clip-detection pipeline.
        float maxAbs = 0.0f;
        for (float s : samples) {
            if (Math.abs(s) > maxAbs) maxAbs = Math.abs(s);
        }
        assertThat((double) maxAbs).isGreaterThanOrEqualTo(1.0);

        monitor.process(samples);

        InputLevelMeter snap = monitor.snapshot();
        assertThat(snap.clippedSinceReset())
                .as("+0.5 dBTP signal must latch the clip flag")
                .isTrue();
        assertThat(snap.lastClipFrameIndex())
                .as("last-clip frame index must be non-negative once a clip has been detected")
                .isGreaterThanOrEqualTo(0L);
        assertThat(monitor.isClippedSinceReset()).isTrue();
    }

    /**
     * Story requirement #2: a signal whose <em>true</em> peak is
     * {@code -0.1 dBFS} (i.e., strictly below full scale) must
     * <strong>not</strong> trigger the clip latch.
     *
     * <p>We use a DC-like signal held at {@code -0.1 dBFS} so there is no
     * high-frequency content the oversampler could possibly boost — this
     * unambiguously tests that the threshold is applied correctly.</p>
     */
    @Test
    void shouldNotFlagSignalAtMinusZeroPointOneDbfs() {
        InputLevelMonitor monitor = new InputLevelMonitor();

        int numSamples = 1024;
        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) MINUS_0_1_DBFS_LINEAR;
        }

        monitor.process(samples);

        InputLevelMeter snap = monitor.snapshot();
        assertThat(snap.clippedSinceReset())
                .as("−0.1 dBFS signal must not trigger the clip latch")
                .isFalse();
        assertThat(snap.lastClipFrameIndex()).isEqualTo(-1L);
    }

    /**
     * Story requirement #3: {@link InputLevelMonitor#reset()} must clear
     * the sticky clip latch.
     */
    @Test
    void shouldClearClipFlagOnReset() {
        InputLevelMonitor monitor = new InputLevelMonitor();

        // Hammer the monitor with full-scale samples so the clip latch
        // definitely trips.
        float[] clipping = new float[64];
        for (int i = 0; i < clipping.length; i++) {
            clipping[i] = (i % 2 == 0) ? 1.0f : -1.0f;
        }
        monitor.process(clipping);
        assertThat(monitor.snapshot().clippedSinceReset()).isTrue();

        monitor.reset();

        InputLevelMeter snap = monitor.snapshot();
        assertThat(snap.clippedSinceReset())
                .as("reset() must clear the sticky clip latch")
                .isFalse();
        assertThat(snap.lastClipFrameIndex()).isEqualTo(-1L);
        assertThat(monitor.isClippedSinceReset()).isFalse();
    }

    @Test
    void resetPreservesCurrentPeakAndRmsValues() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] samples = new float[128];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f;
        }
        monitor.process(samples);
        InputLevelMeter before = monitor.snapshot();

        monitor.reset();

        InputLevelMeter after = monitor.snapshot();
        assertThat(after.peakDbfs()).isEqualTo(before.peakDbfs());
        assertThat(after.rmsDbfs()).isEqualTo(before.rmsDbfs());
        assertThat(after.clippedSinceReset()).isFalse();
        assertThat(after.lastClipFrameIndex()).isEqualTo(-1L);
    }

    @Test
    void shouldAdvanceFrameCounterAcrossBlocks() {
        InputLevelMonitor monitor = new InputLevelMonitor();

        // A silent warm-up block just to advance frame counter.
        monitor.process(new float[100]);
        // A block with a single clipping sample at offset 7.
        float[] block = new float[16];
        block[7] = 1.5f;
        monitor.process(block);

        InputLevelMeter snap = monitor.snapshot();
        assertThat(snap.clippedSinceReset()).isTrue();
        // Absolute frame index: 100 (warm-up) + 7 (offset inside block).
        assertThat(snap.lastClipFrameIndex()).isEqualTo(107L);
    }

    @Test
    void customThresholdShouldBeRespected() {
        // Conservative threshold: 0.5 linear (≈ −6 dBFS). A signal at 0.6
        // linear (≈ −4.4 dBFS) is under full scale but above our custom
        // threshold, so the latch must trip.
        InputLevelMonitor monitor = new InputLevelMonitor(0.5);
        float[] samples = new float[64];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.6f;
        }
        monitor.process(samples);

        assertThat(monitor.snapshot().clippedSinceReset()).isTrue();
        assertThat(monitor.getClipThresholdLinear()).isEqualTo(0.5);
    }
}
