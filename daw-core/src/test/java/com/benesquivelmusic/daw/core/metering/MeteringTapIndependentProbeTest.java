package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 — an <strong>independent</strong> probe of the metering tap
 * contract, written to be discriminating rather than confirmatory.
 *
 * <p>Every value here is deliberately off the defaults and off the values the
 * companion {@code MeteringTapCorrectnessTest} uses, so that a tap which
 * accidentally reports the right number (because the gain it forgot to apply
 * happened to be 1.0, because the pan it never read happened to be centred,
 * or because its "RMS" lane is really the peak of a sine) cannot pass:</p>
 * <ul>
 *   <li>faders are {@code 0.62} (channel), {@code 0.37} (master),
 *       {@code 0.71} (return) and {@code 0.41} (send) — never 1.0, 0.5 or 0;</li>
 *   <li>pans are {@code -0.4} and {@code +0.73} — never centred, never hard;</li>
 *   <li>the signal is a duty-cycle pulse train (20 samples on out of every
 *       64, sign-alternating) whose RMS-to-peak ratio is
 *       {@code sqrt(20/64) = 0.559017}, which is neither 1 (peak) nor
 *       {@code 1/sqrt(2) = 0.707107} (a sine), at amplitude {@code 0.83} —
 *       never full scale;</li>
 *   <li>the binding epochs are {@code 5} then {@code 9} — never 0 or 1.</li>
 * </ul>
 *
 * <p>Expected levels are computed here from the same published formulae the
 * mixer documents (constant-power pan law {@code angle = (pan + 1) * PI / 4},
 * {@code leftGain = cos(angle) * volume}, {@code rightGain = sin(angle) * volume}),
 * never hard-coded, and both {@link MixPrecision} branches are probed because
 * they are two separate accumulation paths.</p>
 */
class MeteringTapIndependentProbeTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BLOCK = 512;
    private static final double TEMPO = 93.0;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 24, BLOCK);

    /** Non-default binding epoch: never 0 (the unbound value) and never 1. */
    private static final long PROBE_EPOCH = 5L;
    /** The epoch a rebind moves to. */
    private static final long REBIND_EPOCH = 9L;

    private static final double CHANNEL_VOLUME = 0.62;
    private static final double MASTER_VOLUME = 0.37;
    private static final double RETURN_VOLUME = 0.71;
    private static final double SEND_LEVEL = 0.41;
    private static final double PAN_LEFTISH = -0.4;
    private static final double PAN_RIGHTISH = 0.73;

    /** Pulse-train period in samples; divides {@link #BLOCK}. */
    private static final int PULSE_PERIOD = 64;
    /** Samples per period at full amplitude; the rest of the period is silence. */
    private static final int PULSE_ON = 20;
    private static final float PULSE_AMPLITUDE = 0.83f;
    /** {@code sqrt(20/64)} — the pulse train's RMS/peak, deliberately not {@code 1/sqrt(2)}. */
    private static final double PULSE_RMS_TO_PEAK = Math.sqrt((double) PULSE_ON / PULSE_PERIOD);

    private static final float LEVEL_TOLERANCE = 1e-4f;
    private static final float RATIO_TOLERANCE = 1e-6f;

    // ── The pan law, restated from Mixer.sumChannelToOutput ───────────────

    /** {@code angle = (pan + 1) * 0.25 * PI} — the mixer's documented constant-power law. */
    private static double panAngle(double pan) {
        return (pan + 1.0) * 0.25 * Math.PI;
    }

    private static double leftGain(double pan, double volume) {
        return Math.cos(panAngle(pan)) * volume;
    }

    private static double rightGain(double pan, double volume) {
        return Math.sin(panAngle(pan)) * volume;
    }

    // ── The probe signal ──────────────────────────────────────────────────

    /**
     * Fills {@code lane} with the sign-alternating duty-cycle pulse train.
     * Peak is {@code amplitude}; RMS over any whole number of periods is
     * {@code amplitude * sqrt(PULSE_ON / PULSE_PERIOD)}; because the squared
     * signal is periodic with {@link #PULSE_PERIOD} and {@link #BLOCK} is a
     * whole number of periods, any whole-sample shift of the block window
     * leaves both figures unchanged.
     */
    private static void fillPulseTrain(float[] lane, float amplitude) {
        for (int i = 0; i < lane.length; i++) {
            int period = i / PULSE_PERIOD;
            int phase = i % PULSE_PERIOD;
            lane[i] = phase < PULSE_ON ? (period % 2 == 0 ? amplitude : -amplitude) : 0f;
        }
    }

    // ── Rig 1: the mixer alone, hand-fed, so the arithmetic is exact ──────

    /**
     * One channel summed by the real {@link Mixer} from a hand-filled buffer
     * (no clip renderer, no transport), tapped by a real
     * {@link MeteringTapBus}. Used where the probe needs the mixer's exact
     * arithmetic rather than a rendered approximation.
     */
    private static final class MixRig implements AutoCloseable {
        final Mixer mixer = new Mixer();
        final MixerChannel channel = new MixerChannel("Probe");
        final MeteringTapBus bus = new MeteringTapBus();
        final LevelSubscription channelTap;
        final LevelSubscription masterChainTap;
        final float[][][] channelBuffers = new float[1][CHANNELS][BLOCK];
        final float[][] output = new float[CHANNELS][BLOCK];
        final float[][][] returnBuffers = new float[Mixer.MAX_RETURN_BUSES][CHANNELS][BLOCK];
        final MeterFrame channelFrame = new MeterFrame();
        final MeterFrame masterChainFrame = new MeterFrame();

        MixRig(MixPrecision precision, double pan, float amplitude) {
            mixer.setMixPrecision(precision);
            channel.setVolume(CHANNEL_VOLUME);
            channel.setPan(pan);
            mixer.addChannel(channel);
            mixer.prepareForPlayback(CHANNELS, BLOCK);
            for (float[] lane : channelBuffers[0]) {
                fillPulseTrain(lane, amplitude);
            }
            bus.rebind(mixer, FORMAT, PROBE_EPOCH);
            channelTap = bus.attachLevel(new MeterTapPoint.ChannelPost(channel.getId()));
            masterChainTap = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
        }

        void mixBlock() {
            TapSnapshot taps = bus.snapshot();
            mixer.mixDown(channelBuffers, output, returnBuffers, BLOCK, taps);
            bus.blockCompleted(taps);
            assertThat(channelTap.readInto(channelFrame)).as("CHANNEL_POST published").isTrue();
            assertThat(masterChainTap.readInto(masterChainFrame)).as("MASTER_CHAIN published").isTrue();
        }

        @Override
        public void close() {
            bus.close();
        }
    }

    // ── Rig 2: the full render pipeline, so MASTER_OUT exists ────────────

    /**
     * A track + clip driven through the real {@link RenderPipeline}: one
     * channel at an off-centre pan with a post-fader send into the default
     * return bus, an odd master fader, and all four level tap points
     * subscribed.
     */
    private static final class PipelineRig implements AutoCloseable {
        final Transport transport = new Transport();
        final Mixer mixer = new Mixer();
        final MixerChannel channel = new MixerChannel("Probe");
        final MixerChannel returnBus;
        final List<Track> tracks;
        final EffectsChain engineMasterChain = new EffectsChain();
        final RenderPipeline pipeline = new RenderPipeline(FORMAT, 4, BLOCK);
        final MeteringTapBus bus = new MeteringTapBus();
        final float[][] output = new float[CHANNELS][BLOCK];

        LevelSubscription channelTap;
        LevelSubscription returnTap;
        LevelSubscription masterChainTap;
        LevelSubscription masterOutTap;

        final MeterFrame channelFrame = new MeterFrame();
        final MeterFrame returnFrame = new MeterFrame();
        final MeterFrame masterChainFrame = new MeterFrame();
        final MeterFrame masterOutFrame = new MeterFrame();

        PipelineRig(MixPrecision precision) {
            mixer.setMixPrecision(precision);
            transport.setTempo(TEMPO);

            Track track = new Track("Probe", TrackType.AUDIO);
            track.addClip(pulseClip());
            channel.setVolume(CHANNEL_VOLUME);
            channel.setPan(PAN_LEFTISH);
            mixer.addChannel(channel);

            returnBus = mixer.getReturnBuses().get(0);
            returnBus.setVolume(RETURN_VOLUME);
            channel.addSend(new Send(returnBus, SEND_LEVEL, SendMode.POST_FADER));

            mixer.getMasterChannel().setVolume(MASTER_VOLUME);

            tracks = List.of(track);
            mixer.prepareForPlayback(CHANNELS, BLOCK);
            engineMasterChain.allocateIntermediateBuffers(CHANNELS, BLOCK);

            bus.rebind(mixer, FORMAT, PROBE_EPOCH);
            subscribeAll();
            transport.play();
        }

        void subscribeAll() {
            channelTap = bus.attachLevel(new MeterTapPoint.ChannelPost(channel.getId()));
            returnTap = bus.attachLevel(new MeterTapPoint.ReturnPost(returnBus.getId()));
            masterChainTap = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
            masterOutTap = bus.attachLevel(MeterTapPoint.MASTER_OUT);
        }

        /** Renders one block exactly as {@code AudioEngine.processBlock} does; returns its stamp. */
        long renderBlock() {
            TapSnapshot taps = bus.snapshot();
            long stamp = taps.blockIndex();
            for (float[] lane : output) {
                Arrays.fill(lane, 0f);
            }
            pipeline.renderBlock(null, output, BLOCK, transport, mixer, tracks, null,
                    engineMasterChain, null, null, null, null, null, null, null, taps);
            bus.blockCompleted(taps);
            return stamp;
        }

        void readAll() {
            assertThat(channelTap.readInto(channelFrame)).as("CHANNEL_POST published").isTrue();
            assertThat(returnTap.readInto(returnFrame)).as("RETURN_POST published").isTrue();
            assertThat(masterChainTap.readInto(masterChainFrame)).as("MASTER_CHAIN published").isTrue();
            assertThat(masterOutTap.readInto(masterOutFrame)).as("MASTER_OUT published").isTrue();
        }

        List<MeterFrame> allFrames() {
            return List.of(channelFrame, returnFrame, masterChainFrame, masterOutFrame);
        }

        @Override
        public void close() {
            bus.close();
        }
    }

    private static AudioClip pulseClip() {
        int totalFrames = BLOCK * 40;
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        AudioClip clip = new AudioClip("probe-pulse", 0.0, totalFrames / samplesPerBeat, null);
        float[][] data = new float[CHANNELS][totalFrames];
        for (float[] lane : data) {
            fillPulseTrain(lane, PULSE_AMPLITUDE);
        }
        clip.setAudioData(data);
        return clip;
    }

    // ═══ 1. MASTER_CHAIN vs MASTER_OUT at an odd master fader ════════════

    /**
     * The master fader is {@code 0.37} and the channel fader {@code 0.62} —
     * neither is a power of two, so an implementation that tapped the same
     * buffer twice, or tapped {@code MASTER_OUT} before the fader, cannot hide
     * behind an exact-arithmetic coincidence. The expected ratio is derived
     * from the gains, never written down.
     */
    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void masterOutIsMasterChainScaledByExactlyTheOddMasterFader(MixPrecision precision) {
        try (PipelineRig rig = new PipelineRig(precision)) {
            rig.renderBlock();
            rig.renderBlock();
            rig.readAll();

            // MASTER_CHAIN is the pre-fader sum: channel (fader + pan law)
            // plus the post-fader send through the return fader (no pan).
            double sendPath = SEND_LEVEL * CHANNEL_VOLUME * RETURN_VOLUME;
            float expectedChainLeft =
                    (float) (PULSE_AMPLITUDE * (leftGain(PAN_LEFTISH, CHANNEL_VOLUME) + sendPath));
            float expectedChainRight =
                    (float) (PULSE_AMPLITUDE * (rightGain(PAN_LEFTISH, CHANNEL_VOLUME) + sendPath));

            assertThat(rig.masterChainFrame.peak(0)).as("MASTER_CHAIN left peak (%s)", precision)
                    .isCloseTo(expectedChainLeft, within(LEVEL_TOLERANCE));
            assertThat(rig.masterChainFrame.peak(1)).as("MASTER_CHAIN right peak (%s)", precision)
                    .isCloseTo(expectedChainRight, within(LEVEL_TOLERANCE));

            for (int lane = 0; lane < CHANNELS; lane++) {
                float chainPeak = rig.masterChainFrame.peak(lane);
                float outPeak = rig.masterOutFrame.peak(lane);
                assertThat(outPeak)
                        .as("MASTER_OUT lane %d is MASTER_CHAIN * %s (%s)", lane, MASTER_VOLUME, precision)
                        .isCloseTo(chainPeak * (float) MASTER_VOLUME, within(RATIO_TOLERANCE));
                assertThat(rig.masterOutFrame.rms(lane))
                        .as("MASTER_OUT lane %d rms is MASTER_CHAIN rms * %s (%s)",
                                lane, MASTER_VOLUME, precision)
                        .isCloseTo(rig.masterChainFrame.rms(lane) * (float) MASTER_VOLUME,
                                within(RATIO_TOLERANCE));
                // The two taps must genuinely DIVERGE: a MASTER_OUT that read
                // the pre-fader buffer would be within tolerance of the chain.
                assertThat(chainPeak - outPeak)
                        .as("the two master taps diverge on lane %d (%s)", lane, precision)
                        .isGreaterThan(0.1f);
            }

            // ... and the divergence is the fader, not the engine master chain
            // (which is empty here and therefore a straight copy).
            assertThat(rig.masterOutFrame.blockIndex())
                    .as("both master taps describe the same block")
                    .isEqualTo(rig.masterChainFrame.blockIndex());
        }
    }

    // ═══ 2. The constant-power pan law at off-centre pans ════════════════

    /**
     * At {@code pan = -0.4} and {@code pan = +0.73} the two lanes of
     * {@code CHANNEL_POST} must stand in the ratio {@code cos(angle)/sin(angle)}
     * of the mixer's own law. A tap reading the channel pre-pan would report
     * equal lanes; a tap applying only the fader would report
     * {@code amplitude * volume} on both.
     */
    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void channelPostCarriesTheConstantPowerPanLawAtOffCentrePans(MixPrecision precision) {
        for (double pan : new double[]{PAN_LEFTISH, PAN_RIGHTISH}) {
            try (MixRig rig = new MixRig(precision, pan, PULSE_AMPLITUDE)) {
                rig.mixBlock();

                double angle = panAngle(pan);
                float expectedLeft = (float) (PULSE_AMPLITUDE * leftGain(pan, CHANNEL_VOLUME));
                float expectedRight = (float) (PULSE_AMPLITUDE * rightGain(pan, CHANNEL_VOLUME));
                double expectedRatio = Math.cos(angle) / Math.sin(angle);

                float left = rig.channelFrame.peak(0);
                float right = rig.channelFrame.peak(1);

                assertThat(left).as("pan %s left peak (%s)", pan, precision)
                        .isCloseTo(expectedLeft, within(LEVEL_TOLERANCE));
                assertThat(right).as("pan %s right peak (%s)", pan, precision)
                        .isCloseTo(expectedRight, within(LEVEL_TOLERANCE));
                assertThat((double) left / right)
                        .as("pan %s lane ratio is cos(angle)/sin(angle) (%s)", pan, precision)
                        .isCloseTo(expectedRatio, within(1e-5));

                // A pre-pan tap would report both lanes at amplitude * volume.
                float prePan = (float) (PULSE_AMPLITUDE * CHANNEL_VOLUME);
                assertThat(left).as("pan %s left is NOT the pre-pan level", pan)
                        .isNotCloseTo(prePan, within(1e-3f));
                assertThat(right).as("pan %s right is NOT the pre-pan level", pan)
                        .isNotCloseTo(prePan, within(1e-3f));
                assertThat(Math.abs(left - right))
                        .as("an off-centre pan must not produce equal lanes").isGreaterThan(0.05f);

                // The pan law is constant power: the lane gains square-sum to
                // the fader's square. That is what distinguishes it from a
                // linear law, and it is derived, not tabulated.
                double sumOfSquares = (left * left + right * right)
                        / (PULSE_AMPLITUDE * (double) PULSE_AMPLITUDE);
                assertThat(sumOfSquares)
                        .as("pan %s obeys constant power: cos^2+sin^2 == 1 times volume^2", pan)
                        .isCloseTo(CHANNEL_VOLUME * CHANNEL_VOLUME, within(1e-5));
            }
        }
    }

    // ═══ 3. RMS is the true RMS, not a second copy of the peak ═══════════

    /**
     * The pulse train's RMS/peak is {@code sqrt(20/64) = 0.559017}: not 1 (a
     * peak copy), not {@code 1/sqrt(2) = 0.707107} (the ratio a sine-driven
     * fixture would make either lane look correct with), and not 0.5.
     */
    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void rmsIsTheTrueRmsOfTheScaledSignalNotThePeak(MixPrecision precision) {
        try (MixRig rig = new MixRig(precision, PAN_LEFTISH, PULSE_AMPLITUDE)) {
            rig.mixBlock();

            for (int lane = 0; lane < CHANNELS; lane++) {
                double gain = lane == 0
                        ? leftGain(PAN_LEFTISH, CHANNEL_VOLUME)
                        : rightGain(PAN_LEFTISH, CHANNEL_VOLUME);
                float expectedPeak = (float) (PULSE_AMPLITUDE * gain);
                float expectedRms = (float) (PULSE_AMPLITUDE * gain * PULSE_RMS_TO_PEAK);

                assertThat(rig.channelFrame.peak(lane)).as("CHANNEL_POST lane %d peak", lane)
                        .isCloseTo(expectedPeak, within(LEVEL_TOLERANCE));
                assertThat(rig.channelFrame.rms(lane)).as("CHANNEL_POST lane %d rms", lane)
                        .isCloseTo(expectedRms, within(LEVEL_TOLERANCE));

                double observedRatio = rig.channelFrame.rms(lane) / rig.channelFrame.peak(lane);
                assertThat(observedRatio)
                        .as("lane %d rms/peak is the pulse train's, %s", lane, PULSE_RMS_TO_PEAK)
                        .isCloseTo(PULSE_RMS_TO_PEAK, within(1e-4));
                assertThat(observedRatio).as("lane %d rms is not a copy of the peak", lane)
                        .isLessThan(0.7);
                assertThat(Math.abs(observedRatio - 1.0 / Math.sqrt(2.0)))
                        .as("lane %d rms is not the sine assumption 1/sqrt(2)", lane)
                        .isGreaterThan(0.1);
            }

            // MASTER_CHAIN sees the same single channel through a different
            // accumulation site (the master stage, not the summing multiply):
            // both must agree sample for sample.
            for (int lane = 0; lane < CHANNELS; lane++) {
                assertThat(rig.masterChainFrame.peak(lane))
                        .as("MASTER_CHAIN lane %d equals the single channel's post-fader peak", lane)
                        .isCloseTo(rig.channelFrame.peak(lane), within(1e-7f));
                assertThat(rig.masterChainFrame.rms(lane))
                        .as("MASTER_CHAIN lane %d equals the single channel's post-fader rms", lane)
                        .isCloseTo(rig.channelFrame.rms(lane), within(1e-7f));
            }
        }
    }

    // ═══ 4. The clip flag's threshold ════════════════════════════════════

    /**
     * The production threshold is {@code LevelTapSlot.publish}:
     * {@code if (peak >= 1.0f) clipped = true;} — inclusive, at exactly
     * {@code 1.0f}. This probe brackets it to one ULP by choosing source
     * amplitudes whose post-fader products are the two adjacent achievable
     * values either side of full scale, plus an ordinary {@code 0.999} block.
     */
    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void theClipFlagTripsAtExactlyFullScaleAndNotOneUlpBelow(MixPrecision precision) {
        // The mixer's post-fader multiply for this channel, restated per
        // precision branch: float branch multiplies by the narrowed gain,
        // DOUBLE_64 multiplies in 64-bit and narrows the product.
        double gainDouble = leftGain(PAN_LEFTISH, CHANNEL_VOLUME);
        float gainFloat = (float) gainDouble;
        PostFader postFader = precision == MixPrecision.DOUBLE_64
                ? amp -> (float) (amp * gainDouble)
                : amp -> amp * gainFloat;

        float ordinaryAmplitude = 0.999f / gainFloat;
        float justBelowAmplitude = largestAmplitudeBelow(postFader, 1.0f, 1.0f / gainFloat);
        float atFullScaleAmplitude = smallestAmplitudeAtLeast(postFader, 1.0f, 1.0f / gainFloat);

        float ordinaryPeak = clipProbePeak(precision, ordinaryAmplitude);
        assertThat(ordinaryPeak).as("the 0.999 block's post-fader peak (%s)", precision)
                .isCloseTo(0.999f, within(1e-6f)).isLessThan(1.0f);
        assertThat(clipProbeClipped(precision, ordinaryAmplitude))
                .as("a block peaking at 0.999 must NOT be flagged as clipped (%s)", precision)
                .isFalse();

        float justBelowPeak = clipProbePeak(precision, justBelowAmplitude);
        assertThat(justBelowPeak).as("the largest achievable peak below full scale (%s)", precision)
                .isLessThan(1.0f).isGreaterThan(0.9999990f);
        assertThat(clipProbeClipped(precision, justBelowAmplitude))
                .as("one ULP below 1.0 must NOT clip — the threshold is >= 1.0f, not > 0.999 (%s)",
                        precision)
                .isFalse();

        float fullScalePeak = clipProbePeak(precision, atFullScaleAmplitude);
        assertThat(fullScalePeak).as("the smallest achievable peak at/above full scale (%s)", precision)
                .isGreaterThanOrEqualTo(1.0f).isLessThan(1.0000010f);
        assertThat(clipProbeClipped(precision, atFullScaleAmplitude))
                .as("a block peaking at exactly 1.0 MUST be flagged as clipped (%s)", precision)
                .isTrue();

        assertThat(fullScalePeak - justBelowPeak)
                .as("the two bracketing blocks are adjacent — the flag flips exactly at 1.0 (%s)",
                        precision)
                .isLessThan(1e-6f);
    }

    /** The mixer's post-fader multiply for one sample, per precision branch. */
    @FunctionalInterface
    private interface PostFader {
        float apply(float amplitude);
    }

    /** The largest amplitude whose post-fader product is strictly below {@code limit}. */
    private static float largestAmplitudeBelow(PostFader postFader, float limit, float guess) {
        float amp = guess;
        for (int i = 0; i < 512 && postFader.apply(amp) >= limit; i++) {
            amp = Math.nextDown(amp);
        }
        for (int i = 0; i < 512 && postFader.apply(Math.nextUp(amp)) < limit; i++) {
            amp = Math.nextUp(amp);
        }
        assertThat(postFader.apply(amp)).as("search converged below %s", limit).isLessThan(limit);
        return amp;
    }

    /** The smallest amplitude whose post-fader product reaches {@code limit}. */
    private static float smallestAmplitudeAtLeast(PostFader postFader, float limit, float guess) {
        float amp = guess;
        for (int i = 0; i < 512 && postFader.apply(amp) < limit; i++) {
            amp = Math.nextUp(amp);
        }
        for (int i = 0; i < 512 && postFader.apply(Math.nextDown(amp)) >= limit; i++) {
            amp = Math.nextDown(amp);
        }
        assertThat(postFader.apply(amp)).as("search converged at/above %s", limit)
                .isGreaterThanOrEqualTo(limit);
        return amp;
    }

    private static float clipProbePeak(MixPrecision precision, float amplitude) {
        try (MixRig rig = new MixRig(precision, PAN_LEFTISH, amplitude)) {
            rig.mixBlock();
            return rig.channelFrame.peak(0);
        }
    }

    private static boolean clipProbeClipped(MixPrecision precision, float amplitude) {
        try (MixRig rig = new MixRig(precision, PAN_LEFTISH, amplitude)) {
            rig.mixBlock();
            return rig.channelFrame.clipped();
        }
    }

    // ═══ 5. Block-index and epoch stamping ═══════════════════════════════

    /**
     * Three consecutive rendered blocks must carry strictly increasing block
     * indices at every tap point, and within one block all four tap points —
     * two of which are stamped in {@code Mixer}, one in {@code Mixer}'s master
     * stage and one in {@code RenderPipeline} — must agree on both the index
     * and the epoch.
     */
    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void allFourTapPointsOfOneBlockShareTheStampAndThreeBlocksAdvanceIt(MixPrecision precision) {
        try (PipelineRig rig = new PipelineRig(precision)) {
            List<long[]> perBlock = new ArrayList<>();
            long[] stamps = new long[3];

            for (int b = 0; b < 3; b++) {
                stamps[b] = rig.renderBlock();
                rig.readAll();
                long[] indices = new long[4];
                List<MeterFrame> frames = rig.allFrames();
                for (int t = 0; t < frames.size(); t++) {
                    indices[t] = frames.get(t).blockIndex();
                    assertThat(frames.get(t).epoch())
                            .as("block %d tap %d carries the binding epoch (%s)", b, t, precision)
                            .isEqualTo(PROBE_EPOCH);
                }
                assertThat(indices)
                        .as("all four tap points of block %d share one blockIndex (%s)", b, precision)
                        .containsOnly(stamps[b]);
                perBlock.add(indices);
            }

            for (int t = 0; t < 4; t++) {
                assertThat(perBlock.get(1)[t]).as("tap %d advanced on block 2", t)
                        .isGreaterThan(perBlock.get(0)[t]);
                assertThat(perBlock.get(2)[t]).as("tap %d advanced on block 3", t)
                        .isGreaterThan(perBlock.get(1)[t]);
            }
            assertThat(stamps[1]).isEqualTo(stamps[0] + 1);
            assertThat(stamps[2]).isEqualTo(stamps[1] + 1);
            assertThat(rig.bus.blockIndex()).as("blockCompleted advanced past the last stamp")
                    .isEqualTo(stamps[2] + 1);
        }
    }

    /**
     * A rebind to a higher epoch disposes the epoch-5 tokens and the frames a
     * freshly attached epoch-9 token reads carry the new epoch. Before the
     * first block of the new epoch is rendered, the reused slots still hold
     * epoch-5 frames — a fresh token must therefore never see the NEW epoch on
     * stale data, which is exactly why the FX drain gates on
     * {@code frame.epoch()}.
     */
    @Test
    void aRebindBumpsTheEpochAFreshlyAttachedSubscriptionSees() {
        try (PipelineRig rig = new PipelineRig(MixPrecision.DOUBLE_64)) {
            rig.renderBlock();
            rig.readAll();
            long stampBeforeRebind = rig.masterOutFrame.blockIndex();
            for (MeterFrame frame : rig.allFrames()) {
                assertThat(frame.epoch()).isEqualTo(PROBE_EPOCH);
            }

            LevelSubscription staleToken = rig.masterOutTap;
            rig.bus.rebind(rig.mixer, FORMAT, REBIND_EPOCH);

            assertThat(staleToken.isDisposed()).as("the epoch-5 token is disposed by the rebind")
                    .isTrue();
            assertThat(staleToken.readInto(new MeterFrame()))
                    .as("a disposed token reads nothing").isFalse();
            assertThat(rig.bus.epoch()).isEqualTo(REBIND_EPOCH);

            rig.subscribeAll();
            MeterFrame beforeAnyNewBlock = new MeterFrame();
            assertThat(rig.masterOutTap.readInto(beforeAnyNewBlock))
                    .as("the rebind reuses the MASTER_OUT slot, so an epoch-%s token resolves it "
                            + "immediately — before any block of the new epoch has been rendered",
                            REBIND_EPOCH)
                    .isTrue();
            assertThat(beforeAnyNewBlock.epoch())
                    .as("that first read is STALE: the reused slot still carries the OLD epoch "
                            + "until the next block publishes — never the new one, which is why a "
                            + "drain must gate on frame.epoch() and not merely on the token's")
                    .isEqualTo(PROBE_EPOCH);
            assertThat(beforeAnyNewBlock.blockIndex())
                    .as("and the stale frame still carries the pre-rebind block index")
                    .isEqualTo(stampBeforeRebind);

            long stampAfterRebind = rig.renderBlock();
            rig.readAll();
            for (MeterFrame frame : rig.allFrames()) {
                assertThat(frame.epoch())
                        .as("every tap of the first block after the rebind carries epoch %s",
                                REBIND_EPOCH)
                        .isEqualTo(REBIND_EPOCH);
                assertThat(frame.blockIndex()).isEqualTo(stampAfterRebind);
            }
            assertThat(REBIND_EPOCH).isGreaterThan(PROBE_EPOCH);
            assertThat(stampAfterRebind).as("the block counter is not reset by a rebind")
                    .isGreaterThan(stampBeforeRebind);
        }
    }
}
