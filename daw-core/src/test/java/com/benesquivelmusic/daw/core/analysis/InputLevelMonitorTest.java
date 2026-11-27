package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private static final double MINUS_20_DBFS_LINEAR = Math.pow(10.0, -20.0 / 20.0);

    /** The render drive a device loss lost, and the one the next stream's start builds. */
    private static final long LOST_DRIVE = 3L;
    private static final long NEW_DRIVE = 4L;

    /**
     * Bounds the test thread's own waits in a test with real threads, so a stall fails the test as a stall;
     * it releases no thread.
     */
    private static final Duration GUARD = Duration.ofSeconds(10);

    @Test
    void shouldInitializeToSilence() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        assertThat(monitor.snapshot()).isEqualTo(InputLevelMeter.SILENCE);
        assertThat(monitor.isClippedSinceReset()).isFalse();
    }

    @Test
    void endedSourceMasksAValuePublishedByAnInFlightBlock() {
        var monitor = new InputLevelMonitor();
        var source = new InputSourceAvailability();
        monitor.setSourceAvailability(source);
        source.markUnavailable();
        monitor.process(new float[]{0.5f, 0.5f, 0.5f});
        assertThat(monitor.isRoutingUnavailable()).isTrue();
        assertThat(monitor.snapshot()).isSameAs(InputLevelMeter.SILENCE);
    }

    @Test
    void unavailableRoutingMasksAValueRepublishedAfterTheRoutingWasCleared() {
        var monitor = new InputLevelMonitor();
        monitor.process(new float[]{0.25f, 0.25f, 0.25f});
        monitor.setRoutingUnavailable(true);
        // An already-running input callback can publish after the control thread clears latest.
        monitor.processInputChannels(new float[][]{{0.5f, 0.5f, 0.5f}, {0.25f, 0.25f, 0.25f}}, 0, 2, 3);

        assertThat(monitor.isRoutingUnavailable()).isTrue();
        assertThat(monitor.snapshot()).isSameAs(InputLevelMeter.SILENCE);

        monitor.setRoutingUnavailable(false);
        assertThat(monitor.snapshot().rmsDbfs()).isGreaterThan(-10);
    }

    @Test
    void previousSourceTerminationCannotSilenceAReboundMonitor() {
        var monitor = new InputLevelMonitor();
        var previous = new InputSourceAvailability();
        monitor.setSourceAvailability(previous);
        monitor.setSourceAvailability(new InputSourceAvailability());
        monitor.process(new float[]{0.5f, 0.5f, 0.5f});
        var replacementLevel = monitor.snapshot();
        previous.markUnavailable();
        assertThat(monitor.isRoutingUnavailable()).isFalse();
        assertThat(monitor.snapshot()).isSameAs(replacementLevel);
        assertThat(replacementLevel.peakDbfs()).isGreaterThan(-10);
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
    void silenceDropsTheLevelToTheFloorAndKeepsTheClipLatch() {
        InputLevelMonitor monitor = new InputLevelMonitor();
        float[] clipping = new float[64];
        for (int i = 0; i < clipping.length; i++) {
            clipping[i] = (i % 2 == 0) ? 1.0f : -1.0f;
        }
        monitor.process(clipping);
        InputLevelMeter loud = monitor.snapshot();
        assertThat(loud.clippedSinceReset()).isTrue();

        monitor.silence();

        InputLevelMeter silent = monitor.snapshot();
        assertThat(silent.peakDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(silent.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(silent.clippedSinceReset()).as("a lost device keeps its clip LED lit (E64)").isTrue();
        assertThat(silent.lastClipFrameIndex()).isEqualTo(loud.lastClipFrameIndex());
        assertThat(monitor.isClippedSinceReset()).isTrue();
    }

    @Test
    void aBlockTheLostStreamRendersLateShowsNoLevelAndStillLatchesItsClip() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        monitor.process(constant(0.5f, 256));
        assertThat(monitor.snapshot().peakDbfs()).as("premise: a loud level").isGreaterThan(-12.0);
        assertThat(monitor.isClippedSinceReset()).as("premise: no clip yet").isFalse();

        registry.silenceAll(LOST_DRIVE);
        monitor.process(interSamplePeakAtPlusHalfDbTp());

        InputLevelMeter late = monitor.snapshot();
        assertThat(late.peakDbfs()).as("no block of the lost stream shows a level (E175)")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(late.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(late.clippedSinceReset()).as("its inter-sample overshoot still lights the clip LED (E175)")
                .isTrue();
        assertThat(late.lastClipFrameIndex()).as("the clip is the late block's").isGreaterThanOrEqualTo(256L);
    }

    @Test
    void theMeterStaysAtTheFloorUntilTheNewStreamDeliversItsFirstBlock() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        monitor.process(constant(0.5f, 256));
        registry.silenceAll(LOST_DRIVE);
        monitor.process(constant(0.7f, 256));

        assertThat(monitor.snapshot().peakDbfs()).as("no block of a new stream yet")
                .isEqualTo(InputLevelMeter.DB_FLOOR);

        registry.liftAll(NEW_DRIVE);
        assertThat(monitor.snapshot().peakDbfs()).as("a new stream's start alone shows no level (E175)")
                .isEqualTo(InputLevelMeter.DB_FLOOR);

        monitor.process(constant(0.5f, 256));
        assertThat(monitor.snapshot().peakDbfs()).as("the new stream's first block shows its own level")
                .isCloseTo(-6.02, org.assertj.core.data.Offset.offset(0.1));
    }

    /**
     * A late block that ends near full scale leaves the true-peak detector's
     * history near full scale; were it kept, the junction with a quiet first
     * block of the new stream would interpolate an overshoot above 0 dBFS.
     */
    @Test
    void theNewStreamsFirstBlockReadsItsOwnLevelWithNothingOfTheLostStreamInIt() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.silenceAll(LOST_DRIVE);
        monitor.process(riseToTheCrestOfASlowSine(0.99));
        assertThat(monitor.isClippedSinceReset()).as("premise: the late block does not clip").isFalse();

        registry.liftAll(NEW_DRIVE);
        monitor.process(sineFromZero(Math.pow(10.0, -20.0 / 20.0), 480));

        InputLevelMeter first = monitor.snapshot();
        assertThat(first.peakDbfs()).as("the first block's own peak (E175)")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(first.rmsDbfs()).as("the first block's own RMS")
                .isCloseTo(-23.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(first.clippedSinceReset()).as("no clip from the junction with the lost stream").isFalse();
    }

    /** The loss's FX delivery can come after a reconnect has already built the next drive (ruling E185). */
    @Test
    void aDriveBuiltBeforeTheLossIsDeliveredStartsWithNothingOfTheLostStream() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.process(riseToTheCrestOfASlowSine(0.99));
        assertThat(monitor.isClippedSinceReset()).as("premise: the lost drive's last block does not clip").isFalse();

        registry.liftAll(NEW_DRIVE);
        assertThat(monitor.snapshot().peakDbfs()).as("the new stream's start shows the floor (E185)")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        registry.silenceAll(LOST_DRIVE);
        monitor.process(sineFromZero(Math.pow(10.0, -20.0 / 20.0), 480));

        InputLevelMeter first = monitor.snapshot();
        assertThat(first.peakDbfs()).as("the first block's own peak")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(first.clippedSinceReset()).as("no clip from the junction with the lost stream").isFalse();
    }

    /**
     * Every stream start is a break in the signal: until its first block the
     * floor is all the meter has measured (ruling E185), with no loss at all.
     */
    @Test
    void everyStreamStartShowsTheFloorKeepingTheLatchAndMeasuresItsFirstBlockOnItsOwn() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.process(interSamplePeakAtPlusHalfDbTp());
        monitor.process(riseToTheCrestOfASlowSine(0.99));
        long clipFrame = monitor.snapshot().lastClipFrameIndex();
        assertThat(clipFrame).as("premise: the previous stream latched a clip").isNotNegative();

        registry.liftAll(NEW_DRIVE);
        InputLevelMeter atTheStart = monitor.snapshot();
        assertThat(atTheStart.peakDbfs()).as("the start shows the floor").isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(atTheStart.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(atTheStart.clippedSinceReset()).as("and keeps the clip latch").isTrue();
        assertThat(atTheStart.lastClipFrameIndex()).isEqualTo(clipFrame);

        monitor.process(sineFromZero(Math.pow(10.0, -20.0 / 20.0), 480));
        InputLevelMeter first = monitor.snapshot();
        assertThat(first.peakDbfs()).as("the first block's own peak")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(first.lastClipFrameIndex()).as("no new clip from the junction with the previous stream")
                .isEqualTo(clipFrame);
    }

    @Test
    void aClipResetDuringTheLossKeepsTheFloor() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.silenceAll(LOST_DRIVE);
        monitor.process(interSamplePeakAtPlusHalfDbTp());
        assertThat(monitor.isClippedSinceReset()).as("premise: the late block latched a clip").isTrue();

        monitor.reset();

        InputLevelMeter afterTheReset = monitor.snapshot();
        assertThat(afterTheReset.peakDbfs()).as("the reset keeps the late block's level hidden")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(afterTheReset.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(afterTheReset.clippedSinceReset()).as("and clears the latch").isFalse();
    }

    /**
     * A block an input device beside a closed stream delivers after a newer
     * render drive was built is refused whole (ruling E201): it shows no
     * level, latches no clip, sets no routing flag and leaves nothing in the
     * true-peak history, so the newer stream's first block, a −20 dBFS sine
     * starting at 0, reads its own level after a refused block with a
     * +0.5 dBTP inter-sample overshoot.
     */
    @Test
    void aBlockOfAnOlderDriveThanTheMetersWritesNothing() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(NEW_DRIVE);
        InputLevelMeter atTheStart = monitor.snapshot();

        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE);
        monitor.setRoutingUnavailable(true, LOST_DRIVE);

        assertThat(monitor.snapshot()).as("the closed stream's block shows no level (E201)").isSameAs(atTheStart);
        assertThat(monitor.isClippedSinceReset()).as("and latches no clip").isFalse();
        assertThat(monitor.isRoutingUnavailable()).as("and sets no routing flag").isFalse();

        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480, NEW_DRIVE);
        InputLevelMeter first = monitor.snapshot();
        assertThat(first.peakDbfs()).as("nothing of the refused block in the true-peak history")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(first.clippedSinceReset()).as("no clip from a junction with it").isFalse();
    }

    /**
     * A block already being measured when a newer render drive is built
     * completes into the closed stream's state, which nothing shows (ruling
     * E201).
     */
    @Test
    void aBlockMeasuredAcrossTheBuildOfANewerDriveCompletesIntoTheClosedStream() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);

        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE,
                () -> registry.liftAll(NEW_DRIVE));

        InputLevelMeter atTheStart = monitor.snapshot();
        assertThat(atTheStart.peakDbfs()).as("the newer stream's start shows the floor")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(atTheStart.clippedSinceReset()).as("and no clip of the closed stream's block (E201)").isFalse();
        assertThat(monitor.isClippedSinceReset()).as("the clip LED stays dark").isFalse();

        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480, NEW_DRIVE);
        InputLevelMeter first = monitor.snapshot();
        assertThat(first.peakDbfs()).as("nothing of that block in the newer stream's true-peak history")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(first.clippedSinceReset()).as("and still no clip").isFalse();
    }

    /** The latch carried into a new stream is the one the meter held at that build (rulings E185, E201). */
    @Test
    void theLatchHeldAtABuildIsCarriedAndOneTheClosedStreamSetsAfterItIsNot() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE);
        long heldAtTheBuild = monitor.snapshot().lastClipFrameIndex();
        assertThat(heldAtTheBuild).as("premise: the stream latched a clip").isNotNegative();

        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE,
                () -> registry.liftAll(NEW_DRIVE));

        InputLevelMeter atTheStart = monitor.snapshot();
        assertThat(atTheStart.peakDbfs()).as("the newer stream's start shows the floor")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(atTheStart.clippedSinceReset()).as("with the latch held at the build").isTrue();
        assertThat(atTheStart.lastClipFrameIndex()).as("not the one the closed stream set after it")
                .isEqualTo(heldAtTheBuild);
        assertThat(monitor.isClippedSinceReset()).isTrue();
    }

    /** A clip LED cleared before a newer drive is built stays cleared in the newer stream. */
    @Test
    void aClearBeforeABuildIsNotCarriedIntoTheNewerStream() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE);
        assertThat(monitor.isClippedSinceReset()).as("premise: a clip is latched").isTrue();

        monitor.reset();
        registry.liftAll(NEW_DRIVE);

        InputLevelMeter afterTheBuild = monitor.snapshot();
        assertThat(afterTheBuild.peakDbfs()).as("premise: the newer stream started")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(afterTheBuild.clippedSinceReset()).as("the cleared latch is not carried").isFalse();
        assertThat(afterTheBuild.lastClipFrameIndex()).isEqualTo(-1L);
        assertThat(monitor.isClippedSinceReset()).isFalse();
    }

    /** A clip LED cleared after a newer drive is built clears the latch that build carried. */
    @Test
    void aClearAfterABuildClearsTheLatchTheBuildCarried() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE);
        registry.liftAll(NEW_DRIVE);
        assertThat(monitor.isClippedSinceReset()).as("premise: the build carried the latch").isTrue();

        monitor.reset();

        InputLevelMeter afterTheClear = monitor.snapshot();
        assertThat(afterTheClear.peakDbfs()).as("the newer stream's level stays").isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(afterTheClear.clippedSinceReset()).as("the clear reaches the newer stream").isFalse();
        assertThat(afterTheClear.lastClipFrameIndex()).isEqualTo(-1L);
        assertThat(monitor.isClippedSinceReset()).isFalse();
    }

    /**
     * A clear of the latch and the install of a state exclude each other: a
     * clear made while a build holds the latch it copied waits for the
     * install and clears the installed state, so the person's clear is not
     * lost to the build. The build is held between its copy and its install
     * until the test releases it, in the {@code finally} at the latest; the
     * clear runs on a second thread, whose state is read until it reaches
     * {@code BLOCKED} or {@code TERMINATED}, whichever it reaches first.
     * {@link #GUARD} bounds the test thread's waits, and {@code @Timeout} the
     * whole test.
     */
    @Test
    @Timeout(60)
    void aClearMadeWhileABuildHoldsTheLatchItCopiedWaitsForTheInstallAndClearsTheInstalledState()
            throws InterruptedException {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE);
        assertThat(monitor.isClippedSinceReset()).as("premise: a clip is latched").isTrue();
        CountDownLatch copied = new CountDownLatch(1);
        CountDownLatch installed = new CountDownLatch(1);
        Thread build = Thread.ofPlatform().name("drive build").unstarted(
                () -> monitor.startWithDrive(NEW_DRIVE, false, () -> {
                    copied.countDown();
                    awaitTheRelease(installed);
                }));
        Thread clear = Thread.ofPlatform().name("clip LED clear").unstarted(monitor::reset);
        try {
            build.start();
            assertThat(copied.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("premise: the build copied the latch").isTrue();
            clear.start();
            Thread.State clearWhileTheBuildHoldsTheCopy = blockedOrTerminated(clear);
            installed.countDown();
            assertThat(build.join(GUARD)).as("the build finished").isTrue();
            assertThat(clear.join(GUARD)).as("the clear finished").isTrue();

            assertThat(monitor.isClippedSinceReset()).as("the clear reaches the installed state").isFalse();
            InputLevelMeter afterBoth = monitor.snapshot();
            assertThat(afterBoth.peakDbfs()).as("premise: the newer stream started")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(afterBoth.clippedSinceReset()).isFalse();
            assertThat(afterBoth.lastClipFrameIndex()).isEqualTo(-1L);
            assertThat(clearWhileTheBuildHoldsTheCopy).as("the clear waited for the install")
                    .isEqualTo(Thread.State.BLOCKED);
        } finally {
            installed.countDown();
            build.join(GUARD);
            clear.join(GUARD);
        }
    }

    /**
     * A floor at a device loss and the install of a state exclude each other:
     * a loss whose drive is unknown, made while a refusal of the input devices
     * beside the stream holds the level it copied, waits for the install and
     * floors the installed state, keeping its clip latch, so the bar does not
     * keep its last level (ruling E64). The refusal is held between its copy
     * and its install until the test releases it, in the {@code finally} at
     * the latest; the loss runs on a second thread, whose state is read until
     * it reaches {@code BLOCKED} or {@code TERMINATED}, whichever it reaches
     * first. {@link #GUARD} bounds the test thread's waits, and
     * {@code @Timeout} the whole test.
     */
    @Test
    @Timeout(60)
    void aFloorMadeWhileARefusalHoldsTheLevelItCopiedWaitsForTheInstallAndFloorsTheInstalledState()
            throws InterruptedException {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(LOST_DRIVE);
        monitor.processInputChannels(new float[][]{interSamplePeakAtPlusHalfDbTp()}, 0, 1, 256, LOST_DRIVE);
        // Two blocks, so the second reads its own level with no overshoot left in the true-peak history.
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480, LOST_DRIVE);
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480, LOST_DRIVE);
        InputLevelMeter beforeTheLoss = monitor.snapshot();
        CountDownLatch copied = new CountDownLatch(1);
        CountDownLatch installed = new CountDownLatch(1);
        Thread refusal = Thread.ofPlatform().name("refusal of the devices beside the stream").unstarted(
                () -> monitor.startWithDrive(LOST_DRIVE, true, () -> {
                    copied.countDown();
                    awaitTheRelease(installed);
                }));
        Thread loss = Thread.ofPlatform().name("device loss").unstarted(() -> registry.silenceAll(-1L));
        try {
            refusal.start();
            assertThat(copied.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("premise: the refusal copied the level").isTrue();
            loss.start();
            Thread.State lossWhileTheRefusalHoldsTheCopy = blockedOrTerminated(loss);
            installed.countDown();
            assertThat(refusal.join(GUARD)).as("the refusal finished").isTrue();
            assertThat(loss.join(GUARD)).as("the loss finished").isTrue();

            InputLevelMeter afterBoth = monitor.snapshot();
            assertThat(beforeTheLoss.peakDbfs()).as("premise: the meter showed the stream's level")
                    .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
            assertThat(beforeTheLoss.clippedSinceReset()).as("premise: with a clip latched").isTrue();
            assertThat(afterBoth.peakDbfs()).as("the loss floors the installed state")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(afterBoth.clippedSinceReset()).as("keeping its clip latch").isTrue();
            assertThat(afterBoth.lastClipFrameIndex()).isEqualTo(beforeTheLoss.lastClipFrameIndex());
            assertThat(lossWhileTheRefusalHoldsTheCopy).as("the floor waited for the install")
                    .isEqualTo(Thread.State.BLOCKED);
        } finally {
            installed.countDown();
            refusal.join(GUARD);
            loss.join(GUARD);
        }
    }

    /**
     * A clear of the latch, a loss's floor and the install of a state take one
     * lock, the monitor's own, and no other method takes a lock: the measuring
     * methods, the routing flag's writes and the readers never wait on it.
     */
    @Test
    void onlyAClearOfTheLatchALossFloorAndTheInstallOfAStateTakeALockAndAllTakeTheMonitorsOwn() throws IOException {
        assertThat(MonitorEntries.lockedFieldsByMethod(InputLevelMonitor.class)).isEqualTo(Map.of(
                "reset", Set.of("installLock"),
                "silenceThrough", Set.of("installLock"),
                "install", Set.of("installLock")));
    }

    private static Thread.State blockedOrTerminated(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + GUARD.toNanos();
        for (Thread.State state = thread.getState(); ; state = thread.getState()) {
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return state;
            }
            assertThat(System.nanoTime() - deadline).as("%s reached neither BLOCKED nor TERMINATED", thread)
                    .isNegative();
            Thread.sleep(1);
        }
    }

    /** A held thread's wait: until the test counts {@code latch} down, with no timeout of its own. */
    private static void awaitTheRelease(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private static float[] constant(float value, int length) {
        float[] samples = new float[length];
        java.util.Arrays.fill(samples, value);
        return samples;
    }

    /** As {@link #shouldFlagInterSamplePeakAtPositiveHalfDbTp}: every sample under 0 dBFS, the true peak +0.5 dBTP. */
    private static float[] interSamplePeakAtPlusHalfDbTp() {
        float[] samples = new float[256];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (PLUS_0_5_DBFS_LINEAR * Math.sin(i * (Math.PI / 2.0) + Math.PI / 4.0));
        }
        return samples;
    }

    /** A quarter period of a 100 Hz sine at 48 kHz, from 0 to its crest at {@code amplitude}. */
    private static float[] riseToTheCrestOfASlowSine(double amplitude) {
        float[] samples = new float[121];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * 100.0 * i / 48_000.0));
        }
        return samples;
    }

    /** A 1 kHz sine at 48 kHz starting at 0. */
    private static float[] sineFromZero(double amplitude, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * 1_000.0 * i / 48_000.0));
        }
        return samples;
    }
}
