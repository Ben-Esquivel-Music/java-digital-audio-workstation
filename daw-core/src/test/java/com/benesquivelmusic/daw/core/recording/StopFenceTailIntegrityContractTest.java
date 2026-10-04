package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.decodedRampValue;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The stop fence (Recording Reliability book §5.2 FINALIZING: deregister →
 * drain → seal): a take that is stopped while the audio thread is still
 * delivering blocks ends at a block boundary — every block that was
 * published is in the sealed segment, in order and whole, and nothing else
 * is. The block whose callback straddles the stop is dropped whole; the
 * flush thread's final sweep waits for a callback in flight; a callback
 * that never leaves cannot keep the take from being sealed, and what that
 * costs is recorded.
 *
 * <p>The race test stops many takes under a live producer thread — unpaced
 * in two cycles of three, and in the third held inside the producer gate
 * until the stop has been requested, so that a contended stop is certain to
 * have been tested and is counted.
 * The other tests hold a callback in flight without a second thread: they
 * run the two halves of the take's real {@link CaptureCallback} —
 * {@code fillBlock}, then {@code publishAndLeave} — from the test thread,
 * with the stop in between. Where a test needs the one interleaving those
 * halves cannot produce — a callback that read the gate open just before it
 * was closed and publishes after — it makes the callback's remaining calls
 * on the ring itself ({@code publish}, {@code exitProducer}). Every wait is
 * bounded by {@link RampCaptureTestSupport#HANG_GUARD}.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class StopFenceTailIntegrityContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path tempDir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    /** The callback a test left inside the producer gate; left again at teardown so no flush thread waits for it. */
    private CaptureCallback inFlight;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void leaveTheGateAndReleaseEveryHold() {
        release.countDown();
        if (inFlight != null) {
            inFlight.publishAndLeave(false);
        }
    }

    private RecordingPipeline newPipeline(AudioEngine engine, Transport transport, Path takeDir, Track track) {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        // Headroom that never warns, whatever the machine's disk holds.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        pipeline.setWarningSink(warnings::add);
        return pipeline;
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + HANG_GUARD.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                fail(what + " did not happen within " + HANG_GUARD);
            }
            LockSupport.parkNanos(100_000L);
        }
    }

    /** Every frame of the track's sealed segments, in manifest order. */
    private static float[] sealedFrames(RecordingPipeline pipeline, Track track) throws IOException {
        List<Path> paths = pipeline.getCaptureFlushService().sealedSegmentPaths().get(track.getId());
        List<float[]> parts = new ArrayList<>();
        int total = 0;
        for (Path path : paths) {
            assertThat(SegmentFile.isStreamingName(path)).as("%s is sealed", path).isFalse();
            float[] frames = SegmentFile.readFrames(path)[0];
            parts.add(frames);
            total += frames.length;
        }
        float[] all = new float[total];
        int at = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }

    /** The sealed take is exactly the first {@code blocks} ramp blocks: no partial block, no hole, nothing after. */
    private static void assertTheTakeIsTheRampPrefix(RecordingPipeline pipeline, Track track, long blocks)
            throws IOException {
        float[] frames = sealedFrames(pipeline, track);
        assertThat(frames.length).as("sealed frames are a whole number of blocks: %d block(s)", blocks)
                .isEqualTo((int) (blocks * BLOCK_FRAMES));
        for (int n = 0; n < frames.length; n++) {
            if (frames[n] != decodedRampValue(n)) {
                fail("frame " + n + " of the sealed take is " + frames[n] + ", the ramp has " + decodedRampValue(n));
            }
        }
    }

    private static void assertSealedByStopWithNoGap(RecordingPipeline pipeline) throws IOException {
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertThat(manifest.gaps()).as("no loss is recorded, because none happened").isEmpty();
        assertThat(manifest.overflowBlocks()).isZero();
    }

    /**
     * Sixty takes, each stopped while a second thread is delivering blocks.
     * Two cycles in three leave the stop to land wherever it lands against
     * an unpaced producer running the engine's own {@code processBlock}.
     * Whether any of those stops finds a callback inside the producer gate
     * is up to the scheduler, so those cycles alone would prove nothing
     * about a contended stop. Every third cycle therefore makes the
     * contention certain, with no timing involved: its producer thread runs
     * the real callback's two halves and, in the block the stop is to
     * straddle, waits between them — inside the gate, slot filled — until
     * the stopping thread has returned from {@code requestStop()}. The test
     * counts the cycles in which a block that had been filled was then not
     * published — a stop that closed the gate on a callback in flight — and
     * requires every driven cycle to be one.
     */
    @Test
    void everyTakeStoppedUnderALiveProducerSealsExactlyTheBlocksItPublished() throws Exception {
        int cycles = 60;
        int maxBlocks = 200;
        int drivenCycles = 0;
        int cyclesWhoseStopClosedTheGateOnACallbackInFlight = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            AudioEngine engine = new AudioEngine(MONO_16);
            Transport transport = new Transport();
            Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
            Path takeDir = Files.createDirectory(tempDir.resolve("take-" + cycle));
            RecordingPipeline pipeline = newPipeline(engine, transport, takeDir, track);
            // More slots than the producer can ever offer: overflow is impossible.
            pipeline.setRingSlots(256);
            startRecording(pipeline);
            CaptureRing ring = pipeline.getCaptureRing();
            CaptureFlushService service = pipeline.getCaptureFlushService();
            CaptureCallback callback = (CaptureCallback) engine.getRecordingCallback();
            // The stop lands at a different point of the take each cycle.
            long stopAfter = cycle % 40;
            boolean driven = cycle % 3 == 0;

            AtomicBoolean producing = new AtomicBoolean(true);
            AtomicReference<Throwable> producerFailure = new AtomicReference<>();
            CountDownLatch insideTheGate = new CountDownLatch(1);
            CountDownLatch stopRequested = new CountDownLatch(1);
            AtomicInteger filledButNotPublished = new AtomicInteger();
            Thread producer = Thread.ofPlatform().name("stop-fence-producer").daemon(true).start(() -> {
                try {
                    float[][] output = new float[1][BLOCK_FRAMES];
                    for (int b = 0; b < maxBlocks && producing.get(); b++) {
                        float[][] block = rampBlock((long) b * BLOCK_FRAMES);
                        if (driven) {
                            // What onAudioCaptured does, with a hold between its halves in one block.
                            boolean filled = callback.fillBlock(block, BLOCK_FRAMES);
                            if (filled && b == stopAfter) {
                                insideTheGate.countDown();
                                if (!stopRequested.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)) {
                                    throw new AssertionError("the stop was not requested within " + HANG_GUARD);
                                }
                            }
                            long publishedBefore = ring.publishedBlocks();
                            callback.publishAndLeave(filled);
                            if (filled && ring.publishedBlocks() == publishedBefore) {
                                filledButNotPublished.incrementAndGet();
                            }
                        } else {
                            engine.processBlock(block, output, BLOCK_FRAMES);
                        }
                        advanceOneBlock(transport);
                    }
                } catch (Throwable thrown) {
                    producerFailure.set(thrown);
                }
            });
            List<AudioClip> clips;
            try {
                if (driven) {
                    assertThat(insideTheGate.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                            .as("cycle %d: the producer is inside the gate with block %d filled", cycle, stopAfter)
                            .isTrue();
                    // The callback is held until this thread releases it: the
                    // flush thread must wait for it, however long that takes.
                    service.setProducerQuiescenceBound(HANG_GUARD.multipliedBy(2));
                    CompletionStage<Void> termination = pipeline.requestStop();
                    stopRequested.countDown();
                    awaitWithinTheGuard(termination, "the capture-flush thread's termination after a stop");
                    clips = pipeline.completeStop();
                } else {
                    awaitCondition("the producer publishing " + stopAfter + " block(s)",
                            () -> ring.publishedBlocks() >= stopAfter || !producer.isAlive());
                    clips = stopRecording(pipeline);
                }
            } finally {
                producing.set(false);
                stopRequested.countDown();
                producer.join(HANG_GUARD.toMillis());
            }
            assertThat(producer.isAlive()).as("cycle %d: the producer ended", cycle).isFalse();
            assertThat(producerFailure.get()).as("cycle %d: the callback never threw", cycle).isNull();
            if (driven) {
                drivenCycles++;
                assertThat(filledButNotPublished.get())
                        .as("cycle %d: the block whose callback was inside the gate when the stop closed it, and "
                                + "only that block, was filled and not published", cycle).isEqualTo(1);
                assertThat(ring.publishedBlocks()).as("cycle %d: the blocks before the straddling one", cycle)
                        .isEqualTo(stopAfter);
                cyclesWhoseStopClosedTheGateOnACallbackInFlight++;
            }

            long published = ring.publishedBlocks();
            assertThat(service.appliedBlocks()).as("cycle %d: every published block was applied", cycle)
                    .isEqualTo(published);
            assertThat(service.discardedBlocks()).as("cycle %d", cycle).isZero();
            assertThat(pipeline.getOverflowCount()).as("cycle %d", cycle).isZero();
            assertTheTakeIsTheRampPrefix(pipeline, track, published);
            assertSealedByStopWithNoGap(pipeline);
            assertThat(clips).as("cycle %d: a clip exactly when something was captured", cycle)
                    .hasSize(published > 0 ? 1 : 0);
            assertThat(warnings).as("cycle %d", cycle).isEmpty();
        }
        assertThat(drivenCycles).as("fixture: one cycle in three drives the contention").isEqualTo(20);
        assertThat(cyclesWhoseStopClosedTheGateOnACallbackInFlight)
                .as("stops that provably closed the gate on a callback in flight: the fence was exercised under "
                        + "contention, not only by stops that happened to find the gate empty")
                .isGreaterThanOrEqualTo(20);
    }

    @Test
    void theCallbackTheEngineRunsIsTheTakesOwnCaptureCallbackAndNothingAroundIt() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = newPipeline(engine, transport, tempDir, track);
        assertThat(engine.getRecordingCallback()).as("fixture: nothing installed before the take").isNull();

        startRecording(pipeline);

        assertThat(engine.getRecordingCallback())
                .as("the object the audio thread calls is the callback the capture sentinel walks")
                .isNotNull()
                .satisfies(installed -> assertThat(installed.getClass()).isSameAs(CaptureCallback.class));
        stopRecording(pipeline);
        assertThat(engine.getRecordingCallback()).as("and the stop removed it").isNull();
    }

    @Test
    void aBlockPublishedAfterTheTimeoutWarningAndBeforeTheFinalSweepIsPartOfTheTakeAsTheWarningSays()
            throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = newPipeline(engine, transport, tempDir, track);
        // The warning sink runs on the flush thread between the wait that
        // gave up and the final sweep: the callback still inside publishes
        // there — it had read the gate open before the stop — and leaves.
        AtomicReference<Runnable> whenTheWaitGivesUp = new AtomicReference<>();
        pipeline.setWarningSink(warning -> {
            warnings.add(warning);
            Runnable publish = warning.contains("had not left the capture ring")
                    ? whenTheWaitGivesUp.getAndSet(null) : null;
            if (publish != null) {
                publish.run();
            }
        });
        TakeWithACallbackInFlight take = takeWithACallbackInFlight(pipeline, engine, transport, track);
        whenTheWaitGivesUp.set(() -> {
            take.ring().publish();
            take.ring().exitProducer();
        });
        inFlight = null; // the sink leaves the gate

        // The wait gives up at its first look.
        take.service().setProducerQuiescenceBound(Duration.ZERO);
        awaitWithinTheGuard(pipeline.requestStop(), "the capture-flush thread's termination");
        pipeline.completeStop();

        assertThat(whenTheWaitGivesUp.get()).as("fixture: the block was published from the warning").isNull();
        assertThat(take.ring().publishedBlocks()).isEqualTo(4);
        assertThat(take.service().appliedBlocks()).as("the final sweep, which runs after the warning, got the block")
                .isEqualTo(4);
        assertTheTakeIsTheRampPrefix(pipeline, track, 4);
        assertSealedByStopWithNoGap(pipeline);
        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning)
                .contains("had not left the capture ring")
                .contains("after the capture-flush thread began waiting")
                .contains("a block it publishes before the final sweep has read the ring is still part of the take")
                .contains("one it publishes after that is not")
                .doesNotContain("from here on"));
    }

    /** A take with three blocks applied and the fourth block's callback inside the producer gate, filled and unpublished. */
    private record TakeWithACallbackInFlight(AudioEngine engine, Transport transport, Track track,
                                             RecordingPipeline pipeline, CaptureRing ring,
                                             CaptureFlushService service, CaptureCallback callback) {
    }

    private TakeWithACallbackInFlight takeWithACallbackInFlight(RecordingPipeline pipeline, AudioEngine engine,
                                                              Transport transport, Track track) {
        startRecording(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 3, 1);
        CaptureCallback callback = (CaptureCallback) engine.getRecordingCallback();
        inFlight = callback;
        assertThat(callback.fillBlock(rampBlock(frame), BLOCK_FRAMES))
                .as("the gate is open and the ring has room: the callback claimed and filled a slot").isTrue();
        return new TakeWithACallbackInFlight(engine, transport, track, pipeline, pipeline.getCaptureRing(),
                pipeline.getCaptureFlushService(), callback);
    }

    private TakeWithACallbackInFlight takeWithACallbackInFlight() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        return takeWithACallbackInFlight(newPipeline(engine, transport, tempDir, track), engine, transport, track);
    }

    /** Requests the stop and returns once the flush thread waits for the callback in flight — or has terminated without waiting. */
    private static CompletionStage<Void> stopAndLetTheFlushThreadReachTheFence(TakeWithACallbackInFlight take) {
        // Twice the guard: a sweep that only waited out its bound cannot pass for one that waited for the callback.
        take.service().setProducerQuiescenceBound(HANG_GUARD.multipliedBy(2));
        CompletionStage<Void> termination = take.pipeline().requestStop();
        awaitCondition("the flush thread reaching the stop fence",
                () -> take.service().isAwaitingProducerExit() || take.service().isTerminated());
        return termination;
    }

    @Test
    void theFinalSweepWaitsForACallbackInFlightAndTheBlockThatStraddlesTheStopIsDroppedWhole() throws Exception {
        TakeWithACallbackInFlight take = takeWithACallbackInFlight();

        CompletionStage<Void> termination = stopAndLetTheFlushThreadReachTheFence(take);
        assertThat(take.service().isTerminated())
                .as("the flush thread has not swept and sealed behind a callback that is still inside").isFalse();
        assertThat(take.service().isAwaitingProducerExit()).isTrue();
        assertThat(take.service().isSealed()).isFalse();

        // The callback's second half, after the stop: the gate is closed.
        take.callback().publishAndLeave(true);
        inFlight = null;
        awaitWithinTheGuard(termination, "the capture-flush thread's termination once the callback has left");
        List<AudioClip> clips = take.pipeline().completeStop();

        assertThat(take.ring().publishedBlocks()).as("the straddling block was not published").isEqualTo(3);
        assertThat(take.service().appliedBlocks()).isEqualTo(3);
        assertThat(take.service().discardedBlocks()).isZero();
        assertTheTakeIsTheRampPrefix(take.pipeline(), take.track(), 3);
        assertSealedByStopWithNoGap(take.pipeline());
        assertThat(clips).hasSize(1);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aBlockWhoseCallbackReadTheGateOpenBeforeTheStopIsInTheSealedTake() throws Exception {
        TakeWithACallbackInFlight take = takeWithACallbackInFlight();

        CompletionStage<Void> termination = stopAndLetTheFlushThreadReachTheFence(take);
        assertThat(take.service().isTerminated())
                .as("the flush thread has not swept and sealed behind a callback that is still inside").isFalse();

        // What a callback does that read the gate open just before the stop
        // closed it: it publishes, wakes the flush thread and leaves.
        take.ring().publish();
        take.service().signal();
        take.ring().exitProducer();
        inFlight = null;
        awaitWithinTheGuard(termination, "the capture-flush thread's termination once the callback has left");
        take.pipeline().completeStop();

        assertThat(take.ring().publishedBlocks()).isEqualTo(4);
        assertThat(take.service().appliedBlocks()).as("the sweep that waited got the block").isEqualTo(4);
        assertTheTakeIsTheRampPrefix(take.pipeline(), take.track(), 4);
        assertSealedByStopWithNoGap(take.pipeline());
        assertThat(warnings).isEmpty();
    }

    @Test
    void aCallbackThatNeverLeavesDoesNotKeepTheTakeFromBeingSealed() throws Exception {
        TakeWithACallbackInFlight take = takeWithACallbackInFlight();

        // The default bound: the wait gives up on its own, well inside the guard.
        awaitWithinTheGuard(take.pipeline().requestStop(),
                "the capture-flush thread's termination with a callback that never leaves");
        take.pipeline().completeStop();

        assertThat(take.ring().publishedBlocks()).isEqualTo(3);
        assertThat(take.service().appliedBlocks()).isEqualTo(3);
        assertTheTakeIsTheRampPrefix(take.pipeline(), take.track(), 3);
        assertSealedByStopWithNoGap(take.pipeline());
        assertThat(warnings).as("the sweep that did not wait any longer says so").singleElement()
                .satisfies(warning -> assertThat(warning).contains("had not left the capture ring")
                        .contains(CaptureFlushService.PRODUCER_QUIESCENCE_BOUND.toMillis() + " ms"));
    }

    @Test
    void aBlockPublishedBehindTheFinalSweepIsRecordedAsAGapWithAWarning() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = newPipeline(engine, transport, tempDir, track);
        // The seal's force(true) holds the flush thread once it is armed; no
        // cadence force can happen, so the next force is the seal's.
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        AtomicBoolean holdNextForce = new AtomicBoolean();
        CountDownLatch held = new CountDownLatch(1);
        journal.beforeForce(() -> {
            if (holdNextForce.compareAndSet(true, false)) {
                held.countDown();
                try {
                    release.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        pipeline.setForceCadence(Duration.ofHours(1));
        TakeWithACallbackInFlight take = takeWithACallbackInFlight(pipeline, engine, transport, track);

        // The wait gives up at its first look, sweeps, and is held sealing.
        take.service().setProducerQuiescenceBound(Duration.ZERO);
        holdNextForce.set(true);
        CompletionStage<Void> termination = pipeline.requestStop();
        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the flush thread swept without the callback and is sealing").isTrue();

        // The callback that was still inside read the gate open before the stop: it publishes now.
        take.ring().publish();
        take.ring().exitProducer();
        inFlight = null;
        release.countDown();
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        pipeline.completeStop();

        assertThat(take.ring().publishedBlocks()).isEqualTo(4);
        assertThat(take.service().appliedBlocks()).as("the block behind the sweep was never read").isEqualTo(3);
        assertTheTakeIsTheRampPrefix(pipeline, track, 3);
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.gaps()).as("the block the take does not hold is on the record")
                .containsExactly(new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 3L * BLOCK_FRAMES, 1));
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).contains("had not left the capture ring");
        assertThat(warnings.get(1)).contains("1 capture block(s) were published after the final sweep")
                .contains("frame " + 3L * BLOCK_FRAMES);
    }

    @Test
    void aStragglerThatStampsAfterTheStopRewoundThePlayheadStartsNoLoopLap() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        transport.setTempo(120.0);
        // A loop far longer than the take: the only way the beat goes backwards is the stop's rewind.
        transport.setLoopRegion(0.0, 64.0);
        transport.setLoopEnabled(true);
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = newPipeline(engine, transport, tempDir, track);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long frame = feedRamp(engine, transport, pipeline, 0, 3, 1);

        // The flush thread is held right after it applied the fourth block,
        // still inside its drain pass: whatever is published meanwhile is
        // applied by that same pass, before the stop's seal.
        CountDownLatch fourthApplied = new CountDownLatch(1);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (sequence == 3) {
                fourthApplied.countDown();
                try {
                    release.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        engine.processBlock(rampBlock(frame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
        assertThat(fourthApplied.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        double beatBeforeTheStop = transport.getPositionInBeats();

        // The audio thread had loaded the callback before the stop removed it.
        AudioEngine.RecordingCallback straggler = engine.getRecordingCallback();
        CompletionStage<Void> termination = pipeline.requestStop();
        assertThat(transport.getPositionInBeats()).as("fixture: the stop rewound the playhead")
                .isLessThan(beatBeforeTheStop);
        assertThat(transport.isLoopEnabled()).isTrue();
        straggler.onAudioCaptured(rampBlock(frame + BLOCK_FRAMES), BLOCK_FRAMES);

        release.countDown();
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        pipeline.completeStop();

        assertThat(pipeline.getCaptureRing().publishedBlocks()).as("the straggler published nothing").isEqualTo(4);
        assertThat(pipeline.getTakeGroups().get(track).size())
                .as("one lap was recorded: the rewound straggler started no second one").isEqualTo(1);
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.segmentsFor(track.getId())).singleElement()
                .satisfies(segment -> assertThat(segment.lane()).isZero());
        assertTheTakeIsTheRampPrefix(pipeline, track, 4);
        assertSealedByStopWithNoGap(pipeline);
    }

    @Test
    void theGateIsClosedBeforeTheStopMovesTheTransport() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = newPipeline(engine, transport, tempDir, track);
        startRecording(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 3, 1);
        CaptureRing ring = pipeline.getCaptureRing();
        AudioEngine.RecordingCallback callback = engine.getRecordingCallback();

        // The transport tells its listeners that it stopped from inside
        // stop(), on the stopping thread, after it has moved the playhead: a
        // callback that runs at that very moment must already find the gate closed.
        List<Boolean> closedWhenTheTransportStopped = new CopyOnWriteArrayList<>();
        Runnable removeListener = transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE
                    && transport.getState() == com.benesquivelmusic.daw.core.transport.TransportState.STOPPED) {
                closedWhenTheTransportStopped.add(ring.isProducerClosed());
                callback.onAudioCaptured(rampBlock(frame), BLOCK_FRAMES);
            }
        });
        try {
            stopRecording(pipeline);
        } finally {
            removeListener.run();
        }

        assertThat(closedWhenTheTransportStopped).as("the stop reached the transport, the gate closed before it")
                .containsExactly(true);
        assertThat(ring.publishedBlocks()).as("the callback that ran behind the transport's stop published nothing")
                .isEqualTo(3);
        assertTheTakeIsTheRampPrefix(pipeline, track, 3);
        assertSealedByStopWithNoGap(pipeline);
    }

    @Test
    void aCallbackLeftOverFromAnEarlierTakeNeverReachesTheNextTakesRing() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = newPipeline(engine, transport, tempDir, track);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        AudioEngine.RecordingCallback earlier = engine.getRecordingCallback();
        CaptureRing earlierRing = pipeline.getCaptureRing();
        stopRecording(pipeline);

        // The same pipeline prepares its next take: a new ring, its gate
        // open. Whether that take's files could be created next to the
        // first take's does not matter here; the readiness is only waited out.
        PipelineLifecycleTestSupport.failureWithinTheGuard(pipeline.prepare(), "the next take's readiness");
        CaptureRing nextRing = pipeline.getCaptureRing();
        assertThat(nextRing).isNotSameAs(earlierRing);
        assertThat(nextRing.isProducerClosed()).isFalse();

        earlier.onAudioCaptured(rampBlock(0), BLOCK_FRAMES);
        CaptureCallback earlierCallback = (CaptureCallback) earlier;
        assertThat(earlierCallback.fillBlock(rampBlock(0), BLOCK_FRAMES))
                .as("a callback that enters a closed gate claims and fills nothing").isFalse();
        earlierCallback.publishAndLeave(false);

        assertThat(nextRing.publishedBlocks()).as("the earlier take's callback carries its own ring").isZero();
        assertThat(earlierRing.publishedBlocks()).as("whose gate is closed for good").isEqualTo(2);
        assertThat(earlierRing.isProducerClosed()).isTrue();
        awaitWithinTheGuard(pipeline.cancelStart(), "the termination of the cancelled next take");
        assertThat(nextRing.isProducerClosed()).as("a cancelled start closes its ring's gate too").isTrue();
    }
}
