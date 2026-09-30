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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 review probe (Copilot 5365941737, HIGH) — four edges of the
 * pending-finalisation contract the review round's own tests do not reach:
 *
 * <ul>
 *   <li>the flush thread is held <em>after</em> every lane is sealed, in the
 *       final manifest write: the take is still being written (its
 *       manifest), so the Stop is still pending — the thread is terminated
 *       only once it will never touch the take again, manifest included;</li>
 *   <li>a holder of the pending take's completion cannot complete it — the
 *       app posts its deferred stop from that completion, and a forged one
 *       would run that stop, with its bounded join on the FX thread, before
 *       the thread has terminated;</li>
 *   <li>a one-shot side effect of the first {@code stop()} that throws (a
 *       transport listener) leaves the take pending, not orphaned: the next
 *       {@code stop()} stops the flush thread, seals the take and builds it;</li>
 *   <li>a dependent of the termination signal that stops the pipeline runs
 *       on the flush thread, and that stop never joins its own thread: with
 *       a join bound far beyond the guard, it still returns the clips.</li>
 * </ul>
 *
 * <p>The final-manifest hold: every attempt of the seal's manifest write is
 * refused through {@code CaptureFlushService.failNextManifestWrites}, and
 * the warning the flush thread then hands to the sink holds it on a latch.
 * Every wait is bounded: by {@link Story323TestSupport#HANG_GUARD} — above
 * the {@link #JOIN} a guarded {@code stop()} waits, and below the one-hour
 * bound of the last test, whose stop must never join its own thread at all —
 * or, inside {@code feedRamp} and {@code setDrainPaused}, by
 * {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}.</p>
 */
class Story323StopFinalizationProbeContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    /**
     * The shortened join: long enough that the flush thread reaches its hold
     * (four 20 ms manifest retry pauses after the lane seal) before the join
     * runs out, and far inside {@link Story323TestSupport#HANG_GUARD}.
     */
    private static final Duration JOIN = Duration.ofSeconds(1);

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
        track = Story323TestSupport.armedMonoTrack("Vocal");
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

    /** The pipeline's warning sink; runs on the flush thread for the manifest-failure warning. */
    private void onWarning(String message) {
        warnings.add(message);
        if (message.contains("could not be written") && holdOnManifestWarning.compareAndSet(true, false)) {
            held.countDown();
            try {
                release.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
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
     * Records two blocks, then stops with every attempt of the next manifest
     * write refused — with the ring dry and nothing dirty that is the seal's
     * final write, made after every lane is sealed — and the flush thread
     * held in the warning that follows.
     *
     * @return the pending take the stop threw
     */
    private TakeFinalizationPendingException stopHeldInTheFinalManifestWrite() throws Exception {
        pipeline = newPipeline();
        pipeline.start();
        feedRamp(engine, transport, pipeline, 0, 2, 2);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.setStopJoinTimeout(JOIN);
        long manifestWritesBefore = service.manifestWrites();
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);
        holdOnManifestWarning.set(true);

        Throwable thrown = outcomeWithinTheGuard("stop", pipeline::stop);

        assertThat(held.await(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("fixture: the flush thread is held in the final manifest write").isTrue();
        assertThat(service.isSealed()).as("fixture: the seal has begun").isTrue();
        assertThat(service.sealedSegmentPaths().get(track.getId()))
                .as("fixture: every lane is sealed — what a build now would read is already complete")
                .hasSize(1);
        assertThat(service.manifestWrites()).as("fixture: the final manifest has not reached the disk")
                .isEqualTo(manifestWritesBefore);
        assertThat(thrown)
                .as("the thread still writes the take's manifest: the Stop is pending, not built")
                .isInstanceOf(TakeFinalizationPendingException.class);
        return (TakeFinalizationPendingException) thrown;
    }

    /** Runs a stop that must return within the guard, and returns its clips. */
    private List<AudioClip> stopThatCompletes() throws InterruptedException {
        List<List<AudioClip>> result = new CopyOnWriteArrayList<>();
        Throwable thrown = outcomeWithinTheGuard("stop that completes", () -> result.add(pipeline.stop()));
        assertThat(thrown).as("the stop completes").isNull();
        return result.getFirst();
    }

    @Test
    void aStopHeldInTheFinalManifestWriteAfterEveryLaneIsSealedIsStillPending() throws Exception {
        TakeFinalizationPendingException pending = stopHeldInTheFinalManifestWrite();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        assertThat(service.isTerminated()).as("the thread has the take's manifest still to write").isFalse();
        assertThat(pending.completion().toCompletableFuture().isDone()).isFalse();
        assertThat(pipeline.isFinalizationPending()).isTrue();
        assertThat(track.getClips()).as("no clip is built while the flush thread still writes the take").isEmpty();
        assertThat(pipeline.getRecordedClips()).isEmpty();

        release.countDown();
        pending.completion().toCompletableFuture().get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(service.isTerminated()).isTrue();
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).contains("could not be written"));

        List<AudioClip> clips = stopThatCompletes();

        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(2 * BLOCK_FRAMES));
        assertThat(track.getClips()).containsExactlyElementsOf(clips);
    }

    @Test
    void aHolderOfThePendingTakesCompletionCannotCompleteIt() throws Exception {
        TakeFinalizationPendingException pending = stopHeldInTheFinalManifestWrite();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        AtomicBoolean dependentRan = new AtomicBoolean();
        pending.completion().thenRun(() -> dependentRan.set(true));

        // What a holder may try: complete the stage it was handed.
        pending.completion().toCompletableFuture().complete(null);

        assertThat(dependentRan).as("no dependent runs on a forged completion").isFalse();
        assertThat(service.termination().toCompletableFuture().isDone())
                .as("the service's own signal is not completed by a holder").isFalse();
        assertThat(service.isTerminated()).isFalse();
        assertThat(track.getClips()).isEmpty();

        release.countDown();
        service.termination().toCompletableFuture().get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(dependentRan).as("the real termination runs it").isTrue();
        assertThat(stopThatCompletes()).hasSize(1);
    }

    @Test
    void aOneShotStopEffectThatThrowsLeavesTheTakePendingAndTheNextStopSealsIt() throws Exception {
        RuntimeException fault = new IllegalStateException("injected transport-listener fault");
        AtomicBoolean throwOnStateChange = new AtomicBoolean();
        transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE && throwOnStateChange.compareAndSet(true, false)) {
                throw fault;
            }
        });
        pipeline = newPipeline();
        pipeline.start();
        feedRamp(engine, transport, pipeline, 0, 3, 3);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        throwOnStateChange.set(true); // the transport stop inside pipeline.stop() notifies, and its listener throws

        Throwable thrown = outcomeWithinTheGuard("stop", pipeline::stop);

        assertThat(thrown).as("the listener's fault reaches the caller").isSameAs(fault);
        assertThat(transport.getState()).as("fixture: the transport stopped before its listener threw")
                .isEqualTo(TransportState.STOPPED);
        assertThat(pipeline.isActive()).isFalse();
        assertThat(pipeline.isFinalizationPending())
                .as("the stop has begun and not finished: the take is pending, not orphaned").isTrue();
        assertThat(track.getClips()).isEmpty();

        List<AudioClip> clips = stopThatCompletes();

        assertThat(service.isTerminated()).as("the next stop stopped the flush thread").isTrue();
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.STOP);
        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(3 * BLOCK_FRAMES));
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
    }

    @Test
    void aDependentOfTheTerminationThatStopsThePipelineOnTheFlushThreadNeverJoinsItself() throws Exception {
        pipeline = newPipeline();
        pipeline.start();
        feedRamp(engine, transport, pipeline, 0, 2, 2);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        // A join bound far beyond the guard: only a stop that never joins
        // its own thread can return within it.
        service.setStopJoinTimeout(Duration.ofHours(1));
        CompletableFuture<List<AudioClip>> stoppedThere = new CompletableFuture<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        // Non-async: runs on the flush thread as its last act.
        service.termination().thenRun(() -> {
            ranOn.set(Thread.currentThread());
            try {
                stoppedThere.complete(pipeline.stop());
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
                .satisfies(clip -> assertThat(clip.getAudioData()[0])
                        .as("the block applied before the fault is in the take").hasSize(3 * BLOCK_FRAMES));
    }
}
