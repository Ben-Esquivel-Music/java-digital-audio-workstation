package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

/**
 * The stop in two halves (story 323 review, Copilot 5365941737 HIGH and PR
 * #978 review 5391920205 F1): {@link RecordingPipeline#requestStop()} runs the
 * one-shot side effects, asks the {@code capture-flush} thread to seal the
 * take and returns that thread's termination signal at once — it never waits
 * for the seal, however long the thread takes on a slow or stuck filesystem —
 * and leaves the pipeline
 * {@linkplain RecordingPipeline#isFinalizationPending() finalization pending}.
 * {@link RecordingPipeline#completeStop()}, made once that signal has
 * completed, builds the clips exactly as an uninterrupted stop would have,
 * without repeating the one-shot side effects the user may have moved past;
 * made before that, it is refused and reads nothing the thread writes.
 * {@code prepare()} and the pre-start setters are refused meanwhile. The
 * signal completes on every path that ends the thread, and only then; a stop
 * made once the thread has terminated does not wait for it to exit, and one
 * requested on the flush thread itself never waits for that thread.
 *
 * <p>The thread is held with a latch — inside the seal of the tail segment
 * (the {@code force(true)} that opens it, through the channel opener seam),
 * inside the final sweep (the block observer seam), or, once it has
 * terminated, in a dependent of the termination signal. Every wait is
 * bounded: a request that must return at once by
 * {@link RampCaptureTestSupport#HANG_GUARD}, while the hold it must not wait
 * for lasts twice the guard; the termination signals by the guard as well;
 * and, inside {@code feedRamp}, {@code awaitFlushed} and
 * {@code setDrainPaused}, by {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class StopFinalizationPendingContractTest {

    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private RecordingPipeline pipeline;
    private final ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    /** Armed by a test: the next delegated {@code force} holds the forcing thread until {@link #release}. */
    private final AtomicBoolean holdNextForce = new AtomicBoolean();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        transport.setTempo(120.0);
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        journal.beforeForce(() -> {
            if (holdNextForce.compareAndSet(true, false)) {
                holdBeyondTheGuard();
            }
        });
    }

    /**
     * A held thread is never left behind: released, then — whether the test
     * passed or failed before it stopped or cancelled the take — the take is
     * ended and its flush thread joined within the guard; one still alive
     * fails the test.
     */
    @AfterEach
    void releaseAndEndTheTake() throws InterruptedException {
        release.countDown();
        PipelineLifecycleTestSupport.endTheTakeAndAssertItsFlushThreadEnded(pipeline);
    }

    /** Runs on the flush thread: signals that it is held, then waits for the release (bounded). */
    private void holdHere() {
        held.countDown();
        try {
            release.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@link #holdHere()} with twice the guard as its bound, so a call that
     * waited for the held thread would still be waiting when
     * {@link RampCaptureTestSupport#HANG_GUARD} runs out, rather than slip
     * through as the hold's own bound ran out.
     */
    private void holdBeyondTheGuard() {
        held.countDown();
        try {
            release.await(2 * HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RecordingPipeline newPipeline(Track... tracks) {
        RecordingPipeline created = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(tracks));
        // Headroom that never warns, whatever the machine's disk holds.
        created.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        created.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        // No cadence force can happen, so the next force after arming the
        // hold is the one the test means.
        created.setForceCadence(Duration.ofHours(1));
        created.setWarningSink(warnings::add);
        return created;
    }

    private void feedOne(long frame) {
        engine.processBlock(rampBlock(frame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
    }

    /** Runs {@code requestStop()} on its own thread, requires it to return within the guard, and returns its signal. */
    private CompletionStage<Void> requestStopWithinTheGuard() throws InterruptedException {
        AtomicReference<CompletionStage<Void>> signal = new AtomicReference<>();
        Throwable thrown = outcomeWithinTheGuard("stop request", () -> signal.set(pipeline.requestStop()));
        assertThat(thrown).as("the stop request returns, and throws nothing").isNull();
        return signal.get();
    }

    /** Runs {@code completeStop()} on its own thread, requires it to return within the guard, and returns its clips. */
    private List<AudioClip> completeStopWithinTheGuard() throws InterruptedException {
        List<List<AudioClip>> result = new CopyOnWriteArrayList<>();
        Throwable thrown = outcomeWithinTheGuard("stop completion", () -> result.add(pipeline.completeStop()));
        assertThat(thrown).as("the stop completion builds the take").isNull();
        return result.getFirst();
    }

    /** Every path under the take directory with its size — what "untouched" means here. */
    private Map<Path, Long> snapshot() throws IOException {
        Map<Path, Long> sizes = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(takeDir)) {
            for (Path path : paths.sorted().toList()) {
                sizes.put(path, Files.isRegularFile(path) ? Files.size(path) : -1L);
            }
        }
        return sizes;
    }

    private List<String> manifestOrder() throws IOException {
        List<String> order = new ArrayList<>();
        for (TakeManifest.SegmentEntry entry : TakeManifest.read(pipeline.getTakeManifestPath()).segmentsFor(track.getId())) {
            order.add(entry.resolve(takeDir).toAbsolutePath().toString());
        }
        return order;
    }

    @Test
    void aStopRequestWhileTheSealIsHeldReturnsAtOnceAndTheCompletionReferencesEverySealedSegment() throws Exception {
        transport.setPositionInBeats(8.0);
        pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 4 * BLOCK_BYTES); // 4 blocks per segment
        startRecording(pipeline);
        double anchor = transport.getPositionInBeats();
        feedRamp(engine, transport, pipeline, 0, 10, 4); // 4 + 4 + 2 → 3 segments
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path trackDir = takeDir.resolve(track.getId());
        assertThat(trackDir.resolve("segment-002.wav.part"))
                .as("fixture: the tail segment is still streaming").exists();
        holdNextForce.set(true); // the force(true) that opens the tail segment's seal, held beyond the guard

        CompletionStage<Void> written = requestStopWithinTheGuard();

        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the flush thread is held inside the tail segment's seal").isTrue();
        // Compared by reference: AssertJ's CompletionStage assertions convert
        // the stage, and a minimal stage converts to a fresh copy each time.
        assertThat(written == service.termination()).as("the request hands out the thread's termination").isTrue();
        assertThat(written.toCompletableFuture().isDone()).isFalse();
        assertThat(service.isTerminated()).isFalse();
        assertThat(trackDir.resolve("segment-002.wav.part")).as("fixture: the tail is not sealed yet").exists();
        assertThat(service.sealedSegmentPaths().get(track.getId()))
                .as("fixture: what a build now would read — a lane's segments are listed once its seal"
                        + " has finished, so not even the two rotated ones are yet")
                .isEmpty();
        assertThat(pipeline.isFinalizationPending()).isTrue();
        assertThat(pipeline.isActive()).isFalse();
        assertThat(engine.getRecordingCallback()).as("the one-shot stop ran: callback removed").isNull();
        assertThat(track.isRecording()).as("the one-shot stop ran: flag cleared").isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).as("the one-shot stop ran: back at the anchor").isEqualTo(anchor);
        assertThat(warnings).as("a stop that waits for nothing has nothing to warn about").isEmpty();

        // A completion made now builds nothing from a take still being written.
        assertThatThrownBy(pipeline::completeStop)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still being written");
        assertThat(pipeline.isFinalizationPending()).isTrue();
        assertThat(track.getClips()).as("no clip is built from a take still being written").isEmpty();
        assertThat(pipeline.getRecordedClips()).isEmpty();

        // Nothing may restart or reconfigure the pipeline over the pending take.
        Map<Path, Long> before = snapshot();
        assertThatThrownBy(pipeline::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still finalising");
        assertThatThrownBy(() -> pipeline.setSegmentLimits(Duration.ofMinutes(1), BLOCK_BYTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still being finalised");
        assertThat(snapshot()).as("the refused prepare touched none of the pending take's files").isEqualTo(before);
        assertThat(pipeline.getCaptureFlushService()).as("nor dropped its flush service").isSameAs(service);
        assertThat(engine.getRecordingCallback()).as("nor wired a callback").isNull();
        assertThat(track.isRecording()).as("nor flagged a track").isFalse();

        // The user moves on: another take owns the callback slot and the
        // flag, the tempo changes, and playback runs from a new anchor.
        AudioEngine.RecordingCallback otherTake = (buffer, frames) -> { };
        engine.setRecordingCallback(otherTake);
        track.setRecording(true);
        transport.setTempo(60.0);
        transport.play();
        transport.advancePosition(3.0);
        double playhead = transport.getPositionInBeats();

        CompletionStage<Void> again = requestStopWithinTheGuard();

        assertThat(again == written).as("the same termination signal").isTrue();
        assertNothingRepeated(otherTake, playhead);
        assertThat(track.getClips()).isEmpty();
        assertThat(pipeline.getRecordedClips()).isEmpty();

        release.countDown();
        awaitWithinTheGuard(written, "the capture-flush thread's termination");
        assertThat(service.isTerminated()).isTrue();
        assertThat(pipeline.isFinalizationPending())
                .as("the thread is done, the stop is not: no clip until the completion builds it").isTrue();
        assertThat(track.getClips()).isEmpty();

        List<AudioClip> clips = completeStopWithinTheGuard();

        assertNothingRepeated(otherTake, playhead);
        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).hasSize(1);
        AudioClip clip = clips.getFirst();
        List<String> expected = List.of(
                trackDir.resolve("segment-000.wav").toAbsolutePath().toString(),
                trackDir.resolve("segment-001.wav").toAbsolutePath().toString(),
                trackDir.resolve("segment-002.wav").toAbsolutePath().toString());
        assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(expected);
        assertThat(manifestOrder()).as("manifest order IS the clip's order").isEqualTo(expected);
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        long framesOnDisk = 0;
        for (String path : clip.getSourceSegmentPaths()) {
            SegmentFile.Description description = SegmentFile.describe(Path.of(path));
            assertThat(description.sealed()).isTrue();
            framesOnDisk += description.frameCount();
        }
        assertThat(framesOnDisk).isEqualTo(10L * BLOCK_FRAMES);
        assertThat(clip.getAudioData()[0]).hasSize(10 * BLOCK_FRAMES);
        assertThat(clip.getStartBeat()).isEqualTo(anchor);
        assertThat(clip.getDurationBeats())
                .as("built at the tempo read when the stop began (120 bpm), not today's 60")
                .isCloseTo(10.0 * BLOCK_FRAMES / SAMPLE_RATE * (120.0 / 60.0), within(1e-12));
        assertThat(track.getClips()).containsExactly(clip);
        assertThat(pipeline.getRecordedClips()).containsExactly(entry(track, clip));

        assertThat(pipeline.completeStop()).as("the clips were returned once").isEmpty();
        assertThat(track.getClips()).hasSize(1);
        engine.setRecordingCallback(null);
    }

    private void assertNothingRepeated(AudioEngine.RecordingCallback otherTake, double playhead) {
        assertThat(engine.getRecordingCallback())
                .as("the callback slot is not cleared a second time").isSameAs(otherTake);
        assertThat(track.isRecording()).as("the recording flag is not cleared a second time").isTrue();
        assertThat(transport.getState())
                .as("the transport is not stopped a second time").isEqualTo(TransportState.PLAYING);
        assertThat(transport.getPositionInBeats())
                .as("the playhead is not yanked back to an anchor").isEqualTo(playhead);
    }

    @Test
    void aStopHeldInTheFinalSweepHasSealedNothingYetAndStillCountsAsStarted() throws Exception {
        pipeline = newPipeline(track);
        startRecording(pipeline);
        feedOne(0);
        feedOne(BLOCK_FRAMES);
        pipeline.awaitFlushed();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.setDrainPaused(true);
        feedOne(2L * BLOCK_FRAMES); // queued: only the final sweep will apply it
        service.setBlockObserver((sequence, startFrame, numFrames) -> holdBeyondTheGuard());

        CompletionStage<Void> written = requestStopWithinTheGuard();

        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the flush thread is held in the final sweep").isTrue();
        assertThat(service.isSealed()).as("fixture: nothing is sealed yet").isFalse();
        assertThat(pipeline.isFinalizationPending()).isTrue();
        assertThatCode(pipeline::awaitFlushed)
                .as("a pending take was started: the fence is not refused as 'never started'")
                .doesNotThrowAnyException();
        assertThat(track.getClips()).isEmpty();
        assertThat(pipeline.getRecordedClips()).isEmpty();

        release.countDown();
        awaitWithinTheGuard(written, "the capture-flush thread's termination");
        List<AudioClip> clips = completeStopWithinTheGuard();

        assertThat(clips).singleElement().satisfies(clip -> {
            assertThat(clip.getAudioData()[0]).as("the block the final sweep applied is in the clip").hasSize(3 * BLOCK_FRAMES);
            assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(manifestOrder());
        });
        assertThat(SegmentFile.describe(takeDir.resolve(track.getId()).resolve("segment-000.wav")).frameCount())
                .isEqualTo(3L * BLOCK_FRAMES);
    }

    @Test
    void aLoopRecordTakeCompletedAfterTerminationAttachesTheWholeStack() throws Exception {
        int blocksPerLoop = 4;
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocksPerLoop * (double) BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES); // 2 segments per lap
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 3 * blocksPerLoop + 1, 1); // 3 laps + one block of the 4th
        int stackedBeforeStop = pipeline.getTakeGroups().get(track).size();
        holdNextForce.set(true); // the seal of the lap in flight

        CompletionStage<Void> written = requestStopWithinTheGuard();

        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(track.getTakeGroups()).as("no take group is attached while the take is being written").isEmpty();
        assertThat(track.getClips()).isEmpty();
        assertThat(pipeline.getRecordedClips()).isEmpty();

        release.countDown();
        awaitWithinTheGuard(written, "the capture-flush thread's termination");
        List<AudioClip> clips = completeStopWithinTheGuard();

        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group.size()).as("the lap in flight became the last take").isEqualTo(stackedBeforeStop + 1);
        assertThat(track.getTakeGroups()).containsExactly(entry(group.id(), group));
        assertThat(clips).containsExactly(group.activeClip());
        assertThat(track.getClips()).containsExactly(group.activeClip());
        List<String> everyTakesSegments = new ArrayList<>();
        for (Take take : group.takes()) {
            everyTakesSegments.addAll(take.clip().getSourceSegmentPaths());
        }
        assertThat(everyTakesSegments)
                .as("the stack references every sealed segment of the take, in manifest order")
                .isEqualTo(manifestOrder());
    }

    @Test
    void anEarlySealLeavesTheThreadRunningAndTheSignalOpenUntilTheStop() throws Exception {
        pipeline = newPipeline(track);
        startRecording(pipeline);
        feedOne(0);
        pipeline.awaitFlushed();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        journal.failNextWrites(1);
        feedOne(BLOCK_FRAMES);
        pipeline.awaitFlushed(); // the failed append and its early seal are part of the fence

        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(service.isRunning()).as("after an early seal the thread keeps draining").isTrue();
        assertThat(service.isTerminated()).isFalse();
        assertThat(service.termination().toCompletableFuture().isDone())
                .as("sealed is not terminated: the signal waits for the thread").isFalse();

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(service.isTerminated()).isTrue();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(BLOCK_FRAMES));
    }

    @Test
    void aThrowableThatEndsTheLoopCompletesTheSignalWithoutAStop() throws Exception {
        pipeline = newPipeline(track);
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            throw new InjectedFault("injected drain-loop fault");
        });
        feedOne(0);

        awaitWithinTheGuard(service.termination(), "the capture-flush thread's termination");

        assertThat(service.isTerminated()).isTrue();
        assertThat(pipeline.isActive()).as("fixture: nobody has stopped the pipeline").isTrue();
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).contains("injected drain-loop fault"));

        CompletionStage<Void> written = requestStopWithinTheGuard();
        assertThat(written.toCompletableFuture().isDone()).as("the thread had already terminated").isTrue();
        List<AudioClip> clips = completeStopWithinTheGuard();

        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(manifestOrder()));
    }

    /**
     * Once the flush thread has terminated, a stop reads the take at once:
     * neither half waits for the thread to exit, which may still be running
     * a non-async dependent of the termination signal (here one that holds
     * it beyond the guard) or unwinding.
     */
    @Test
    void aStopAfterTheThreadHasTerminatedDoesNotWaitForTheThreadToExit() throws Exception {
        pipeline = newPipeline(track);
        startRecording(pipeline);
        feedOne(0);
        pipeline.awaitFlushed();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        // Non-async: runs on the flush thread once it has terminated, and holds it there.
        service.termination().thenRun(this::holdBeyondTheGuard);
        // The loop ends without a stop: a throwable escapes it (the block
        // observer seam). Installed while the loop is held between passes, so
        // the pass that applied block 0 has ended and the observer sees block 1 first.
        service.setDrainPaused(true);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            throw new IllegalStateException("injected drain-loop fault");
        });
        service.setDrainPaused(false);
        feedOne(BLOCK_FRAMES);
        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the terminated thread is held in a dependent of its termination").isTrue();
        assertThat(service.isTerminated()).isTrue();

        requestStopWithinTheGuard();
        List<AudioClip> clips = completeStopWithinTheGuard();

        assertThat(service.thread().isAlive()).as("the stop completed while the thread was still held").isTrue();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(2 * BLOCK_FRAMES));
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
    }

    /**
     * A stop requested on the flush thread itself before it has terminated —
     * from the warning sink, say; here from the block observer — returns at
     * once, because the request waits for nothing; a completion made there
     * and then is refused, because the thread has not terminated; and the
     * completion made once it has builds the take.
     */
    @Test
    void aStopRequestedOnTheFlushThreadReturnsAtOnceAndItsCompletionWaitsForTheTermination() throws Exception {
        pipeline = newPipeline(track);
        startRecording(pipeline);
        feedOne(0);
        pipeline.awaitFlushed();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        CompletableFuture<CompletionStage<Void>> requestedThere = new CompletableFuture<>();
        CompletableFuture<Throwable> completionThere = new CompletableFuture<>();
        AtomicBoolean first = new AtomicBoolean(true);
        // Installed while the loop is held between passes, so the pass that
        // applied block 0 has ended and the observer sees block 1 first.
        service.setDrainPaused(true);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            if (first.compareAndSet(true, false)) {
                try {
                    requestedThere.complete(pipeline.requestStop());
                } catch (Throwable thrown) {
                    requestedThere.completeExceptionally(thrown);
                }
                try {
                    pipeline.completeStop();
                    completionThere.complete(null);
                } catch (Throwable thrown) {
                    completionThere.complete(thrown);
                }
            }
        });
        service.setDrainPaused(false);
        feedOne(BLOCK_FRAMES);

        CompletionStage<Void> written = requestedThere.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(completionThere.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the completion is refused on the thread that is still writing the take")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still being written");
        awaitWithinTheGuard(written, "the capture-flush thread's termination");
        List<AudioClip> clips = completeStopWithinTheGuard();

        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.STOP);
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(2 * BLOCK_FRAMES));
    }

    @Test
    void aLanesErrorRethrownBySealCompletesTheSignalAndTheCompletionBuildsTheClips() throws Exception {
        pipeline = newPipeline(track);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            session.addListener(new StopFaultListener(fault));
            return session;
        });
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        feedOne(0);
        pipeline.awaitFlushed();

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(service.lastFailure()).containsSame(fault);
        assertThat(service.isTerminated()).isTrue();
        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getSourceSegmentPaths()).containsExactlyElementsOf(manifestOrder()));
    }

    private static final class InjectedFault extends Error {
        private static final long serialVersionUID = 1L;

        InjectedFault(String message) {
            super(message);
        }
    }

    /** A listener whose stop notification dies with an {@link Error}; every other callback does nothing. */
    private static final class StopFaultListener implements RecordingListener {
        private final Error fault;

        StopFaultListener(Error fault) {
            this.fault = fault;
        }

        @Override
        public void onRecordingStarted() {
        }

        @Override
        public void onRecordingPaused() {
        }

        @Override
        public void onRecordingResumed() {
        }

        @Override
        public void onRecordingStopped() {
            throw fault;
        }

        @Override
        public void onNewSegmentCreated(int segmentIndex) {
        }
    }
}
