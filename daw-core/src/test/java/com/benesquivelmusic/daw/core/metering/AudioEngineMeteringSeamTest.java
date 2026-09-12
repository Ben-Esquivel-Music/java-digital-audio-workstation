package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 318 — the PRODUCTION engine seam, driven through
 * {@link AudioEngine#processBlock(float[][], float[][], int, float[])}.
 *
 * <p>Every other metering test hand-rolls the three lines that make meters
 * work in the shipped app — hoist {@code meteringTapBus.snapshot()}, hand it
 * to {@code renderPipeline.renderBlock(...)}, then
 * {@code meteringTapBus.blockCompleted(taps)} — inside its own rig. That
 * leaves the real seam unpinned: dropping the {@code taps} argument, or the
 * {@code blockCompleted} call, would leave the whole suite green while every
 * meter in the product went dark or froze. This test drives the engine
 * itself, so it fails on either deletion:</p>
 * <ul>
 *   <li>no {@code taps} argument → nothing publishes → {@code readInto}
 *       returns {@code false} and the peak assertions fail;</li>
 *   <li>no {@code blockCompleted} → the block counter never advances, so
 *       every frame carries the same {@code blockIndex} and the
 *       "advanced between drives" assertion fails.</li>
 * </ul>
 */
class AudioEngineMeteringSeamTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int BLOCK = 64;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 24, BLOCK);
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;
    private static final int TOTAL_FRAMES = BLOCK * 64;

    @Test
    void processBlockPublishesAndStampsEveryTapOfTheBoundProject() {
        DawProject project = new DawProject("Seam", FORMAT);
        project.getTransport().setTempo(TEMPO);
        Track track = project.createAudioTrack("Lead");
        track.addClip(sineClip());
        MixerChannel channel = project.getMixerChannelForTrack(track);

        AudioEngine engine = new AudioEngine(FORMAT);
        try {
            new EngineBinder(engine).bind(project);
            project.getTransport().play();
            engine.start();

            MeteringTapBus bus = engine.meteringTapBus();
            LevelSubscription masterOut = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            LevelSubscription masterChain = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
            LevelSubscription channelPost =
                    bus.attachLevel(new MeterTapPoint.ChannelPost(channel.getId()));

            drive(engine, 8);

            MeterFrame masterOutFrame = new MeterFrame();
            MeterFrame masterChainFrame = new MeterFrame();
            MeterFrame channelFrame = new MeterFrame();
            assertThat(masterOut.readInto(masterOutFrame))
                    .as("processBlock hands the snapshot to renderBlock: MASTER_OUT published")
                    .isTrue();
            assertThat(masterChain.readInto(masterChainFrame))
                    .as("MASTER_CHAIN published").isTrue();
            assertThat(channelPost.readInto(channelFrame))
                    .as("CHANNEL_POST published for the bound project's channel").isTrue();

            assertThat(masterOutFrame.maxPeak())
                    .as("the engine's own render carries audible level to MASTER_OUT")
                    .isGreaterThan(0.1f);
            assertThat(channelFrame.maxPeak())
                    .as("and to the track's CHANNEL_POST").isGreaterThan(0.1f);

            long epoch = bus.epoch();
            long stamp = masterOutFrame.blockIndex();
            assertThat(masterChainFrame.blockIndex())
                    .as("all taps of one block carry the same stamp").isEqualTo(stamp);
            assertThat(channelFrame.blockIndex()).isEqualTo(stamp);
            for (MeterFrame frame : new MeterFrame[] {
                    masterOutFrame, masterChainFrame, channelFrame }) {
                assertThat(frame.epoch()).as("stamped with the binder's epoch").isEqualTo(epoch);
            }

            drive(engine, 4);
            assertThat(masterOut.readInto(masterOutFrame)).isTrue();
            assertThat(masterOutFrame.blockIndex())
                    .as("blockCompleted advanced the counter across the second drive")
                    .isGreaterThan(stamp);
        } finally {
            engine.shutdown();
        }
    }

    private static void drive(AudioEngine engine, int blocks) {
        float[][] input = new float[CHANNELS][BLOCK];
        float[][] output = new float[CHANNELS][BLOCK];
        float[] interleaved = new float[CHANNELS * BLOCK];
        for (int block = 0; block < blocks; block++) {
            engine.processBlock(input, output, BLOCK, interleaved);
        }
    }

    @Test
    void renderingStopsPublishingWhenTheLastMeterDetaches() {
        var project = new DawProject("No meter demand", FORMAT);
        var track = project.createAudioTrack("Lead");
        track.addClip(sineClip());
        var channel = project.getMixerChannelForTrack(track);
        var engine = new AudioEngine(FORMAT);
        try {
            new EngineBinder(engine).bind(project);
            project.getTransport().play();
            engine.start();
            var bus = engine.meteringTapBus();
            var channelMeter = bus.attachLevel(new MeterTapPoint.ChannelPost(channel.getId()));
            var masterMeter = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            var observed = bus.snapshot();
            drive(engine, 2);
            var frame = new MeterFrame();
            assertThat(channelMeter.readInto(frame)).isTrue();
            long lastPublished = frame.blockIndex();
            channelMeter.dispose();
            masterMeter.dispose();
            assertThat(bus.snapshot().isEmpty()).isTrue();
            drive(engine, 3);
            assertThat(observed.channelSlot(0, channel).readInto(frame)).isTrue();
            assertThat(frame.blockIndex()).isEqualTo(lastPublished);
            assertThat(observed.masterOut().readInto(frame)).isTrue();
            assertThat(frame.blockIndex()).isEqualTo(lastPublished);
        } finally {
            engine.shutdown();
        }
    }

    @ParameterizedTest(name = "late failure={0}, Error={1}")
    @CsvSource({"false,false", "true,false", "true,true"})
    void failedRenderPublishesOnlySilenceAndTheNextHealthyBlockGetsANewStamp(
            boolean lateFailure, boolean error) throws Exception {
        var project = new DawProject("Failed metering render", FORMAT);
        var track = project.createAudioTrack("Lead");
        track.addClip(sineClip());
        var channel = project.getMixerChannelForTrack(track);
        var returnBus = project.getMixer().getReturnBuses().getFirst();
        channel.addSend(new Send(returnBus, 0.5, SendMode.POST_FADER));
        var insert = new InsertSlot("Active", new PassThrough());
        var unvisitedInsert = new InsertSlot("Bypassed", new PassThrough());
        unvisitedInsert.setBypassed(true);
        channel.addInsert(insert);
        channel.addInsert(unvisitedInsert);
        var points = List.of(new MeterTapPoint.ChannelPost(channel.getId()),
                new MeterTapPoint.ReturnPost(returnBus.getId()), MeterTapPoint.MASTER_CHAIN,
                MeterTapPoint.MASTER_OUT, new MeterTapPoint.InsertIo(insert.getPluginInstanceId()),
                new MeterTapPoint.InsertIo(unvisitedInsert.getPluginInstanceId()));
        var engine = new AudioEngine(FORMAT);
        var failing = new AtomicBoolean();
        Throwable original = error ? new AssertionError("late render fault")
                : new IllegalStateException("render fault");
        try {
            new EngineBinder(engine).bind(project);
            project.getTransport().play();
            engine.start();
            drive(engine, 8);
            var bus = engine.meteringTapBus();
            var levels = new ArrayList<LevelSubscription>();
            var analysis = new LinkedBlockingQueue<AnalyzedBlock>();
            for (MeterTapPoint point : points) {
                levels.add(bus.attachLevel(point));
                bus.attachAnalysis(point, 4, (samples, channels, frames, rate) -> {
                    float peak = 0f;
                    for (int lane = 0; lane < channels; lane++) {
                        for (int frame = 0; frame < frames; frame++) {
                            peak = Math.max(peak, Math.abs(samples[lane][frame]));
                        }
                    }
                    analysis.add(new AnalyzedBlock(point, peak, channels, frames));
                });
            }
            var activeIo = bus.attachInsertIo((MeterTapPoint.InsertIo) points.get(4));
            var unvisitedIo = bus.attachInsertIo((MeterTapPoint.InsertIo) points.get(5));
            drive(engine, 1);
            for (int i = 0; i < points.size() - 1; i++) {
                var block = analysis.poll(2, TimeUnit.SECONDS);
                assertThat(block).isNotNull();
                assertThat(block.peak()).isPositive();
            }
            long previousStamp = bus.blockIndex() - 1;
            Runnable fault = () -> {
                if (!failing.get()) {
                    return;
                }
                // The FX reader and independently waking analysis thread must
                // never observe the tentative block, even at the final tap.
                var reader = CompletableFuture.runAsync(() -> {
                    var frame = new MeterFrame();
                    for (var level : levels) {
                        if (level.readInto(frame)) {
                            assertThat(frame.blockIndex()).isEqualTo(previousStamp);
                        }
                    }
                    if (activeIo.readInputInto(frame)) {
                        assertThat(frame.blockIndex()).isEqualTo(previousStamp);
                    }
                    try {
                        assertThat(analysis.poll(75, TimeUnit.MILLISECONDS)).isNull();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                });
                reader.orTimeout(2, TimeUnit.SECONDS).join();
                if (original instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) original;
            };
            if (lateFailure) {
                project.getTransport().addChangeListener(kind -> {
                    if (kind == Transport.ChangeKind.POSITION) {
                        fault.run();
                    }
                });
            } else {
                engine.setRecordingCallback((input, frames) -> fault.run());
            }
            failing.set(true);
            assertThatThrownBy(() -> drive(engine, 1)).isSameAs(original);
            failing.set(false);

            long failedStamp = previousStamp + 1;
            assertThat(bus.blockIndex()).isEqualTo(failedStamp + 1);
            var frame = new MeterFrame();
            for (var level : levels) {
                assertThat(level.readInto(frame)).isTrue();
                assertSilentFrame(frame, failedStamp, bus.epoch());
            }
            for (var io : List.of(activeIo, unvisitedIo)) {
                assertThat(io.readInputInto(frame)).isTrue();
                assertSilentFrame(frame, failedStamp, bus.epoch());
                assertThat(io.readOutputInto(frame)).isTrue();
                assertSilentFrame(frame, failedStamp, bus.epoch());
            }
            var abortedPoints = new ArrayList<MeterTapPoint>();
            for (int i = 0; i < points.size(); i++) {
                var block = analysis.poll(2, TimeUnit.SECONDS);
                assertThat(block).isNotNull();
                assertThat(block.peak()).isZero();
                assertThat(block.channels()).isEqualTo(CHANNELS);
                assertThat(block.frames()).isEqualTo(BLOCK);
                abortedPoints.add(block.point());
            }
            assertThat(abortedPoints).containsExactlyInAnyOrderElementsOf(points);
            assertThat(analysis).isEmpty();

            drive(engine, 1);
            for (int i = 0; i < levels.size() - 1; i++) {
                assertThat(levels.get(i).readInto(frame)).isTrue();
                assertThat(frame.blockIndex()).isEqualTo(failedStamp + 1);
                assertThat(frame.maxPeak()).isPositive();
                var block = analysis.poll(2, TimeUnit.SECONDS);
                assertThat(block).isNotNull();
                assertThat(block.peak()).isPositive();
            }
            assertThat(analysis).isEmpty();
        } finally {
            failing.set(false);
            engine.shutdown();
        }
    }

    @Test
    void invalidOutputStillCompletesItsAttemptAndDoesNotStrandDeferredSlots() {
        var project = new DawProject("Invalid output", FORMAT);
        var engine = new AudioEngine(FORMAT);
        try {
            new EngineBinder(engine).bind(project);
            var meter = engine.meteringTapBus().attachLevel(MeterTapPoint.MASTER_OUT);
            engine.start();
            assertThatThrownBy(() -> engine.processBlock(new float[CHANNELS][BLOCK], null, BLOCK))
                    .isInstanceOf(NullPointerException.class);
            assertThat(engine.meteringTapBus().blockIndex()).isEqualTo(1L);
            var frame = new MeterFrame();
            assertThat(meter.readInto(frame)).isTrue();
            assertSilentFrame(frame, 0L, engine.meteringTapBus().epoch());
            drive(engine, 1);
            assertThat(meter.readInto(frame)).isTrue();
            assertThat(frame.blockIndex()).isEqualTo(1L);
        } finally {
            engine.shutdown();
        }
    }

    private static void assertSilentFrame(MeterFrame frame, long stamp, long epoch) {
        assertThat(frame.blockIndex()).isEqualTo(stamp);
        assertThat(frame.epoch()).isEqualTo(epoch);
        assertThat(frame.channelCount()).isEqualTo(CHANNELS);
        assertThat(frame.maxPeak()).isZero();
        assertThat(frame.clipped()).isFalse();
        for (int lane = 0; lane < CHANNELS; lane++) {
            assertThat(frame.rms(lane)).isZero();
        }
    }

    private record AnalyzedBlock(MeterTapPoint point, float peak, int channels, int frames) { }

    private static final class PassThrough implements AudioProcessor {
        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int lane = 0; lane < output.length; lane++) {
                System.arraycopy(input[lane], 0, output[lane], 0, frames);
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return CHANNELS; }
        @Override public int getOutputChannelCount() { return CHANNELS; }
    }

    private static AudioClip sineClip() {
        AudioClip clip = new AudioClip("Clip", 0.0, TOTAL_FRAMES / SAMPLES_PER_BEAT, null);
        float[][] data = new float[CHANNELS][TOTAL_FRAMES];
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * 8.0 * i / BLOCK) * 0.8f;
            data[0][i] = v;
            data[1][i] = v;
        }
        clip.setAudioData(data);
        return clip;
    }
}
