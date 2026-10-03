package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 323 proof — the force cadence (book §2.1, §4.3, §5.3 "Bounded
 * risk"), measured on an injected clock: a segment's un-forced bytes are
 * forced once the configured cadence (5 s by default) has elapsed since its
 * last force — by the append that finds the cadence elapsed, and otherwise
 * by the {@code capture-flush} thread's cadence tick, which runs after every
 * block it applies and at the end of every drain pass. So bytes are forced
 * on cadence even when nothing more is appended to their segment: past a
 * punch-out, and while the ring is dry.
 */
class SegmentForceCadenceContractTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
    private static final int SECONDS = 20;
    private static final long GIB = 1L << 30;

    /**
     * Bound of {@link #awaitCondition}: two hundred times the
     * {@link CaptureFlushService#PARK_BACKSTOP} after which the idle pass
     * runs. It asserts no timing; it only turns "never" into a red test
     * instead of a hang (daw-core has no default JUnit timeout).
     */
    private static final Duration WAIT_BUDGET = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    void theDefaultCadenceIsFiveSeconds() {
        assertThat(SegmentWriter.DEFAULT_FORCE_CADENCE).isEqualTo(Duration.ofSeconds(5));
        Track track = RampCaptureTestSupport.armedMonoTrack("Bass");
        RecordingPipeline pipeline = new RecordingPipeline(new AudioEngine(MONO_16), new Transport(), MONO_16,
                tempDir.resolve("default"), List.of(track));
        assertThat(pipeline.getForceCadence()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void unforcedBytesNeverExceedFiveSecondsAndForcesLandExactlyOnTheBoundaries() throws IOException {
        long forcesAtFive = recordOneBlockPerSecond(tempDir.resolve("five"), Duration.ofSeconds(5));
        long forcesAtOne = recordOneBlockPerSecond(tempDir.resolve("one"), Duration.ofSeconds(1));

        assertThat(forcesAtFive).isEqualTo(SECONDS / 5);
        assertThat(forcesAtOne).as("a 1 s cadence forces five times as often").isEqualTo(5 * forcesAtFive);
    }

    @Test
    void aConfiguredCadenceReachesTheWriterThroughThePipelinesOwnSessionFactory() {
        // Nothing here builds a session: cadence, clock and channel opener
        // are set on the pipeline, and its own factory has to carry all
        // three to the writer.
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Keys");
        AtomicLong clock = new AtomicLong(0);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16,
                tempDir.resolve("configured"), List.of(track));
        Duration cadence = Duration.ofSeconds(1);
        assertThat(cadence).as("fixture: not the default").isNotEqualTo(SegmentWriter.DEFAULT_FORCE_CADENCE);
        pipeline.setForceCadence(cadence);
        pipeline.setNanoClock(clock::get);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        startRecording(pipeline);

        assertThat(pipeline.getSession(track).getForceCadence()).isEqualTo(cadence);
        SegmentWriter writer = pipeline.getSession(track).getCurrentWriter();
        assertThat(writer.forceCadence()).as("the writer's cadence is the pipeline's").isEqualTo(cadence);
        assertThat(journal.events()).as("the segment was opened through the pipeline's opener").isNotEmpty();
        assertThat(journal.forces(false)).isZero();

        float[][] output = new float[1][BLOCK_FRAMES];
        for (int second = 1; second <= 3; second++) {
            long firstFrame = (long) (second - 1) * BLOCK_FRAMES;
            publishAt(pipeline, clock, second * SECOND, () -> {
                engine.processBlock(rampBlock(firstFrame), output, BLOCK_FRAMES);
                advanceOneBlock(transport);
            });
            assertThat(journal.forces(false))
                    .as("force(false) calls that reached the channel at t=%ds on the injected clock", second)
                    .isEqualTo(second);
        }

        assertThat(writer.forceCount()).isEqualTo(3);
        assertThat(writer.bytesSinceForce()).isZero();
        stopRecording(pipeline);
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(3);
    }

    @Test
    void bytesLeftBehindByAPunchOutAreForcedOnCadenceWithoutAnotherAppend() {
        Transport transport = new Transport();
        transport.setPunchRegion(new PunchRegion(0L, 2L * BLOCK_FRAMES, true));
        Rig rig = startedRig(tempDir.resolve("punch-out"), transport, message -> { });
        List<Seen> seen = new CopyOnWriteArrayList<>();
        rig.service().setBlockObserver((sequence, startFrame, numFrames) ->
                seen.add(new Seen(startFrame, rig.journal().forces(false))));

        rig.clock().set(1 * SECOND);
        rig.feed(0);             // inside the punch region: recorded
        rig.feed(BLOCK_FRAMES);  // the block that ends at the punch-out: recorded
        rig.pipeline().awaitFlushed();
        SegmentWriter writer = rig.writer();
        assertThat(writer.frameCount()).as("fixture: both blocks inside the punch were recorded")
                .isEqualTo(2L * BLOCK_FRAMES);
        assertThat(writer.bytesSinceForce()).as("fixture: and are not forced yet").isEqualTo(2 * BLOCK_BYTES);
        assertThat(rig.journal().forces(false)).isZero();
        long appends = dataWrites(rig.journal());

        // Past the punch-out every block is gated and appends nothing. The
        // loop is held between passes while the clock crosses the cadence
        // and three such blocks are published, so one resumed pass applies
        // all three: the observer reads the channel's force count as each is
        // finished, before that pass has found the ring empty.
        rig.service().setDrainPaused(true);
        rig.clock().set(5 * SECOND);
        for (int b = 2; b < 5; b++) {
            rig.feed((long) b * BLOCK_FRAMES);
        }
        rig.service().setDrainPaused(false);
        rig.pipeline().awaitFlushed();

        assertThat(dataWrites(rig.journal())).as("no further append reached the channel").isEqualTo(appends);
        assertThat(writer.frameCount()).isEqualTo(2L * BLOCK_FRAMES);
        assertThat(writer.bytesSinceForce()).as("the punched-out bytes are forced").isZero();
        assertThat(writer.forceCount()).isEqualTo(1);
        assertThat(writer.lastForceNanos()).isEqualTo(5 * SECOND);

        stopRecording(rig.pipeline());
        assertThat(seen).as("force(false) calls on the channel as each block was finished (read once the stop"
                        + " has returned: the thread has terminated)")
                .containsExactly(
                        new Seen(0, 0),
                        new Seen(BLOCK_FRAMES, 0),
                        new Seen(2L * BLOCK_FRAMES, 1),
                        new Seen(3L * BLOCK_FRAMES, 1),
                        new Seen(4L * BLOCK_FRAMES, 1));
        assertThat(rig.journal().forces(false)).as("the seal adds no cadence force").isEqualTo(1);
        assertThat(rig.journal().forces(true)).as("the seal forces the data, then the patched header").isEqualTo(2);
    }

    @Test
    void bytesOnADryRingAreForcedOnCadenceByTheIdlePass() {
        Rig rig = startedRig(tempDir.resolve("dry-ring"), new Transport(), message -> { });
        rig.clock().set(1 * SECOND);
        rig.feed(0);
        rig.pipeline().awaitFlushed();
        SegmentWriter writer = rig.writer();
        assertThat(writer.bytesSinceForce()).as("fixture: one block, not forced yet").isEqualTo(BLOCK_BYTES);
        assertThat(rig.journal().forces(false)).isZero();
        long appends = dataWrites(rig.journal());
        long published = rig.pipeline().getCaptureRing().publishedBlocks();

        // Nothing more arrives — a device that stopped calling back. Only
        // the passes the loop runs after each park backstop can force these.
        rig.clock().set(5 * SECOND);
        awaitCondition("a force(false) of the dry ring's un-forced bytes", () -> rig.journal().forces(false) >= 1);

        assertThat(rig.pipeline().getCaptureRing().publishedBlocks()).as("no block was published meanwhile")
                .isEqualTo(published);
        assertThat(dataWrites(rig.journal())).as("no further append reached the channel").isEqualTo(appends);
        stopRecording(rig.pipeline());
        assertThat(writer.forceCount()).as("one cadence force (read once the stop has returned: the thread has terminated)")
                .isEqualTo(1);
        assertThat(writer.lastForceNanos()).isEqualTo(5 * SECOND);
        assertThat(rig.journal().forces(false)).as("the seal adds no cadence force").isEqualTo(1);
        assertThat(rig.journal().forces(true)).as("the seal forces the data, then the patched header").isEqualTo(2);
    }

    /**
     * The cadence tick stands down in the stop's final sweep. Nothing is
     * published into the sweep, so the only check that could meet the due
     * bytes there is the tick's; an append the sweep applies still makes
     * its own check, which this test does not exercise.
     */
    @Test
    void theCadenceTickStandsDownInTheStopsFinalSweepAndLeavesDueBytesToTheSeal() {
        Rig rig = startedRig(tempDir.resolve("final-sweep"), new Transport(), message -> { });
        rig.clock().set(1 * SECOND);
        rig.feed(0);
        rig.pipeline().awaitFlushed();
        rig.clock().set(5 * SECOND);
        awaitCondition("fixture: the idle pass forces due bytes", () -> rig.journal().forces(false) >= 1);

        rig.clock().set(6 * SECOND);
        rig.feed(BLOCK_FRAMES);
        rig.pipeline().awaitFlushed();
        SegmentWriter writer = rig.writer();
        assertThat(writer.bytesSinceForce()).as("fixture: the second block is not forced yet").isEqualTo(BLOCK_BYTES);
        assertThat(rig.journal().forces(false)).isEqualTo(1);

        // The cadence runs out while the loop is held between passes, and
        // Stop arrives before any pass has met the due bytes: the final
        // sweep, with an empty ring, is the first pass to meet them, its
        // tick stands down, and the seal right behind it forces them with
        // force(true).
        rig.service().setDrainPaused(true);
        rig.clock().set(20 * SECOND);
        stopRecording(rig.pipeline());

        assertThat(writer.isSealed()).isTrue();
        assertThat(writer.frameCount()).isEqualTo(2L * BLOCK_FRAMES);
        assertThat(rig.journal().forces(false)).as("no cadence force ahead of the seal").isEqualTo(1);
        assertThat(rig.journal().forces(true)).as("the seal forces the data, then the patched header").isEqualTo(2);
    }

    @Test
    void aWriterWithNothingUnforcedIsNotForcedAgainWhileItsTrackRecordsNothing() {
        Transport transport = new Transport();
        transport.setPunchRegion(new PunchRegion(0L, BLOCK_FRAMES, true));
        Rig rig = startedRig(tempDir.resolve("idle-writer"), transport, message -> { });
        rig.clock().set(1 * SECOND);
        rig.feed(0); // the only block inside the punch region
        rig.pipeline().awaitFlushed();
        publishAt(rig.pipeline(), rig.clock(), 5 * SECOND, () -> rig.feed(BLOCK_FRAMES));
        assertThat(rig.journal().forces(false)).as("fixture: the tick forced the recorded block").isEqualTo(1);
        SegmentWriter writer = rig.writer();
        assertThat(writer.bytesSinceForce()).isZero();

        // Every later block is gated and lands at least a whole cadence
        // after the last force: the tick runs for each of them and finds
        // nothing to force.
        for (int n = 2; n <= 5; n++) {
            long firstFrame = (long) n * BLOCK_FRAMES;
            publishAt(rig.pipeline(), rig.clock(), n * 5 * SECOND, () -> rig.feed(firstFrame));
            assertThat(rig.journal().forces(false)).as("force(false) calls at t=%ds", n * 5).isEqualTo(1);
        }
        assertThat(writer.forceCount()).isEqualTo(1);
        assertThat(writer.lastForceNanos()).as("the cadence force stayed where the bytes were forced")
                .isEqualTo(5 * SECOND);
        assertThat(writer.frameCount()).isEqualTo(BLOCK_FRAMES);

        stopRecording(rig.pipeline());
        assertThat(rig.journal().forces(false)).isEqualTo(1);
        assertThat(rig.journal().forces(true)).isEqualTo(2);
    }

    @Test
    void aCadenceTickWhoseForceThrowsAnIoExceptionSealsTheTakeAsAWriteFailure() throws IOException {
        Throwable failure = tickFailureSealsTheTake("tick-io-failure", journal -> journal.failNextForces(1));

        assertThat(failure).isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("force failed on")
                .hasRootCauseMessage("injected force(false) failure (test double)");
    }

    @Test
    void aCadenceTickWhoseForceThrowsARuntimeExceptionSealsTheTakeAsAWriteFailure() throws IOException {
        IllegalStateException fault = new IllegalStateException("injected force fault");
        AtomicBoolean thrown = new AtomicBoolean();
        Throwable failure = tickFailureSealsTheTake("tick-runtime-failure", journal -> journal.beforeForce(() -> {
            if (thrown.compareAndSet(false, true)) {
                throw fault; // this one force only: the seal's forces go through
            }
        }));

        assertThat(failure).isSameAs(fault);
    }

    /**
     * The force that fails is the append's own cadence check: the block
     * that lands on the boundary is written, then forced, and the force
     * throws. The early seal then seals that block too, so the session's
     * counters, its RAM mirror (the clip's audio), the clip's duration, the
     * sealed segment and the manifest must all hold the same frames — the
     * block included.
     */
    @Test
    void anAppendWhoseCadenceForceFailsEndsTheTakeWithSessionClipSegmentAndManifestAgreeing() throws IOException {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Rig rig = startedRig(tempDir.resolve("append-force-failure"), new Transport(), warnings::add);
        for (int second = 1; second <= 4; second++) {
            long firstFrame = (long) (second - 1) * BLOCK_FRAMES;
            publishAt(rig.pipeline(), rig.clock(), second * SECOND, () -> rig.feed(firstFrame));
        }
        CaptureFlushService service = rig.service();
        RecordingSession session = rig.pipeline().getSession(rig.track());
        SegmentWriter writer = rig.writer();
        assertThat(writer.bytesSinceForce()).as("fixture: four blocks, none forced yet").isEqualTo(4 * BLOCK_BYTES);
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(4L * BLOCK_FRAMES);

        // Armed while the loop is held, so the first force after it is the
        // one the boundary block's append makes (the tick runs after it).
        publishAt(rig.pipeline(), rig.clock(), 5 * SECOND, () -> {
            rig.journal().failNextForces(1);
            rig.feed(4L * BLOCK_FRAMES);
        });
        assertThat(service.isSealed()).as("the failed force sealed the take early").isTrue();
        // Blocks still arrive after the early seal; they are discarded.
        publishAt(rig.pipeline(), rig.clock(), 6 * SECOND, () -> rig.feed(5L * BLOCK_FRAMES));
        publishAt(rig.pipeline(), rig.clock(), 7 * SECOND, () -> rig.feed(6L * BLOCK_FRAMES));
        assertThat(service.discardedBlocks()).isEqualTo(2);

        List<AudioClip> clips = stopRecording(rig.pipeline());

        long frames = 5L * BLOCK_FRAMES;
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(service.lastFailure()).hasValueSatisfying(failure -> assertThat(failure)
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("injected force(false) failure (test double)"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("Recording stopped early"));
        assertThat(rig.journal().forces(false)).as("the failed force never reached the file").isZero();
        assertThat(rig.journal().forces(true)).as("the early seal forces the data, then the patched header")
                .isEqualTo(2);

        assertThat(writer.isSealed()).isTrue();
        assertThat(writer.frameCount()).as("the boundary block was written before its force failed")
                .isEqualTo(frames);
        assertThat(SegmentFile.readFrames(writer.sealedPath())[0]).as("the sealed segment").hasSize((int) frames);
        TakeManifest manifest = TakeManifest.read(rig.pipeline().getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(manifest.segmentsFor(rig.track().getId()))
                .extracting(TakeManifest.SegmentEntry::frames, TakeManifest.SegmentEntry::state)
                .containsExactly(tuple(frames, TakeManifest.SegmentState.SEALED));

        assertThat(session.getTotalSamplesRecorded()).as("the session reports what the segment holds")
                .isEqualTo(frames);
        assertThat(session.getCapturedSampleCount()).isEqualTo((int) frames);
        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        assertThat(clip.getSourceSegmentPaths()).containsExactly(writer.sealedPath().toAbsolutePath().toString());
        assertThat(clip.getAudioData()[0]).as("the clip's audio, from the RAM mirror").hasSize((int) frames);
        double seconds = frames / MONO_16.sampleRate();
        assertThat(clip.getDurationBeats()).as("the clip's duration")
                .isEqualTo(seconds * (rig.transport().getTempo() / 60.0));
    }

    /**
     * Records one block, arms {@code fault} on the segment's channel, moves
     * the clock past the cadence with the ring dry, and checks that the take
     * ends exactly as a failed append ends it: sealed early with everything
     * captured, {@code aborted}/{@code write-failure} in the manifest, a
     * warning, and a flush thread that is still running.
     *
     * @return the failure the flush service recorded
     */
    private Throwable tickFailureSealsTheTake(String name, Consumer<ObservedFileChannel.Journal> fault)
            throws IOException {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Rig rig = startedRig(tempDir.resolve(name), new Transport(), warnings::add);
        rig.clock().set(1 * SECOND);
        rig.feed(0);
        rig.pipeline().awaitFlushed();
        CaptureFlushService service = rig.service();
        SegmentWriter writer = rig.writer();

        fault.accept(rig.journal());
        rig.clock().set(5 * SECOND);
        awaitCondition("the early seal that answers the failed cadence force", service::isSealed);
        assertThat(service.isRunning()).as("the failure was not thrown out of the loop").isTrue();

        List<?> clips = stopRecording(rig.pipeline());
        assertThat(clips).as("the take keeps what was captured").hasSize(1);
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("Recording stopped early"));
        TakeManifest manifest = TakeManifest.read(rig.pipeline().getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(manifest.segmentsFor(rig.track().getId()))
                .extracting(TakeManifest.SegmentEntry::frames, TakeManifest.SegmentEntry::state)
                .containsExactly(tuple((long) BLOCK_FRAMES, TakeManifest.SegmentState.SEALED));
        assertThat(writer.isSealed()).isTrue();
        assertThat(SegmentFile.readFrames(writer.sealedPath())[0]).hasSize(BLOCK_FRAMES);
        assertThat(rig.journal().forces(false)).as("the failed force never reached the file").isZero();
        assertThat(rig.journal().forces(true)).as("the early seal forces the data, then the patched header")
                .isEqualTo(2);
        return service.lastFailure().orElseThrow();
    }

    /**
     * Feeds one block per simulated second for {@link #SECONDS} seconds and
     * asserts, after every block, that the active writer's un-forced bytes
     * stay within one cadence of data and that {@code forceCount()} equals
     * the number of whole cadences elapsed.
     *
     * @return the writer's final force count
     */
    private long recordOneBlockPerSecond(Path takeDir, Duration cadence) throws IOException {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        AtomicLong clock = new AtomicLong(0);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setNanoClock(clock::get);
        pipeline.setForceCadence(cadence);
        // The segment's channel is wrapped so the test counts the force
        // CALLS that reach it; the session is the one the pipeline's own
        // factory builds, which carries cadence, clock and opener to it.
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        startRecording(pipeline);
        assertThat(journal.forces(false)).as("no force before the first cadence boundary").isZero();

        long cadenceSeconds = cadence.toSeconds();
        long maxUnforcedBytes = cadenceSeconds * BLOCK_BYTES; // one block per second
        float[][] output = new float[1][BLOCK_FRAMES];
        long frame = 0;
        long forces = 0;
        for (int second = 1; second <= SECONDS; second++) {
            long firstFrame = frame;
            publishAt(pipeline, clock, second * SECOND, () -> {
                engine.processBlock(rampBlock(firstFrame), output, BLOCK_FRAMES);
                advanceOneBlock(transport);
            });
            frame += BLOCK_FRAMES;

            SegmentWriter writer = pipeline.getSession(track).getCurrentWriter();
            assertThat(writer.forceCadence()).isEqualTo(cadence);
            assertThat(writer.bytesSinceForce())
                    .as("un-forced bytes at t=%ds (cadence %ds)", second, cadenceSeconds)
                    .isLessThanOrEqualTo(maxUnforcedBytes);
            long expectedForces = second / cadenceSeconds;
            assertThat(writer.forceCount())
                    .as("forces at t=%ds (cadence %ds)", second, cadenceSeconds)
                    .isEqualTo(expectedForces);
            assertThat(journal.forces(false))
                    .as("force(false) calls that reached the channel at t=%ds (cadence %ds)", second, cadenceSeconds)
                    .isEqualTo(expectedForces);
            if (second % cadenceSeconds == 0) {
                assertThat(writer.bytesSinceForce()).as("just forced at t=%ds", second).isZero();
            } else {
                assertThat(writer.bytesSinceForce()).isEqualTo((second % cadenceSeconds) * BLOCK_BYTES);
            }
            forces = writer.forceCount();
        }

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.forceCadenceMillis()).isEqualTo(cadence.toMillis());
        assertThat(journal.forces(true)).as("no metadata force while the segment streams").isZero();
        stopRecording(pipeline);
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(forces);
        assertThat(journal.forces(true)).as("the seal forces the data, then the patched header").isEqualTo(2);
        return forces;
    }

    /**
     * Publishes one block at simulated time {@code nanos} and waits until it
     * has been applied. In these simulations the clock step and the block's
     * arrival are one instant, so no flush-thread pass may run between them:
     * the loop is held between passes ({@code setDrainPaused(true)} returns
     * once no pass is running) while the clock moves and the block is
     * published, and the pass that resumes applies the block before its
     * end-of-pass tick. Without the hold, an idle pass could meet the new
     * time first and force the earlier bytes a moment before the block lands
     * — a schedule that keeps the bound, but not the one the exact
     * per-boundary counts describe. The block's own application, and the
     * cadence check the append and the tick make there, are not affected.
     */
    private static void publishAt(RecordingPipeline pipeline, AtomicLong clock, long nanos, Runnable publish) {
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.setDrainPaused(true);
        clock.set(nanos);
        publish.run();
        service.setDrainPaused(false);
        pipeline.awaitFlushed();
    }

    /**
     * Waits, for at most {@link #WAIT_BUDGET}, until {@code condition}
     * holds, and fails the test otherwise. The condition is re-read every
     * millisecond; nothing in it waits.
     */
    private static void awaitCondition(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT_BUDGET.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(what + " did not happen within " + WAIT_BUDGET);
            }
            LockSupport.parkNanos(1_000_000L);
        }
    }

    /** Data appends that reached the segment's channel: positional writes at or past the 44-byte header. */
    private static long dataWrites(ObservedFileChannel.Journal journal) {
        return journal.events().stream()
                .filter(event -> event.startsWith("write@"))
                .filter(event -> Long.parseLong(event.substring("write@".length())) >= SegmentWriter.DATA_OFFSET)
                .count();
    }

    /** One block the flush thread finished, and the {@code force(false)} calls on the channel at that moment. */
    private record Seen(long startFrame, long forcesSoFar) {
    }

    /** A started one-track take whose segment channel is journalled and whose clock is injected (default 5 s cadence). */
    private record Rig(AudioEngine engine, Transport transport, Track track, AtomicLong clock,
                       ObservedFileChannel.Journal journal, RecordingPipeline pipeline) {

        CaptureFlushService service() {
            return pipeline.getCaptureFlushService();
        }

        SegmentWriter writer() {
            return pipeline.getSession(track).getCurrentWriter();
        }

        /** One ramp block starting at {@code firstFrame} through the recording callback; the transport then advances a block. */
        void feed(long firstFrame) {
            engine.processBlock(rampBlock(firstFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
            advanceOneBlock(transport);
        }
    }

    private static Rig startedRig(Path takeDir, Transport transport, Consumer<String> warningSink) {
        AudioEngine engine = new AudioEngine(MONO_16);
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        AtomicLong clock = new AtomicLong(0);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setNanoClock(clock::get);
        pipeline.setWarningSink(warningSink);
        // A fixed probe: the real one would warn (or seal) on a nearly full disk.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warningSink));
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        startRecording(pipeline);
        assertThat(pipeline.getForceCadence()).as("fixture: the default cadence").isEqualTo(Duration.ofSeconds(5));
        return new Rig(engine, transport, track, clock, journal, pipeline);
    }
}
