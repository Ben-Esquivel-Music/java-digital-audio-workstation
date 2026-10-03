package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.failureWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The readiness gate of a record start (PR #978 review 5391920205 F2;
 * Recording Reliability book §3.2, §5.1, §5.2): {@link RecordingPipeline#prepare()}
 * touches no storage and waits for nothing — the {@code capture-flush}
 * thread creates the take's files as its first act — and capture begins
 * ({@link RecordingPipeline#beginCapture()}) only once the take's readiness
 * has completed normally, because by default the capture ring holds only
 * enough blocks to cover 250 ms of audio, rounded up to a power-of-two slot
 * count. A start stays all-or-nothing (story 323 D11): a readiness that
 * fails does so only once the flush thread has rolled back the take's files
 * — deleting the segment and manifest files the take created, and each
 * track directory that left empty (best-effort: what an I/O error keeps
 * from being deleted is left and the error logged) — and has terminated; a
 * cancel ({@link RecordingPipeline#cancelStart()}) and the rollback of a
 * failed {@code beginCapture()} ask that thread to discard the take and
 * return at once, and whichever of a cancel and a readiness comes first,
 * capture never begins and, when no delete fails and the flush thread had
 * not already sealed the take early and ended (a throwable that escaped its
 * drain loop), no file is left. After
 * either, a restart of the same pipeline is refused, touching nothing, until
 * that thread has terminated, so this pipeline never has two flush threads
 * working in its take directory (book §2.3). A seek while the take is being
 * prepared over a stopped transport does not move recording away from the
 * take's anchor, and a take prepared over a rolling transport begins capture
 * without moving the playhead.
 *
 * <p>The flush thread is held inside its first segment open through the
 * channel opener seam, or inside a pass through the block observer seam, for
 * twice {@link RampCaptureTestSupport#HANG_GUARD}; every call that must not
 * wait for it runs under {@code outcomeWithinTheGuard}, so a call that waited
 * would overrun the guard instead of slipping through as the hold ran out.
 * Every other wait is bounded by the guard, or, inside {@code awaitFlushed},
 * by {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}.</p>
 */
class RecordStartReadinessGateContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private RecordingPipeline pipeline;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    /** The first segment open the blocking opener saw; counted down on the opening thread. */
    private final CountDownLatch openEntered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<String> openedOn = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        transport.setTempo(120.0);
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
    }

    /** A held thread is never left behind: released, then given the guard to finish before the directory goes. */
    @AfterEach
    void releaseAndJoinTheFlushThread() throws InterruptedException {
        release.countDown();
        CaptureFlushService service = pipeline == null ? null : pipeline.getCaptureFlushService();
        if (service != null) {
            service.thread().join(HANG_GUARD.toMillis());
        }
    }

    private RecordingPipeline newPipeline(Track... tracks) {
        RecordingPipeline created = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(tracks));
        // Headroom that never warns, whatever the machine's disk holds.
        created.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        created.setWarningSink(warnings::add);
        return created;
    }

    /**
     * A channel opener whose first open records its thread and holds it
     * until {@link #release} — for at most twice the guard — before the file
     * is created; every later open goes straight through.
     */
    private SegmentWriter.ChannelOpener openerHeldAtTheFirstOpen() {
        AtomicBoolean first = new AtomicBoolean(true);
        return path -> {
            if (first.compareAndSet(true, false)) {
                openedOn.set(Thread.currentThread().getName());
                openEntered.countDown();
                try {
                    release.await(2 * HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while held");
                }
            }
            return SegmentWriter.CREATE_NEW_CHANNEL.open(path);
        };
    }

    /** Runs {@code prepare()} under the guard and returns its readiness. */
    private CompletionStage<Void> prepareWithinTheGuard() throws InterruptedException {
        AtomicReference<CompletionStage<Void>> readiness = new AtomicReference<>();
        assertThat(outcomeWithinTheGuard("prepare", () -> readiness.set(pipeline.prepare())))
                .as("prepare returns at once, and throws nothing").isNull();
        return readiness.get();
    }

    /** Runs {@code cancelStart()} under the guard and returns the termination it hands out. */
    private CompletionStage<Void> cancelWithinTheGuard() throws InterruptedException {
        AtomicReference<CompletionStage<Void>> termination = new AtomicReference<>();
        assertThat(outcomeWithinTheGuard("cancel", () -> termination.set(pipeline.cancelStart())))
                .as("the cancel returns at once, and throws nothing").isNull();
        return termination.get();
    }

    private void awaitTheHeldOpen() throws InterruptedException {
        assertThat(openEntered.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the first segment open is held").isTrue();
    }

    /**
     * Holds the draining flush thread of {@code service} inside a pass until
     * {@link #release} — for at most twice the guard: one block is published
     * straight to the ring, and the block observer holds the thread once it
     * has applied it. Returns once the thread is held.
     */
    private void holdTheFlushThreadInAPass(CaptureFlushService service) throws InterruptedException {
        CountDownLatch inPass = new CountDownLatch(1);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            inPass.countDown();
            try {
                release.await(2 * HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CaptureRing.Slot slot = pipeline.getCaptureRing().claim();
        slot.setNumFrames(BLOCK_FRAMES);
        slot.copySource(0, rampBlock(0), 1, BLOCK_FRAMES);
        pipeline.getCaptureRing().publish();
        service.signal();
        assertThat(inPass.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the flush thread is held in a pass").isTrue();
    }

    /**
     * A {@code prepare()} refused because the previous take's flush thread
     * has not terminated: it returns at once with an
     * {@link IllegalStateException} that points at {@code termination()},
     * and touches nothing — the pipeline is not preparing, its flush service,
     * ring and (absent) captures are still the previous take's, no track is
     * flagged, the take directory holds what it held, and capture never began.
     */
    private void assertTheRestartIsRefusedTouchingNothing(CaptureFlushService previous) throws Exception {
        CaptureRing previousRing = pipeline.getCaptureRing();
        List<Path> entriesBefore = takeDirectoryEntries();

        Throwable refused = outcomeWithinTheGuard("restart", pipeline::prepare);

        assertThat(refused).as("the restart is refused at once while the previous take's flush thread runs")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("termination()");
        assertThat(pipeline.isPreparing()).as("the refused restart prepared nothing").isFalse();
        assertThat(pipeline.getCaptureFlushService()).as("no second flush service was created").isSameAs(previous);
        assertThat(pipeline.getCaptureRing()).as("the ring was not replaced").isSameAs(previousRing);
        assertThat(pipeline.getSession(track)).as("no capture was built").isNull();
        assertThat(track.isRecording()).as("no track was flagged").isFalse();
        assertThat(takeDirectoryEntries()).as("the take directory holds what it held").isEqualTo(entriesBefore);
        assertThat(previous.isTerminated()).as("the previous take's thread is still held").isFalse();
        assertCaptureNeverBegan();
    }

    private Path firstSegmentPart(Track of) {
        return takeDir.resolve(of.getId()).resolve("segment-000.wav.part");
    }

    private List<Path> takeDirectoryEntries() throws IOException {
        try (Stream<Path> entries = Files.list(takeDir)) {
            return entries.toList();
        }
    }

    private void feedOne(long frame) {
        engine.processBlock(rampBlock(frame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
    }

    private void assertCaptureNeverBegan() {
        assertThat(pipeline.isActive()).isFalse();
        assertThat(engine.getRecordingCallback()).as("no recording callback was installed").isNull();
        assertThat(engine.isRunning()).as("the engine was never started").isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    // (a)
    @Test
    void prepareReturnsAtOnceWhileTheFirstOpenIsHeldAndReadinessCompletesOnceTheFilesExist() throws Exception {
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        Path part = firstSegmentPart(track);

        CompletionStage<Void> readiness = prepareWithinTheGuard();

        awaitTheHeldOpen();
        assertThat(openedOn.get()).as("the take's files are created on the flush thread")
                .isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(part).as("no segment exists while its open is held").doesNotExist();
        assertThat(pipeline.getTakeManifestPath()).doesNotExist();
        assertThat(readiness.toCompletableFuture().isDone()).isFalse();
        assertThat(pipeline.isPreparing()).isTrue();
        assertThat(track.isRecording()).as("prepare flagged the armed track").isTrue();
        assertCaptureNeverBegan();
        // Waited for itself, not through the readiness: a dependent of the
        // same stage may run after another dependent has woken this thread.
        CompletionStage<Boolean> filesExistedAtReadiness = readiness.thenApply(
                ignored -> Files.exists(part) && Files.exists(pipeline.getTakeManifestPath()));

        release.countDown();

        assertThat(awaitWithinTheGuard(filesExistedAtReadiness, "the readiness dependent"))
                .as("readiness completed once the segment and the manifest existed").isTrue();
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealStatus())
                .isEqualTo(TakeManifest.SealStatus.STREAMING);
        pipeline.beginCapture();
        assertThat(pipeline.isActive()).isTrue();
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        feedOne(0);
        pipeline.awaitFlushed();
        assertThat(stopRecording(pipeline)).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(BLOCK_FRAMES));
    }

    // (b)
    @Test
    void aFailedInitialisationFailsReadinessOnlyOnceEveryFileItCreatedIsGoneAndCancelLeavesTheTransportStopped()
            throws Exception {
        Track second = RampCaptureTestSupport.armedMonoTrack("Vocal 2");
        pipeline = newPipeline(track, second);
        Path secondDir = takeDir.resolve(second.getId());
        IOException refusal = new IOException("injected open refusal (test seam)");
        pipeline.setChannelOpener(path -> {
            if (path.startsWith(secondDir)) {
                throw refusal;
            }
            return SegmentWriter.CREATE_NEW_CHANNEL.open(path);
        });

        CompletionStage<Void> readiness = prepareWithinTheGuard();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path firstPart = firstSegmentPart(track);
        // Waited for itself, not through the readiness: a dependent of the
        // same stage may run after another dependent has woken this thread.
        CompletionStage<String> atTheFailure = readiness.handle((ignored, failure) -> "terminated="
                + service.isTerminated() + ", first track's segment exists=" + Files.exists(firstPart));

        Throwable notReady = failureWithinTheGuard(readiness, "the take's readiness");

        assertThat(notReady).isInstanceOf(UncheckedIOException.class);
        assertThat(rootCause(notReady)).isSameAs(refusal);
        assertThat(awaitWithinTheGuard(atTheFailure, "the readiness dependent"))
                .as("what a dependent saw when the readiness failed")
                .isEqualTo("terminated=true, first track's segment exists=false");
        assertThat(service.termination().toCompletableFuture().isDone()).isTrue();
        assertThat(takeDirectoryEntries()).as("every file and directory the take created is gone").isEmpty();
        assertThat(pipeline.isPreparing()).as("the pipeline learns of the failure from its caller").isTrue();
        assertThatThrownBy(pipeline::beginCapture)
                .as("a take that is not ready can only be cancelled")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");

        CompletionStage<Void> termination = cancelWithinTheGuard();

        assertThat(termination.toCompletableFuture().isDone()).isTrue();
        assertThat(pipeline.isPreparing()).isFalse();
        assertThat(track.isRecording()).isFalse();
        assertThat(second.isRecording()).isFalse();
        assertThat(pipeline.getSession(track)).isNull();
        assertCaptureNeverBegan();
        assertThat(service.earlySeal().toCompletableFuture().isDone()).isFalse();
    }

    // (c)
    @Test
    void aCancelWhileTheInitialisationIsHeldReturnsAtOnceAndNoFileRemainsOnceTheThreadIsDone() throws Exception {
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitTheHeldOpen();

        CompletionStage<Void> termination = cancelWithinTheGuard();

        assertThat(termination.toCompletableFuture().isDone()).as("the thread still holds the open").isFalse();
        assertThat(readiness.toCompletableFuture().isDone()).isFalse();
        assertThat(pipeline.isPreparing()).isFalse();
        assertThat(track.isRecording()).isFalse();
        assertCaptureNeverBegan();

        release.countDown(); // the open goes through, the initialisation finishes — and finds the abort
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        Throwable notReady = failureWithinTheGuard(readiness, "the take's readiness");

        assertThat(notReady).as("a cancelled take never becomes ready").isInstanceOf(CancellationException.class);
        assertThat(notReady.getMessage())
                .as("the abort, seen after the take's files were created, says what the rollback did with them")
                .contains("its rollback deletes the segment and manifest files it had created",
                        "leaves the take directory in place");
        assertThat(takeDir).as("the take directory is left in place, as the abort says").isDirectory();
        assertThat(takeDirectoryEntries()).as("the flush thread deleted everything it had created").isEmpty();
        assertThatThrownBy(pipeline::beginCapture).isInstanceOf(IllegalStateException.class);
        assertCaptureNeverBegan();
    }

    // (d)
    @Test
    void aFailedBeginCaptureRollsBackWithoutWaitingAndTheFlushThreadDeletesTheFiles() throws Exception {
        pipeline = newPipeline(track);
        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path part = firstSegmentPart(track);
        assertThat(part).as("fixture: the take is ready").exists();
        // Hold the flush thread in a pass for twice the guard: a rollback that
        // waited for the thread would overrun the guard below.
        holdTheFlushThreadInAPass(service);
        engine.shutdown(); // the engine start inside beginCapture() throws

        Throwable thrown = outcomeWithinTheGuard("begin capture", pipeline::beginCapture);

        assertThat(thrown).as("the engine's failure is rethrown, and the rollback did not wait")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shut down");
        assertThat(engine.getRecordingCallback()).as("the rollback removed the callback").isNull();
        assertThat(pipeline.isActive()).isFalse();
        assertThat(pipeline.isPreparing()).isFalse();
        assertThat(track.isRecording()).isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(part).as("nothing is deleted on the caller's thread").exists();
        assertThat(service.isTerminated()).isFalse();

        release.countDown();
        awaitWithinTheGuard(service.termination(), "the capture-flush thread's termination");

        assertThat(takeDirectoryEntries()).as("the flush thread deleted every file of the take").isEmpty();
        assertThat(service.isSealed()).as("a rolled-back start seals nothing").isFalse();
    }

    // (f)
    @Test
    void aSeekWhileTheTakeIsPreparedLeavesTheTakeAnchoredWhereRecordWasPressed() throws Exception {
        transport.setPositionInBeats(8.0);
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitTheHeldOpen();

        transport.setPositionInBeats(20.0); // a seek while the take's files are being created

        release.countDown();
        awaitWithinTheGuard(readiness, "the take's readiness");
        CaptureFlushService service = pipeline.getCaptureFlushService();
        List<Long> blockStarts = new CopyOnWriteArrayList<>();
        service.setBlockObserver((sequence, startFrame, numFrames) -> blockStarts.add(startFrame));
        pipeline.beginCapture();

        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        assertThat(transport.getPositionInBeats()).as("recording begins where Record was pressed").isEqualTo(8.0);
        assertThat(pipeline.getRecordingStartBeat()).isEqualTo(8.0);
        long anchorFrame = pipeline.getRecordingStartFrame();
        assertThat(anchorFrame).isEqualTo(192_000L); // 8 beats at 120 bpm = 4 s at 48 kHz
        TakeManifest initial = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(initial.startBeat()).isEqualTo(8.0);
        assertThat(initial.startFrame()).isEqualTo(anchorFrame);

        feedOne(0);
        feedOne(BLOCK_FRAMES);
        pipeline.awaitFlushed();
        assertThat(blockStarts).as("the blocks are stamped from the take's anchor on")
                .containsExactly(anchorFrame, anchorFrame + BLOCK_FRAMES);
        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(clips).singleElement().satisfies(clip -> assertThat(clip.getStartBeat()).isEqualTo(8.0));
        assertThat(transport.getPositionInBeats()).as("the stop returns to the take's anchor").isEqualTo(8.0);
    }

    // (f), over a rolling transport
    @Test
    void aTakePreparedOverARollingTransportBeginsWithoutMovingThePlayhead() throws Exception {
        transport.setPositionInBeats(8.0);
        // Rolling with no real-time clock claimed: a seek would be stored at once.
        transport.play();
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitTheHeldOpen();

        advanceOneBlock(transport); // playback rolls on while the take's files are being created
        double rolledTo = transport.getPositionInBeats();
        assertThat(rolledTo).as("fixture: the transport rolled on while the take was prepared").isGreaterThan(8.0);

        release.countDown();
        awaitWithinTheGuard(readiness, "the take's readiness");
        List<Transport.ChangeKind> changes = new CopyOnWriteArrayList<>();
        Runnable stopListening = transport.addChangeListener(changes::add);
        try {
            pipeline.beginCapture();
        } finally {
            stopListening.run();
        }

        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        assertThat(transport.getPositionInBeats()).as("the playhead is not set back to where prepare() read it")
                .isEqualTo(rolledTo);
        assertThat(changes).as("beginning capture changed the state and fired no position change")
                .containsExactly(Transport.ChangeKind.STATE);
        stopRecording(pipeline);
    }

    // (g), the cancel first
    @Test
    void aCancelThatComesBeforeReadinessLeavesNoFileAndCaptureNeverBegins() throws Exception {
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitTheHeldOpen();
        // What the app's FX dependent of the readiness does — on this test's
        // own thread here, once the stage has completed.
        AtomicReference<Throwable> beginOnReadiness = new AtomicReference<>();

        CompletionStage<Void> termination = cancelWithinTheGuard();
        release.countDown();
        Throwable notReady = failureWithinTheGuard(readiness, "the take's readiness");
        try {
            pipeline.beginCapture();
        } catch (IllegalStateException refused) {
            beginOnReadiness.set(refused);
        }

        assertThat(notReady).isInstanceOf(CancellationException.class);
        assertThat(termination.toCompletableFuture().isDone()).as("terminated before the readiness failed").isTrue();
        assertThat(beginOnReadiness.get()).as("capture never begins over a cancelled start")
                .isInstanceOf(IllegalStateException.class);
        assertThat(takeDirectoryEntries()).isEmpty();
        assertCaptureNeverBegan();
    }

    // (g), the readiness first
    @Test
    void aCancelThatComesAfterReadinessLeavesNoFileAndCaptureNeverBegins() throws Exception {
        pipeline = newPipeline(track);
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitWithinTheGuard(readiness, "the take's readiness");
        assertThat(firstSegmentPart(track)).as("fixture: the take is ready").exists();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        CompletionStage<Void> termination = cancelWithinTheGuard();
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");

        assertThat(readiness.toCompletableFuture().isCompletedExceptionally())
                .as("a readiness that completed normally stays so: it is no promise that capture begins").isFalse();
        assertThatThrownBy(pipeline::beginCapture)
                .as("the cancel, not the readiness, decides").isInstanceOf(IllegalStateException.class);
        assertThat(takeDirectoryEntries()).as("the flush thread discarded the ready take").isEmpty();
        assertThat(service.isSealed()).isFalse();
        assertCaptureNeverBegan();
    }

    // (h), after a cancel
    @Test
    void aRestartAfterACancelIsRefusedUntilTheCancelledTakesFlushThreadHasTerminated() throws Exception {
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitTheHeldOpen();
        CaptureFlushService cancelled = pipeline.getCaptureFlushService();
        CompletionStage<Void> termination = cancelWithinTheGuard();
        assertThat(termination.toCompletableFuture().isDone())
                .as("fixture: the cancelled take's flush thread still holds its first open").isFalse();

        assertTheRestartIsRefusedTouchingNothing(cancelled);

        release.countDown(); // the held open goes through; the initialisation finds the abort and deletes it all
        awaitWithinTheGuard(termination, "the cancelled take's flush thread's termination");
        assertThat(failureWithinTheGuard(readiness, "the cancelled take's readiness"))
                .isInstanceOf(CancellationException.class);
        assertThat(takeDirectoryEntries()).as("the cancelled take's files are gone").isEmpty();

        CompletionStage<Void> restarted = prepareWithinTheGuard();

        awaitWithinTheGuard(restarted, "the restarted take's readiness");
        assertThat(pipeline.getCaptureFlushService()).as("the restart has a flush service of its own")
                .isNotSameAs(cancelled);
        assertThat(firstSegmentPart(track)).as("the restarted take's first segment exists").exists();
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealStatus())
                .isEqualTo(TakeManifest.SealStatus.STREAMING);
        pipeline.beginCapture();
        feedOne(0);
        pipeline.awaitFlushed();
        assertThat(stopRecording(pipeline)).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(BLOCK_FRAMES));
    }

    // (h), after a rolled-back beginCapture
    @Test
    void aRestartAfterARolledBackBeginCaptureIsRefusedUntilThatTakesFlushThreadHasTerminated() throws Exception {
        pipeline = newPipeline(track);
        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        CaptureFlushService rolledBack = pipeline.getCaptureFlushService();
        holdTheFlushThreadInAPass(rolledBack);
        engine.shutdown(); // the engine start inside beginCapture() throws
        assertThat(outcomeWithinTheGuard("begin capture", pipeline::beginCapture))
                .as("fixture: beginCapture() failed and rolled back").isInstanceOf(IllegalStateException.class);

        assertTheRestartIsRefusedTouchingNothing(rolledBack);

        release.countDown();
        awaitWithinTheGuard(rolledBack.termination(), "the rolled-back take's flush thread's termination");
        assertThat(takeDirectoryEntries()).as("the rolled-back take's files are gone").isEmpty();

        CompletionStage<Void> restarted = prepareWithinTheGuard();

        awaitWithinTheGuard(restarted, "the restarted take's readiness");
        assertThat(pipeline.getCaptureFlushService()).as("the restart has a flush service of its own")
                .isNotSameAs(rolledBack);
        assertThat(firstSegmentPart(track)).as("the restarted take's first segment exists").exists();
        // The engine stays shut down, so this take can only be cancelled.
        awaitWithinTheGuard(cancelWithinTheGuard(), "the restarted take's flush thread's termination");
        assertThat(takeDirectoryEntries()).as("the restarted take's files are gone too").isEmpty();
    }

    @Test
    void whileATakeIsPreparedOnlyBeginCaptureOrCancelStartMayFollow() throws Exception {
        pipeline = newPipeline(track);
        pipeline.setChannelOpener(openerHeldAtTheFirstOpen());
        assertThatThrownBy(pipeline::beginCapture).as("nothing prepared yet").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(pipeline::cancelStart).as("nothing prepared yet").isInstanceOf(IllegalStateException.class);
        CompletionStage<Void> readiness = prepareWithinTheGuard();
        awaitTheHeldOpen();

        assertThatThrownBy(pipeline::prepare).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already preparing");
        assertThatThrownBy(pipeline::beginCapture).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
        assertThatThrownBy(pipeline::requestStop).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelStart()");
        assertThatThrownBy(pipeline::completeStop).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.setSegmentLimits(Duration.ofMinutes(1), 1024))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("being prepared");
        assertThatThrownBy(() -> pipeline.setForceCadence(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("being prepared");
        assertThatThrownBy(() -> pipeline.setLoopRecord(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("being prepared");
        assertThatThrownBy(() -> pipeline.setChannelOpener(SegmentWriter.CREATE_NEW_CHANNEL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("being prepared");
        assertThat(pipeline.isPreparing()).as("every refusal left the take being prepared").isTrue();
        assertCaptureNeverBegan();

        release.countDown();
        awaitWithinTheGuard(readiness, "the take's readiness");
        pipeline.beginCapture();
        assertThatThrownBy(pipeline::beginCapture).as("capture begins once")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(pipeline::cancelStart).as("a take that is recording is stopped, not cancelled")
                .isInstanceOf(IllegalStateException.class);
        assertThat(stopRecording(pipeline)).isEmpty();
        assertThatThrownBy(pipeline::beginCapture).isInstanceOf(IllegalStateException.class);
    }
}
