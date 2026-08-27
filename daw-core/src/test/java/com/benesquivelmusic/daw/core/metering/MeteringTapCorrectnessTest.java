package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 318 — the tap-correctness acceptance test: a real {@link Mixer}
 * (two stereo channels, one return bus with a post-fader send, the master)
 * driven through a real {@link RenderPipeline} with a known full-scale
 * sine, metered through a real {@link MeteringTapBus}.
 *
 * <p>The signal is 750 Hz at 48 kHz in 512-frame blocks — eight whole
 * cycles per block, so every block's RMS is exactly {@code 1/sqrt(2)} and
 * every block contains the exact-peak sample regardless of how the clip's
 * frame grid lands on the block grid. The expected level at each tap is
 * therefore the sine's level times the gains the mixer folds into its
 * summing multiply: channel fader and constant-power pan law at
 * {@code CHANNEL_POST}, send level and return fader at {@code RETURN_POST},
 * the sum of both at {@code MASTER_CHAIN}, and the master fader on top at
 * {@code MASTER_OUT}.</p>
 */
class MeteringTapCorrectnessTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int BLOCK = 512;
    private static final int CYCLES_PER_BLOCK = 8;
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;
    private static final int BLOCKS = 6;
    private static final int TOTAL_FRAMES = BLOCK * (BLOCKS + 4);
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 24, BLOCK);

    private static final double CHANNEL_A_VOLUME = 0.8;
    private static final double CHANNEL_B_VOLUME = 0.5;
    private static final double SEND_LEVEL = 0.5;
    /** Channel A centred: {@code cos(pi/4) * volume} on both lanes. */
    private static final float A_GAIN = (float) (Math.cos(Math.PI / 4.0) * CHANNEL_A_VOLUME);
    /** Channel B hard left: {@code cos(0) * volume} left, {@code sin(0) * volume = 0} right. */
    private static final float B_LEFT_GAIN = (float) CHANNEL_B_VOLUME;
    /** Post-fader send from A into the return bus (return fader at unity). */
    private static final float RETURN_GAIN = (float) (SEND_LEVEL * CHANNEL_A_VOLUME);
    private static final float SINE_RMS = (float) (1.0 / Math.sqrt(2.0));
    private static final float TOLERANCE = 2e-3f;

    /** A rendering rig over the real mixer, pipeline and bus. */
    private static final class Rig implements AutoCloseable {
        final Transport transport = new Transport();
        final Mixer mixer = new Mixer();
        final MixerChannel channelA;
        final MixerChannel channelB;
        final MixerChannel returnBus;
        final List<Track> tracks;
        final EffectsChain masterChain = new EffectsChain();
        final RenderPipeline pipeline = new RenderPipeline(FORMAT, 8, BLOCK);
        final MeteringTapBus bus = new MeteringTapBus();
        final float[][] output;
        final LevelSubscription tapA;
        final LevelSubscription tapB;
        final LevelSubscription tapReturn;
        final LevelSubscription tapMasterChain;
        final LevelSubscription tapMasterOut;
        final MeterFrame frameA = new MeterFrame();
        final MeterFrame frameB = new MeterFrame();
        final MeterFrame frameReturn = new MeterFrame();
        final MeterFrame frameMasterChain = new MeterFrame();
        final MeterFrame frameMasterOut = new MeterFrame();

        /**
         * Non-null for the {@code mixDownInstrumented} path — the body the
         * shipped app always runs, because {@code MainController.initialize()}
         * installs a {@link TrackCpuBudgetEnforcer} on the engine and
         * {@code RenderPipeline} picks the instrumented overload whenever one
         * is present. Its tap publishing is a duplicate of {@code mixDown}'s
         * and must be asserted, not inspected.
         */
        final TrackCpuBudgetEnforcer enforcer;

        Rig(MixPrecision precision) {
            this(precision, CHANNELS, null);
        }

        Rig(MixPrecision precision, int outputLanes) {
            this(precision, outputLanes, null);
        }

        Rig(MixPrecision precision, int outputLanes, TrackCpuBudgetEnforcer enforcer) {
            this.enforcer = enforcer;
            mixer.setMixPrecision(precision);
            transport.setTempo(TEMPO);

            Track trackA = new Track("A", TrackType.AUDIO);
            trackA.addClip(sineClip("A-clip"));
            channelA = new MixerChannel("A");
            channelA.setVolume(CHANNEL_A_VOLUME);
            channelA.setPan(0.0);
            mixer.addChannel(channelA);

            Track trackB = new Track("B", TrackType.AUDIO);
            trackB.addClip(sineClip("B-clip"));
            channelB = new MixerChannel("B");
            channelB.setVolume(CHANNEL_B_VOLUME);
            channelB.setPan(-1.0);
            mixer.addChannel(channelB);

            returnBus = mixer.getReturnBuses().get(0);
            channelA.addSend(new Send(returnBus, SEND_LEVEL, SendMode.POST_FADER));

            tracks = List.of(trackA, trackB);
            mixer.prepareForPlayback(CHANNELS, BLOCK);
            masterChain.allocateIntermediateBuffers(CHANNELS, BLOCK);
            output = new float[outputLanes][BLOCK];

            bus.rebind(mixer, FORMAT, 1L);
            tapA = bus.attachLevel(new MeterTapPoint.ChannelPost(channelA.getId()));
            tapB = bus.attachLevel(new MeterTapPoint.ChannelPost(channelB.getId()));
            tapReturn = bus.attachLevel(new MeterTapPoint.ReturnPost(returnBus.getId()));
            tapMasterChain = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
            tapMasterOut = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            transport.play();
        }

        /** Renders one block exactly as {@code AudioEngine.processBlock} does; returns the block's stamp. */
        long renderBlock() {
            TapSnapshot taps = bus.snapshot();
            long stamp = taps.blockIndex();
            for (float[] lane : output) {
                Arrays.fill(lane, 0f);
            }
            pipeline.renderBlock(null, output, BLOCK, transport, mixer, tracks, null,
                    masterChain, null, null, enforcer, null, null, null, null, taps);
            bus.blockCompleted(taps);
            return stamp;
        }

        long renderBlocks(int count) {
            long stamp = -1L;
            for (int i = 0; i < count; i++) {
                stamp = renderBlock();
            }
            return stamp;
        }

        void readAll() {
            assertThat(tapA.readInto(frameA)).as("CHANNEL_POST(A) published").isTrue();
            assertThat(tapB.readInto(frameB)).as("CHANNEL_POST(B) published").isTrue();
            assertThat(tapReturn.readInto(frameReturn)).as("RETURN_POST published").isTrue();
            assertThat(tapMasterChain.readInto(frameMasterChain)).as("MASTER_CHAIN published").isTrue();
            assertThat(tapMasterOut.readInto(frameMasterOut)).as("MASTER_OUT published").isTrue();
        }

        @Override
        public void close() {
            bus.close();
            if (enforcer != null) {
                enforcer.close();
            }
        }
    }

    /**
     * Every combination of {@link MixPrecision} and mix body: {@code mixDown}
     * (enforcer absent) and {@code mixDownInstrumented} (enforcer present —
     * the body production always executes). The two bodies are duplicates, so
     * the tap assertions must run against both or the shipped one is covered
     * by inspection only.
     */
    static Stream<Arguments> mixBodies() {
        return Stream.of(MixPrecision.values())
                .flatMap(precision -> Stream.of(
                        Arguments.of(precision, false),
                        Arguments.of(precision, true)));
    }

    private static Rig rig(MixPrecision precision, boolean instrumented) {
        return new Rig(precision, CHANNELS,
                instrumented ? new TrackCpuBudgetEnforcer(SAMPLE_RATE, BLOCK) : null);
    }

    private static AudioClip sineClip(String name) {
        AudioClip clip = new AudioClip(name, 0.0, TOTAL_FRAMES / SAMPLES_PER_BEAT, null);
        float[][] data = new float[CHANNELS][TOTAL_FRAMES];
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * CYCLES_PER_BLOCK * i / BLOCK);
            data[0][i] = v;
            data[1][i] = v;
        }
        clip.setAudioData(data);
        return clip;
    }

    private static void assertLane(MeterFrame frame, int lane, float expectedPeak, String what) {
        assertThat(frame.peak(lane)).as("%s lane %d peak", what, lane)
                .isCloseTo(expectedPeak, within(TOLERANCE));
        assertThat(frame.rms(lane)).as("%s lane %d rms", what, lane)
                .isCloseTo(expectedPeak * SINE_RMS, within(TOLERANCE));
    }

    private static void assertStamp(MeterFrame frame, long epoch, long stamp, String what) {
        assertThat(frame.epoch()).as("%s epoch", what).isEqualTo(epoch);
        assertThat(frame.blockIndex()).as("%s blockIndex", what).isEqualTo(stamp);
    }

    @ParameterizedTest(name = "{0}, instrumented={1}")
    @MethodSource("mixBodies")
    void everyTapReportsTheFullScaleSineScaledByTheGainsTheMixFolds(MixPrecision precision,
                                                                    boolean instrumented) {
        try (Rig rig = rig(precision, instrumented)) {
            long stamp = rig.renderBlocks(BLOCKS);
            rig.readAll();

            assertLane(rig.frameA, 0, A_GAIN, "CHANNEL_POST(A)");
            assertLane(rig.frameA, 1, A_GAIN, "CHANNEL_POST(A)");
            assertLane(rig.frameB, 0, B_LEFT_GAIN, "CHANNEL_POST(B)");
            assertLane(rig.frameB, 1, 0f, "CHANNEL_POST(B)");
            assertLane(rig.frameReturn, 0, RETURN_GAIN, "RETURN_POST");
            assertLane(rig.frameReturn, 1, RETURN_GAIN, "RETURN_POST");
            assertLane(rig.frameMasterChain, 0, A_GAIN + B_LEFT_GAIN + RETURN_GAIN, "MASTER_CHAIN");
            assertLane(rig.frameMasterChain, 1, A_GAIN + RETURN_GAIN, "MASTER_CHAIN");
            assertLane(rig.frameMasterOut, 0, A_GAIN + B_LEFT_GAIN + RETURN_GAIN, "MASTER_OUT");
            assertLane(rig.frameMasterOut, 1, A_GAIN + RETURN_GAIN, "MASTER_OUT");

            assertThat(rig.frameA.clipped()).isFalse();
            assertThat(rig.frameMasterChain.clipped()).as("the sum exceeds full scale").isTrue();
            assertThat(rig.frameMasterOut.clipped()).isTrue();

            long epoch = rig.bus.epoch();
            for (MeterFrame frame : List.of(rig.frameA, rig.frameB, rig.frameReturn,
                    rig.frameMasterChain, rig.frameMasterOut)) {
                assertStamp(frame, epoch, stamp, frame.toString());
                assertThat(frame.channelCount()).isEqualTo(CHANNELS);
            }
            assertThat(rig.bus.blockIndex()).as("blockCompleted advanced the counter")
                    .isEqualTo(stamp + 1);
        }
    }

    @ParameterizedTest(name = "{0}, instrumented={1}")
    @MethodSource("mixBodies")
    void masterChainAndMasterOutDifferByExactlyTheMasterFaderGain(MixPrecision precision,
                                                                  boolean instrumented) {
        try (Rig rig = rig(precision, instrumented)) {
            rig.mixer.getMasterChannel().setVolume(0.5);
            rig.renderBlocks(BLOCKS);
            rig.readAll();

            for (int lane = 0; lane < CHANNELS; lane++) {
                assertThat(rig.frameMasterOut.peak(lane))
                        .as("MASTER_OUT peak is exactly half MASTER_CHAIN's on lane %d", lane)
                        .isEqualTo(rig.frameMasterChain.peak(lane) * 0.5f);
                assertThat(rig.frameMasterOut.rms(lane))
                        .as("MASTER_OUT rms is half MASTER_CHAIN's on lane %d", lane)
                        .isCloseTo(rig.frameMasterChain.rms(lane) * 0.5f, within(1e-6f));
            }
            assertLane(rig.frameMasterChain, 0, A_GAIN + B_LEFT_GAIN + RETURN_GAIN, "MASTER_CHAIN");
            assertThat(rig.frameMasterChain.clipped()).as("pre-fader sum clips").isTrue();
            assertThat(rig.frameMasterOut.clipped()).as("post-fader output does not").isFalse();
            assertThat(rig.frameMasterOut.blockIndex()).isEqualTo(rig.frameMasterChain.blockIndex());
        }
    }

    @ParameterizedTest(name = "{0}, instrumented={1}")
    @MethodSource("mixBodies")
    void mutedAndSoloExcludedChannelsPublishSilenceStampedWithTheBlock(MixPrecision precision,
                                                                       boolean instrumented) {
        try (Rig rig = rig(precision, instrumented)) {
            rig.channelB.setMuted(true);
            long stamp = rig.renderBlocks(2);
            rig.readAll();

            assertThat(rig.frameB.isSilent()).as("muted channel reads silence").isTrue();
            assertStamp(rig.frameB, rig.bus.epoch(), stamp, "muted CHANNEL_POST(B)");
            assertThat(rig.frameB.channelCount()).isEqualTo(CHANNELS);
            assertLane(rig.frameA, 0, A_GAIN, "CHANNEL_POST(A)");
            assertLane(rig.frameMasterChain, 0, A_GAIN + RETURN_GAIN, "MASTER_CHAIN without B");

            rig.channelB.setMuted(false);
            rig.channelA.setSolo(true);
            stamp = rig.renderBlocks(2);
            rig.readAll();

            assertThat(rig.frameB.isSilent()).as("solo-excluded channel reads silence").isTrue();
            assertStamp(rig.frameB, rig.bus.epoch(), stamp, "solo-excluded CHANNEL_POST(B)");
            assertLane(rig.frameA, 0, A_GAIN, "soloed CHANNEL_POST(A)");
            assertLane(rig.frameReturn, 0, RETURN_GAIN, "solo-safe RETURN_POST");

            rig.returnBus.setMuted(true);
            stamp = rig.renderBlocks(1);
            rig.readAll();
            assertThat(rig.frameReturn.isSilent()).as("muted return reads silence").isTrue();
            assertStamp(rig.frameReturn, rig.bus.epoch(), stamp, "muted RETURN_POST");
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void aMutedMasterStillMetersMasterChainPreFaderWhileMasterOutReadsSilence(MixPrecision precision) {
        try (Rig rig = new Rig(precision)) {
            rig.mixer.getMasterChannel().setMuted(true);
            long stamp = rig.renderBlocks(2);
            rig.readAll();

            assertLane(rig.frameMasterChain, 0, A_GAIN + B_LEFT_GAIN + RETURN_GAIN, "MASTER_CHAIN");
            assertThat(rig.frameMasterOut.isSilent()).as("MASTER_OUT reads the muted output").isTrue();
            assertStamp(rig.frameMasterOut, rig.bus.epoch(), stamp, "MASTER_OUT");
            assertThat(rig.frameMasterOut.channelCount()).isEqualTo(CHANNELS);
        }
    }

    @Test
    void insertIoPairMetersTheProcessorsInputAndOutputOnChannelAndReturnChains() {
        try (Rig rig = new Rig(MixPrecision.DOUBLE_64)) {
            InsertSlot channelInsert = new InsertSlot("Half", new GainProcessor(0.5f));
            rig.channelA.addInsert(channelInsert);
            InsertSlot returnInsert = new InsertSlot("Quarter", new GainProcessor(0.25f));
            rig.returnBus.addInsert(returnInsert);
            rig.channelA.prepareEffectsChain(CHANNELS, BLOCK);
            rig.returnBus.prepareEffectsChain(CHANNELS, BLOCK);

            InsertIoSubscription channelPair = rig.bus.attachInsertIo(
                    new MeterTapPoint.InsertIo(channelInsert.getPluginInstanceId()));
            InsertIoSubscription returnPair = rig.bus.attachInsertIo(
                    new MeterTapPoint.InsertIo(returnInsert.getPluginInstanceId()));
            assertThat(rig.bus.snapshot().insertTapCount()).isEqualTo(2);

            long stamp = rig.renderBlocks(BLOCKS);
            rig.readAll();
            MeterFrame in = new MeterFrame();
            MeterFrame out = new MeterFrame();

            assertThat(channelPair.readInputInto(in)).isTrue();
            assertThat(channelPair.readOutputInto(out)).isTrue();
            assertLane(in, 0, 1.0f, "channel insert input (the raw clip)");
            assertLane(out, 0, 0.5f, "channel insert output");
            assertStamp(in, rig.bus.epoch(), stamp, "insert input");
            assertStamp(out, rig.bus.epoch(), stamp, "insert output");
            assertLane(rig.frameA, 0, 0.5f * A_GAIN, "CHANNEL_POST(A) is post-insert, post-fader");

            float returnIn = 0.5f * RETURN_GAIN; // post-fader send of the halved channel
            assertThat(returnPair.readInputInto(in)).isTrue();
            assertThat(returnPair.readOutputInto(out)).isTrue();
            assertLane(in, 0, returnIn, "return insert input (DOUBLE_64 chain)");
            assertLane(out, 0, 0.25f * returnIn, "return insert output (DOUBLE_64 chain)");
            assertLane(rig.frameReturn, 0, 0.25f * returnIn, "RETURN_POST is post-insert");

            channelInsert.setBypassed(true);
            rig.channelA.setInsertBypassed(0, true);
            long later = rig.renderBlocks(1);
            assertThat(channelPair.readInputInto(in)).isTrue();
            assertThat(in.blockIndex())
                    .as("a bypassed slot is absent from the chain and publishes nothing")
                    .isLessThan(later);
        }
    }

    /**
     * Two inserts on one channel: each {@code INSERT_IO} pair must meter ITS
     * OWN processor, and must keep doing so after a mid-chain removal. With a
     * single insert per chain (index 0 is always right) a tags/processors
     * off-by-one inside {@code EffectsChain} would be invisible — it would
     * silently meter the wrong plugin slot rather than fail.
     */
    @Test
    void twoInsertsOnOneChannelEachMeterTheirOwnProcessorAcrossAMidChainRemoval() {
        try (Rig rig = new Rig(MixPrecision.DOUBLE_64)) {
            InsertSlot half = new InsertSlot("Half", new GainProcessor(0.5f));
            InsertSlot quarter = new InsertSlot("Quarter", new GainProcessor(0.25f));
            rig.channelA.addInsert(half);
            rig.channelA.addInsert(quarter);
            rig.channelA.prepareEffectsChain(CHANNELS, BLOCK);

            InsertIoSubscription halfPair = rig.bus.attachInsertIo(
                    new MeterTapPoint.InsertIo(half.getPluginInstanceId()));
            InsertIoSubscription quarterPair = rig.bus.attachInsertIo(
                    new MeterTapPoint.InsertIo(quarter.getPluginInstanceId()));

            long stamp = rig.renderBlocks(BLOCKS);
            MeterFrame in = new MeterFrame();
            MeterFrame out = new MeterFrame();

            assertThat(halfPair.readInputInto(in)).isTrue();
            assertThat(halfPair.readOutputInto(out)).isTrue();
            assertLane(in, 0, 1.0f, "first insert input (the raw clip)");
            assertLane(out, 0, 0.5f, "first insert output");
            assertStamp(in, rig.bus.epoch(), stamp, "first insert input");

            assertThat(quarterPair.readInputInto(in)).isTrue();
            assertThat(quarterPair.readOutputInto(out)).isTrue();
            assertLane(in, 0, 0.5f, "second insert input is the first insert's output");
            assertLane(out, 0, 0.125f, "second insert output");
            assertStamp(out, rig.bus.epoch(), stamp, "second insert output");

            rig.readAll();
            assertLane(rig.frameA, 0, 0.125f * A_GAIN, "CHANNEL_POST(A) after both inserts");

            // Remove the FIRST insert: the survivor moves to index 0, and its
            // tag must move with it.
            rig.channelA.removeInsert(half);
            long later = rig.renderBlocks(2);

            assertThat(quarterPair.readInputInto(in)).isTrue();
            assertThat(quarterPair.readOutputInto(out)).isTrue();
            assertLane(in, 0, 1.0f, "the survivor now sees the raw clip");
            assertLane(out, 0, 0.25f, "the survivor still meters ITS OWN gain");
            assertStamp(out, rig.bus.epoch(), later, "survivor insert output");

            assertThat(halfPair.readInputInto(in)).isTrue();
            assertThat(in.blockIndex())
                    .as("the removed slot is absent from the chain and publishes nothing")
                    .isLessThan(later);

            rig.readAll();
            assertLane(rig.frameA, 0, 0.25f * A_GAIN, "CHANNEL_POST(A) after the removal");
        }
    }

    @Test
    void analysisRingAtMasterChainReceivesThePreFaderSamples() throws InterruptedException {
        try (Rig rig = new Rig(MixPrecision.DOUBLE_64)) {
            rig.mixer.getMasterChannel().setVolume(0.5);
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<float[][]> captured = new AtomicReference<>();
            AtomicInteger channelCount = new AtomicInteger();
            AtomicInteger frameCount = new AtomicInteger();
            AtomicReference<Double> sampleRate = new AtomicReference<>(0.0);
            AnalysisSubscription analysis = rig.bus.attachAnalysis(MeterTapPoint.MASTER_CHAIN, 8,
                    (samples, channels, numFrames, rate) -> {
                        if (captured.compareAndSet(null, copy(samples, channels, numFrames))) {
                            channelCount.set(channels);
                            frameCount.set(numFrames);
                            sampleRate.set(rate);
                            delivered.countDown();
                        }
                    });
            assertThat(rig.bus.snapshot().hasAnalysisRings()).isTrue();

            rig.renderBlocks(3);

            assertThat(delivered.await(5, TimeUnit.SECONDS)).as("analysis thread delivered").isTrue();
            assertThat(channelCount.get()).isEqualTo(CHANNELS);
            assertThat(frameCount.get()).isEqualTo(BLOCK);
            assertThat(sampleRate.get()).isEqualTo(SAMPLE_RATE);
            float[][] block = captured.get();
            assertThat(peakOf(block[0]))
                    .as("the ring carries the PRE-fader sum (exceeds full scale), not the halved output")
                    .isCloseTo(A_GAIN + B_LEFT_GAIN + RETURN_GAIN, within(TOLERANCE));
            assertThat(peakOf(block[1])).isCloseTo(A_GAIN + RETURN_GAIN, within(TOLERANCE));
            rig.readAll();
            assertThat(rig.frameMasterOut.peak(0))
                    .isCloseTo(0.5f * (A_GAIN + B_LEFT_GAIN + RETURN_GAIN), within(TOLERANCE));
            assertThat(analysis.droppedBlocks()).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void aDirectOutputChannelIsMeteredByRenderDirectOutputs(MixPrecision precision) {
        try (Rig rig = new Rig(precision, 4)) {
            rig.channelB.setOutputRouting(new OutputRouting(2, 2));
            long stamp = rig.renderBlocks(BLOCKS);
            rig.readAll();

            assertLane(rig.frameB, 0, B_LEFT_GAIN, "direct CHANNEL_POST(B)");
            assertLane(rig.frameB, 1, 0f, "direct CHANNEL_POST(B)");
            assertStamp(rig.frameB, rig.bus.epoch(), stamp, "direct CHANNEL_POST(B)");
            assertLane(rig.frameMasterChain, 0, A_GAIN + RETURN_GAIN, "MASTER_CHAIN excludes B");
            assertLane(rig.frameMasterOut, 0, A_GAIN + RETURN_GAIN, "MASTER_OUT excludes B");
            assertThat(peakOf(rig.output[2])).as("B landed on hardware lane 2")
                    .isCloseTo(B_LEFT_GAIN, within(TOLERANCE));

            rig.channelB.setMuted(true);
            stamp = rig.renderBlocks(1);
            rig.readAll();
            assertThat(rig.frameB.isSilent()).isTrue();
            assertStamp(rig.frameB, rig.bus.epoch(), stamp, "muted direct CHANNEL_POST(B)");
        }
    }

    @ParameterizedTest
    @EnumSource(MixPrecision.class)
    void aMonoSourcePannedIntoStereoMetersTwoLanesExactlyAsItSums(MixPrecision precision) {
        Mixer mixer = new Mixer();
        mixer.setMixPrecision(precision);
        MixerChannel mono = new MixerChannel("Mono");
        mono.setVolume(0.9);
        mono.setPan(0.5);
        mixer.addChannel(mono);
        mixer.prepareForPlayback(CHANNELS, BLOCK);
        MeteringTapBus bus = new MeteringTapBus();
        try {
            bus.rebind(mixer, FORMAT, 7L);
            LevelSubscription tap = bus.attachLevel(new MeterTapPoint.ChannelPost(mono.getId()));

            float[][][] channelBuffers = new float[1][1][BLOCK];
            for (int i = 0; i < BLOCK; i++) {
                channelBuffers[0][0][i] = (float) Math.sin(2.0 * Math.PI * CYCLES_PER_BLOCK * i / BLOCK);
            }
            float[][] output = new float[CHANNELS][BLOCK];
            float[][][] returnBuffers = new float[Mixer.MAX_RETURN_BUSES][CHANNELS][BLOCK];
            TapSnapshot taps = bus.snapshot();
            mixer.mixDown(channelBuffers, output, returnBuffers, BLOCK, taps);
            bus.blockCompleted(taps);

            double angle = (0.5 + 1.0) * 0.25 * Math.PI;
            float left = (float) (Math.cos(angle) * 0.9);
            float right = (float) (Math.sin(angle) * 0.9);
            MeterFrame frame = new MeterFrame();
            assertThat(tap.readInto(frame)).isTrue();
            assertThat(frame.channelCount()).as("mono source duplicated into two lanes").isEqualTo(2);
            assertLane(frame, 0, left, "mono CHANNEL_POST");
            assertLane(frame, 1, right, "mono CHANNEL_POST");
            assertThat(frame.epoch()).isEqualTo(7L);
            assertThat(peakOf(output[0])).isCloseTo(left, within(TOLERANCE));
            assertThat(peakOf(output[1])).isCloseTo(right, within(TOLERANCE));
        } finally {
            bus.close();
        }
    }

    @Test
    void anUntappedRenderIsUnchangedAndAnUnboundBusTapsNothing() {
        try (Rig rig = new Rig(MixPrecision.DOUBLE_64)) {
            rig.bus.unbind();
            assertThat(rig.tapA.isDisposed()).isTrue();
            TapSnapshot empty = rig.bus.snapshot();
            assertThat(empty.isEmpty()).isTrue();
            long before = rig.bus.blockIndex();
            rig.renderBlock();
            assertThat(rig.bus.blockIndex()).isEqualTo(before + 1);
            assertThat(peakOf(rig.output[0])).as("audio still renders while untapped")
                    .isCloseTo(A_GAIN + B_LEFT_GAIN + RETURN_GAIN, within(TOLERANCE));
            assertThat(rig.tapA.readInto(rig.frameA)).as("a disposed token reads nothing").isFalse();
        }
    }

    private static float peakOf(float[] lane) {
        float peak = 0f;
        for (float v : lane) {
            peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    private static float[][] copy(float[][] samples, int channels, int numFrames) {
        float[][] copy = new float[channels][numFrames];
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(samples[ch], 0, copy[ch], 0, numFrames);
        }
        return copy;
    }

    /** A transparent gain: output = input * gain, so INSERT_IO input and output differ by exactly the gain. */
    private static final class GainProcessor implements AudioProcessor {
        private final float gain;

        GainProcessor(float gain) {
            this.gain = gain;
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            int channels = Math.min(inputBuffer.length, outputBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[ch][f] = inputBuffer[ch][f] * gain;
                }
            }
        }

        @Override
        public boolean supportsDouble() {
            return true;
        }

        @Override
        public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
            int channels = Math.min(inputBuffer.length, outputBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                for (int f = 0; f < numFrames; f++) {
                    outputBuffer[ch][f] = inputBuffer[ch][f] * gain;
                }
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return CHANNELS;
        }

        @Override
        public int getOutputChannelCount() {
            return CHANNELS;
        }
    }
}
