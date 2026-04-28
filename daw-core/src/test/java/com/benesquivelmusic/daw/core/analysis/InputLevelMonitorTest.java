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
     * trip the clip latch, even when every individual sample is strictly
     * below {@code 0 dBFS}.
     *
     * <p>Construction: a sinusoid at {@code fs/4} with amplitude {@code A}
     * sampled on phase {@code π/4} so the sample lattice lands on
     * {@code ±A/√2}. Choosing {@code A = +0.5 dBFS} makes the
     * <em>continuous</em> peak {@code +0.5 dBTP} while sample peaks are
     * {@code A/√2 ≈ −2.51 dBFS} — well below {@code 0 dBFS}. With the
     * BS.1770-4 4× oversampler in
     * {@link com.benesquivelmusic.daw.core.dsp.TruePeakDetector} the
     * inter-sample peak is recovered and the {@code tp >= threshold} check
     * inside {@link InputLevelMonitor} latches the clip flag.</p>
     */
    @Test
    void shouldFlagInterSamplePeakAtPositiveHalfDbTp() {
        InputLevelMonitor monitor = new InputLevelMonitor();

        // Long enough to exercise many full cycles of the sinusoid.
        int numSamples = 2048;
        // Continuous amplitude = +0.5 dBFS (1.0593). Sampling at phase π/4
        // makes every sample land on ±A/√2 ≈ ±0.749 (≈ −2.51 dBFS), so the
        // clip detection MUST go through the inter-sample peak path.
        double continuousAmplitude = PLUS_0_5_DBFS_LINEAR;
        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double phase = i * (Math.PI / 2.0) + Math.PI / 4.0;
            samples[i] = (float) (continuousAmplitude * Math.sin(phase));
        }

        // Fixture sanity: every sample must stay strictly below 0 dBFS so
        // this genuinely tests the inter-sample-peak path (not a sample-
        // peak short-circuit).
        float maxAbs = 0.0f;
        for (float s : samples) {
            if (Math.abs(s) > maxAbs) maxAbs = Math.abs(s);
        }
        assertThat((double) maxAbs)
                .as("fixture sanity: sample peaks must be below 0 dBFS")
                .isLessThan(1.0);

        monitor.process(samples);

        InputLevelMeter snap = monitor.snapshot();
        assertThat(snap.clippedSinceReset())
                .as("+0.5 dBTP signal must latch the clip flag via the inter-sample peak path")
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

    // ── processInputChannels tests ──────────────────────────────────────────

    @Test
    void processInputChannelsShouldRejectNullChannels() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        assertThatThrownBy(() -> monitor.processInputChannels(null, 0, 1, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processInputChannelsShouldRejectNonPositiveNumFrames() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[][] channels = {new float[64], new float[64]};
        assertThatThrownBy(() -> monitor.processInputChannels(channels, 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monitor.processInputChannels(channels, 0, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processInputChannelsShouldRejectOutOfRangeChannelSpec() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[][] channels = {new float[64], new float[64]};
        // negative firstChannel
        assertThatThrownBy(() -> monitor.processInputChannels(channels, -1, 1, 32))
                .isInstanceOf(IllegalArgumentException.class);
        // zero channelCount
        assertThatThrownBy(() -> monitor.processInputChannels(channels, 0, 0, 32))
                .isInstanceOf(IllegalArgumentException.class);
        // firstChannel + channelCount exceeds array length
        assertThatThrownBy(() -> monitor.processInputChannels(channels, 1, 2, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processInputChannelsShouldMeasurePeakOfMonoSlice() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] ch0 = new float[32];
        ch0[10] = 0.75f;
        float[][] channels = {ch0};
        monitor.processInputChannels(channels, 0, 1, 32);

        InputLevelMeter snap = monitor.snapshot();
        // Peak must be at least 0.75 linear ≈ −2.5 dBFS
        assertThat(snap.peakDbfs()).isGreaterThanOrEqualTo(20.0 * Math.log10(0.75) - 0.01);
        assertThat(snap.clippedSinceReset()).isFalse();
    }

    @Test
    void processInputChannelsShouldUseLouderChannelForClipDecision() {
        // Only the second channel (index 1) exceeds the clip threshold.
        // The monitor should still latch the clip flag.
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] ch0 = new float[64];
        float[] ch1 = new float[64];
        for (int i = 0; i < ch1.length; i++) {
            ch0[i] = 0.3f;   // well below threshold
            ch1[i] = 1.5f;   // above full scale
        }
        float[][] channels = {ch0, ch1};
        monitor.processInputChannels(channels, 0, 2, 64);

        assertThat(monitor.snapshot().clippedSinceReset())
                .as("clip on louder channel must latch the flag")
                .isTrue();
    }

    @Test
    void processInputChannelsShouldNotClipWhenBothChannelsBelowThreshold() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] ch0 = new float[64];
        float[] ch1 = new float[64];
        for (int i = 0; i < ch0.length; i++) {
            ch0[i] = 0.5f;
            ch1[i] = -0.4f;
        }
        float[][] channels = {ch0, ch1};
        monitor.processInputChannels(channels, 0, 2, 64);

        assertThat(monitor.snapshot().clippedSinceReset())
                .as("no channel exceeds threshold; clip flag must remain clear")
                .isFalse();
    }

    @Test
    void processInputChannelsShouldAdvanceFrameCounter() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] ch0 = new float[100];
        float[][] channels = {ch0};

        // Warm-up: 100 frames silent
        monitor.processInputChannels(channels, 0, 1, 100);

        // Second block: clip at frame 7
        float[] ch0b = new float[16];
        ch0b[7] = 1.5f;
        float[][] channels2 = {ch0b};
        monitor.processInputChannels(channels2, 0, 1, 16);

        InputLevelMeter snap = monitor.snapshot();
        assertThat(snap.clippedSinceReset()).isTrue();
        assertThat(snap.lastClipFrameIndex()).isEqualTo(107L);
    }

    @Test
    void processInputChannelsShouldRespectChannelOffset() {
        // firstChannel=1 selects only ch1; ch0 has clipping values that
        // must be ignored.
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] ch0 = new float[32];
        float[] ch1 = new float[32];
        for (int i = 0; i < 32; i++) {
            ch0[i] = 1.5f;  // above threshold — but ch0 is NOT selected
            ch1[i] = 0.3f;  // below threshold
        }
        float[][] channels = {ch0, ch1};
        monitor.processInputChannels(channels, 1, 1, 32);

        assertThat(monitor.snapshot().clippedSinceReset())
                .as("ch0 is outside the selected range; clip flag must stay clear")
                .isFalse();
    }
}
