package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 323 review probe (Copilot 5365941737, HIGH; PR #978 review
 * 5391920205 F1) — four edges of the pending-finalisation contract:
 *
 * <ul>
 *   <li>the flush thread is held <em>after</em> every lane is sealed, in the
 *       final manifest write: the take is still being written (its
 *       manifest), so the stop cannot be completed yet — the thread is
 *       terminated only once it will never touch the take again, manifest
 *       included;</li>
 *   <li>a holder of the termination signal a stop request returns cannot
 *       complete it — the app completes the stop from that signal, and a
 *       forged one would complete it before the thread has terminated;</li>
 *   <li>a one-shot side effect of the stop request that throws (a transport
 *       listener) still leaves the seal requested and the take pending, not
 *       orphaned: the thread seals the take and terminates, a repeated
 *       request repeats nothing, and the completion builds the take;</li>
 *   <li>a dependent of the termination signal that completes the stop runs
 *       on the flush thread, and builds the clips there.</li>
 * </ul>
 *
 * <p>The final-manifest hold: every attempt of the seal's manifest write is
 * refused through {@code CaptureFlushService.failNextManifestWrites}, and
 * the warning the flush thread then hands to the sink holds it on a latch.
 * Every wait is bounded: by {@link RampCaptureTestSupport#HANG_GUARD} — a
 * request that must return at once, and every signal — or, inside
 * {@code feedRamp} and {@code setDrainPaused}, by
 * {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class PendingTakeCompletionContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private RecordingPipeline pipeline;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    /** Armed by a test: the next manifest-failure warning holds the flush thread until {@link #release}. */
    private final AtomicBoolean holdOnManifestWarning = new AtomicBoolean();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        transport.setTempo(120.0);
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
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

    /** The pipeline's warning sink; runs on the flush thread for the manifest-failure warning. */
    private void onWarning(String message) {
        warnings.add(message);
        if (message.contains("could not be written") && holdOnManifestWarning.compareAndSet(true, false)) {
            held.countDown();
            try {
                release.await(2 * HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private RecordingPipeline newPipeline() {
        RecordingPipeline created = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        // Headroom that never warns, whatever the machine's disk holds.
        created.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        // No cadence force: the ring runs dry with nothing to force and nothing to rewrite.
        created.setForceCadence(Duration.ofHours(1));
        created.setWarningSink(this::onWarning);
        return created;
    }

    /**
     * Records two blocks, then requests the stop with every attempt of the
     * next manifest write refused — with the ring dry and nothing dirty that
     * is the seal's final write, made after every lane is sealed — and the
     * flush thread held in the warning that follows, for twice the guard.
     *
     * @return the termination signal the stop request returned
     */
    private CompletionStage<Void> stopHeldInTheFinalManifestWrite() throws Exception {
        pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 2);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long manifestWritesBefore = service.manifestWrites();
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);
        holdOnManifestWarning.set(true);

        AtomicReference<CompletionStage<Void>> written = new AtomicReference<>();
        Throwable thrown = outcomeWithinTheGuard("stop request", () -> written.set(pipeline.requestStop()));

        assertThat(thrown).as("the stop request returns without waiting for the seal").isNull();
        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the flush thread is held in the final manifest write").isTrue();
        assertThat(service.isSealed()).as("fixture: the seal has begun").isTrue();
        assertThat(service.sealedSegmentPaths().get(track.getId()))
                .as("fixture: every lane is sealed — what a build now would read is already complete")
                .hasSize(1);
        assertThat(service.manifestWrites()).as("fixture: the final manifest has not reached the disk")
                .isEqualTo(manifestWritesBefore);
        return written.get();
    }

    /** Runs a completion that must return within the guard, and returns its clips. */
    private List<AudioClip> completionThatBuilds() throws InterruptedException {
        List<List<AudioClip>> result = new CopyOnWriteArrayList<>();
        Throwable thrown = outcomeWithinTheGuard("stop completion", () -> result.add(pipeline.completeStop()));
        assertThat(thrown).as("the completion builds the take").isNull();
        return result.getFirst();
    }

    @Test
    void aStopHeldInTheFinalManifestWriteAfterEveryLaneIsSealedCannotBeCompletedYet() throws Exception {
        CompletionStage<Void> written = stopHeldInTheFinalManifestWrite();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        assertThat(service.isTerminated()).as("the thread has the take's manifest still to write").isFalse();
        assertThat(written.toCompletableFuture().isDone()).isFalse();
        assertThat(pipeline.isFinalizationPending()).isTrue();
        assertThatThrownBy(pipeline::completeStop)
                .as("the thread still writes the take's manifest: the stop is pending, not built")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still being written");
        assertThat(pipeline.isFinalizationPending()).isTrue();
        assertThat(track.getClips()).as("no clip is built while the flush thread still writes the take").isEmpty();
        assertThat(pipeline.getRecordedClips()).isEmpty();

        release.countDown();
        awaitWithinTheGuard(written, "the capture-flush thread's termination");
        assertThat(service.isTerminated()).isTrue();
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).contains("could not be written"));

        List<AudioClip> clips = completionThatBuilds();

        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(2 * BLOCK_FRAMES));
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
    }

    @Test
    void aHolderOfTheStopRequestsSignalCannotCompleteIt() throws Exception {
        CompletionStage<Void> written = stopHeldInTheFinalManifestWrite();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        CountDownLatch dependentRan = new CountDownLatch(1);
        written.thenRun(dependentRan::countDown);

        // What a holder may try: complete the stage it was handed.
        written.toCompletableFuture().complete(null);

        assertThat(dependentRan.getCount()).as("no dependent runs on a forged completion").isEqualTo(1);
        assertThat(service.termination().toCompletableFuture().isDone())
                .as("the service's own signal is not completed by a holder").isFalse();
        assertThat(service.isTerminated()).isFalse();
        assertThat(track.getClips()).isEmpty();

        release.countDown();
        // Waited for itself: another dependent of the signal may wake this thread first.
        assertThat(dependentRan.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the real termination runs it").isTrue();
        assertThat(service.isTerminated()).isTrue();
        assertThat(completionThatBuilds()).hasSize(1);
    }

    @Test
    void aOneShotStopEffectThatThrowsStillRequestsTheSealAndTheCompletionBuildsTheTake() throws Exception {
        RuntimeException fault = new IllegalStateException("injected transport-listener fault");
        AtomicBoolean throwOnStateChange = new AtomicBoolean();
        transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE && throwOnStateChange.compareAndSet(true, false)) {
                throw fault;
            }
        });
        pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 3, 3);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        throwOnStateChange.set(true); // the transport stop inside requestStop() notifies, and its listener throws

        Throwable thrown = outcomeWithinTheGuard("stop request", pipeline::requestStop);

        assertThat(thrown).as("the listener's fault reaches the caller").isSameAs(fault);
        assertThat(transport.getState()).as("fixture: the transport stopped before its listener threw")
                .isEqualTo(TransportState.STOPPED);
        assertThat(pipeline.isActive()).isFalse();
        assertThat(pipeline.isFinalizationPending())
                .as("the stop has begun and not finished: the take is pending, not orphaned").isTrue();
        assertThat(track.getClips()).isEmpty();

        // The seal was requested all the same: the thread terminates with no further call.
        awaitWithinTheGuard(service.termination(), "the capture-flush thread's termination");
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.STOP);

        AtomicReference<CompletionStage<Void>> again = new AtomicReference<>();
        assertThat(outcomeWithinTheGuard("stop request again", () -> again.set(pipeline.requestStop())))
                .as("a repeated request repeats nothing, so nothing throws").isNull();
        assertThat(again.get() == service.termination()).as("it returns the same termination signal").isTrue();

        List<AudioClip> clips = completionThatBuilds();

        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(3 * BLOCK_FRAMES));
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
    }

    @Test
    void aDependentOfTheTerminationCompletesTheStopOnTheFlushThread() throws Exception {
        pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 2);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        CompletableFuture<List<AudioClip>> stoppedThere = new CompletableFuture<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        // Non-async: runs on the flush thread as its last act.
        service.termination().thenRun(() -> {
            ranOn.set(Thread.currentThread());
            try {
                pipeline.requestStop();
                stoppedThere.complete(pipeline.completeStop());
            } catch (Throwable t) {
                stoppedThere.completeExceptionally(t);
            }
        });
        // The loop ends without a stop: a throwable escapes it (the block
        // observer seam). Installed while the loop is held between passes, so
        // the pass that applied the second block has ended and the observer
        // sees the third block first.
        service.setDrainPaused(true);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            throw new IllegalStateException("injected drain-loop fault");
        });
        service.setDrainPaused(false);
        engine.processBlock(rampBlock(2L * BLOCK_FRAMES), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);

        List<AudioClip> clips = stoppedThere.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(ranOn.get()).as("the dependent ran on the flush thread").isSameAs(service.thread());
        assertThat(service.isTerminated()).isTrue();
        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0])
                        .as("the block applied before the fault is in the take").hasSize(3 * BLOCK_FRAMES));
    }
}
