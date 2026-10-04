package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.HANG_GUARD;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The early-seal signal (Recording Reliability book §4.3, §5.2; story 323
 * review): when the {@code capture-flush} thread seals a take on its own —
 * the disk-headroom floor, a failed append, a rotation whose seal fails, a
 * failed cadence force, a throwable that escapes its drain loop —
 * {@link RecordingPipeline#earlySeal()} completes once, on that thread, with
 * the reason and whether every segment sealed, once every lane's seal and the
 * final manifest write have been attempted (in every test here that write
 * lands, so the final manifest is on disk when it completes); never for the
 * seal a stop requests, the rollback of a failed start or the
 * {@code stopAndAbandon} test seam, which stops the take without a seal and
 * abandons its writers.
 * The flush thread only signals: the take is the caller's to stop, and that stop
 * returns the clips of what was sealed with the manifest's {@code sealed-by}
 * kept. Every wait is bounded: a signal that never comes by
 * {@link RampCaptureTestSupport#HANG_GUARD}, the fences inside {@code feedRamp}
 * and {@code setDrainPaused} by {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT},
 * and each start's readiness and each stop's termination by
 * {@link PipelineLifecycleTestSupport#LIFECYCLE_GUARD}.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class EarlySealSignalContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    private static final long FLOOR = 64 * MIB;
    private static final long SECOND = 1_000_000_000L;
    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    /** What the injected headroom probe reads, on every check (refresh zero). */
    private final AtomicLong freeBytes = new AtomicLong(10 * GIB);
    /** While set, the injected headroom probe throws instead of reading {@link #freeBytes}. */
    private final AtomicBoolean probeFails = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
    }

    /** A pipeline whose headroom watch probes {@link #freeBytes} on every check, on the injected clock. */
    private RecordingPipeline newPipeline(Track... tracks) {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(tracks));
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> {
            if (probeFails.get()) {
                throw new UncheckedIOException(new IOException("injected free-space probe failure"));
            }
            return freeBytes.get();
        }, GIB, FLOOR, Duration.ZERO, clock::get, warnings::add));
        pipeline.setWarningSink(warnings::add);
        pipeline.setNanoClock(clock::get);
        return pipeline;
    }

    /** One ramp block through the recording callback, with no fence. */
    private void feedOne(long firstFrame) {
        engine.processBlock(rampBlock(firstFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
    }

    /**
     * What a non-async dependent of the signal saw when it ran: the value,
     * the thread it ran on, the manifest on disk at that moment (its status
     * and {@code sealed-by}, {@code null} when it could not be read or
     * carries none), and whether the flush thread had terminated.
     */
    private record Observed(EarlySeal seal, String thread, SealStatus status, SealedBy sealedBy,
                            boolean terminated) {
    }

    /** A non-async dependent registered on a take's signal, recording every run of it. */
    private static final class SignalWitness {
        final List<Observed> runs = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Observed> first = new CompletableFuture<>();

        SignalWitness(RecordingPipeline pipeline) {
            CaptureFlushService service = pipeline.getCaptureFlushService();
            Path manifestPath = pipeline.getTakeManifestPath();
            pipeline.earlySeal().thenAccept(seal -> {
                TakeManifest onDisk = readOrNull(manifestPath);
                Observed observed = new Observed(seal, Thread.currentThread().getName(),
                        onDisk == null ? null : onDisk.sealStatus(),
                        onDisk == null ? null : onDisk.sealedBy().orElse(null),
                        service.isTerminated());
                runs.add(observed);
                first.complete(observed);
            });
        }

        /** Waits, for at most {@link RampCaptureTestSupport#HANG_GUARD}, for the first run. */
        Observed awaitFirst() throws Exception {
            return first.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        }

        private static TakeManifest readOrNull(Path manifestPath) {
            try {
                return TakeManifest.read(manifestPath);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static boolean isDone(CompletionStage<?> stage) {
        return stage.toCompletableFuture().isDone();
    }

    @Test
    void theHeadroomFloorSignalsOnceOnTheFlushThreadAfterTheAbortedManifestIsOnDisk() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 3, 1);
        assertThat(isDone(pipeline.earlySeal())).as("fixture: nothing sealed yet").isFalse();

        freeBytes.set(MIB); // below the 64 MiB floor
        feedRamp(engine, transport, pipeline, frame, 2, 1); // the first meets the floor, the second is discarded

        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isEqualTo(new EarlySeal.DiskExhausted(FLOOR, false, true));
        assertThat(observed.seal().reason()).isEqualTo(SealedBy.DISK_EXHAUSTION);
        assertThat(observed.thread()).as("completed on the flush thread, never on the audio callback's")
                .isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(observed.status()).as("the final manifest was on disk when the signal ran")
                .isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.DISK_EXHAUSTION);
        assertThat(observed.terminated()).as("the thread signals and keeps draining").isFalse();
        assertThat(pipeline.isActive()).as("the pipeline does nothing on the signal").isTrue();
        assertThat(engine.getRecordingCallback()).isNotNull();
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(witness.runs).as("signalled once").hasSize(1);
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(3 * BLOCK_FRAMES));
        TakeManifest afterStop = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(afterStop.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(afterStop.sealedBy()).as("the stop keeps the early seal's reason").contains(SealedBy.DISK_EXHAUSTION);
        assertThat(pipeline.earlySeal().toCompletableFuture().join()).isSameAs(observed.seal());
    }

    @Test
    void aFreeSpaceProbeThatKeepsFailingIsReportedAsFreeSpaceUnknown() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);

        probeFails.set(true);
        // One failed probe keeps the last figure; the second in a row is EXHAUSTED.
        feedRamp(engine, transport, pipeline, frame, 2, 1);

        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isEqualTo(new EarlySeal.DiskExhausted(FLOOR, true, true));
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.DISK_EXHAUSTION);
        assertThat(stopRecording(pipeline)).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(3 * BLOCK_FRAMES));
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void aFailedAppendSignalsAWriteFailureCarryingTheThrowableThatEndedTheTake() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);

        pipeline.getSession(track).getCurrentWriter().failNextAppend();
        feedRamp(engine, transport, pipeline, frame, 2, 1); // the first fails and seals, the second is discarded

        Observed observed = witness.awaitFirst();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(observed.seal()).isInstanceOfSatisfying(EarlySeal.WriteFailed.class, failed -> {
            assertThat(failed.failure()).isInstanceOf(UncheckedIOException.class)
                    .hasRootCauseMessage("injected append failure (test seam) on "
                            + takeDir.resolve(track.getId()).resolve("segment-000.wav.part"));
            assertThat(failed.failure()).isSameAs(service.lastFailure().orElseThrow());
            assertThat(failed.everySegmentSealed()).isTrue();
        });
        assertThat(observed.seal().reason()).isEqualTo(SealedBy.WRITE_FAILURE);
        assertThat(observed.thread()).isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.WRITE_FAILURE);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(witness.runs).hasSize(1);
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(2 * BLOCK_FRAMES));
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealedBy()).contains(SealedBy.WRITE_FAILURE);
    }

    @Test
    void aFailedCadenceForceSignalsAWriteFailure() throws Exception {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        clock.set(SECOND);
        feedRamp(engine, transport, pipeline, 0, 1, 1);
        assertThat(pipeline.getSession(track).getCurrentWriter().bytesSinceForce())
                .as("fixture: one block written, not forced yet").isPositive();

        journal.failNextForces(1);
        clock.set(5 * SECOND); // the 5 s cadence has elapsed: the next idle pass's tick forces, and that force throws

        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isInstanceOfSatisfying(EarlySeal.WriteFailed.class, failed -> {
            assertThat(failed.failure()).isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("force failed on")
                    .hasRootCauseMessage("injected force(false) failure (test double)");
            assertThat(failed.everySegmentSealed()).isTrue();
        });
        assertThat(observed.thread()).isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.WRITE_FAILURE);

        assertThat(stopRecording(pipeline)).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(BLOCK_FRAMES));
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void aThrowableThatEscapesTheDrainLoopSignalsAWriteFailureBeforeTheThreadTerminates() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        IllegalStateException fault = new IllegalStateException("injected drain-loop fault");
        // Installed while the loop is held between passes: the observer sees the next block first.
        service.setDrainPaused(true);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            throw fault;
        });
        service.setDrainPaused(false);

        feedOne(frame); // applied and written; then the observer's throw escapes the drain loop

        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isEqualTo(new EarlySeal.WriteFailed(fault, true));
        assertThat(observed.thread()).isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.WRITE_FAILURE);
        assertThat(observed.terminated()).as("signalled before the thread terminates").isFalse();
        service.termination().toCompletableFuture().get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(pipeline.isActive()).as("the take stays the caller's to stop").isTrue();

        assertThat(stopRecording(pipeline)).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(3 * BLOCK_FRAMES));
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void aHeadroomSealThatRethrowsALanesErrorSignalsOnlyOnceEveryLaneAndTheManifestAreDone() throws Exception {
        // The first track's stop notification dies with an Error after its
        // segment sealed; the seal goes on to the second lane, writes the
        // manifest and only then rethrows — and the signal waits for all of it.
        Track second = RampCaptureTestSupport.armedMonoTrack("Vocal 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            if (t == track) {
                session.addListener(new StopFaultListener(fault));
            }
            return session;
        });
        startRecording(pipeline);
        Path secondSealed = takeDir.resolve(second.getId()).resolve("segment-000.wav");
        AtomicBoolean secondLaneSealedAtTheSignal = new AtomicBoolean();
        pipeline.earlySeal().thenRun(() -> secondLaneSealedAtTheSignal.set(secondSealed.toFile().isFile()));
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);

        freeBytes.set(MIB);
        feedRamp(engine, transport, pipeline, frame, 1, 1);

        Observed observed = witness.awaitFirst();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(service.lastFailure()).as("fixture: the seal rethrew the first lane's Error").containsSame(fault);
        assertThat(observed.seal()).as("the headroom floor sealed the take; every segment did seal")
                .isEqualTo(new EarlySeal.DiskExhausted(FLOOR, false, true));
        assertThat(secondLaneSealedAtTheSignal).as("the lane after the throwing one had its seal first").isTrue();
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).as("a lane threw, so the manifest says write-failure")
                .isEqualTo(SealedBy.WRITE_FAILURE);

        assertThat(stopRecording(pipeline)).hasSize(2);
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void aSegmentWhoseSealFailsIsReportedAsLeftForRecovery() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        Path part = takeDir.resolve(track.getId()).resolve("segment-000.wav.part");
        pipeline.getSession(track).getCurrentWriter().failNextRename();

        freeBytes.set(MIB);
        feedRamp(engine, transport, pipeline, frame, 1, 1); // meets the floor; the seal's rename fails

        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isEqualTo(new EarlySeal.DiskExhausted(FLOOR, false, false));
        assertThat(observed.seal().everySegmentSealed()).isFalse();
        assertThat(part).as("the segment is left as its .part for recovery").isRegularFile();
        assertThat(part.resolveSibling("segment-000.wav")).doesNotExist();
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.WRITE_FAILURE);

        stopRecording(pipeline);
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void aRotationAfterTheAppendThatFillsTheSegmentWhoseSealFailsIsReportedAsLeftForRecovery() throws Exception {
        // Four blocks fill the segment: the rotation right after the fourth
        // append seals it, that seal fails, and the fifth block is discarded.
        assertARotationWhoseSealFailsIsReportedAsLeftForRecovery(4 * BLOCK_BYTES, 4);
    }

    @Test
    void aRotationBeforeAnAppendThatWouldPassTheCapWhoseSealFailsIsReportedAsLeftForRecovery() throws Exception {
        // A cap of three and a half blocks: the fourth block would pass it, so
        // the segment rotates before that block is appended, that seal fails,
        // and neither the fourth nor the fifth block is written.
        assertARotationWhoseSealFailsIsReportedAsLeftForRecovery(3 * BLOCK_BYTES + BLOCK_BYTES / 2, 3);
    }

    /**
     * Records two blocks into a segment capped at {@code segmentBytes}, sets
     * the segment's rename to fail, and feeds three more blocks: the rotation
     * among them fails to seal the segment, which ends the take early, and
     * the signal reports that a segment is left as its {@code .part}.
     *
     * @param segmentBytes the segment byte cap
     * @param keptBlocks   the blocks written before the failed seal
     */
    private void assertARotationWhoseSealFailsIsReportedAsLeftForRecovery(long segmentBytes, int keptBlocks)
            throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), segmentBytes);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        Path trackDir = takeDir.resolve(track.getId());
        Path part = trackDir.resolve("segment-000.wav.part");
        pipeline.getSession(track).getCurrentWriter().failNextRename();

        feedRamp(engine, transport, pipeline, frame, 3, 1);

        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isInstanceOfSatisfying(EarlySeal.WriteFailed.class, failed -> {
            assertThat(failed.failure()).isInstanceOf(UncheckedIOException.class)
                    .hasMessage("cannot seal segment " + trackDir.resolve("segment-000.wav"))
                    .hasRootCauseMessage("injected rename failure (test seam) on " + part);
            assertThat(failed.everySegmentSealed()).as("the rotation's segment did not seal").isFalse();
        });
        assertThat(observed.thread()).isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.WRITE_FAILURE);
        try (Stream<Path> files = Files.list(trackDir)) {
            assertThat(files).as("the segment is left as its .part for recovery, and no next segment was opened")
                    .containsExactly(part);
        }

        // No segment sealed, so the clip lists none and carries no audio; it
        // declares the frames the take captured, and those are in the .part.
        assertThat(stopRecording(pipeline)).singleElement().satisfies(clip -> {
            assertThat(clip.getSourceSegmentPaths()).isEmpty();
            assertThat(clip.getAudioData()).isNull();
            assertThat(clip.getSourceRateMetadata().framesPerChannel()).isEqualTo((long) keptBlocks * BLOCK_FRAMES);
        });
        RampCaptureTestSupport.assertDecodedRamp(SegmentFile.readFrames(List.of(part))[0],
                0, (long) keptBlocks * BLOCK_FRAMES);
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void anExhaustionInTheFinalSweepOfARequestedStopSignalsAndThatStopReturnsTheTakeSealedEarly()
            throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        // Held between passes: the two blocks below wait in the ring for the stop's final sweep.
        service.setDrainPaused(true);
        freeBytes.set(MIB);
        feedOne(frame);
        feedOne(frame + BLOCK_FRAMES);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(isDone(pipeline.earlySeal())).as("the early seal in the final sweep is signalled").isTrue();
        Observed observed = witness.awaitFirst();
        assertThat(observed.seal()).isEqualTo(new EarlySeal.DiskExhausted(FLOOR, false, true));
        assertThat(observed.thread()).isEqualTo(CaptureFlushService.THREAD_NAME);
        assertThat(observed.status()).isEqualTo(SealStatus.ABORTED);
        assertThat(observed.sealedBy()).isEqualTo(SealedBy.DISK_EXHAUSTION);
        assertThat(service.sealReason()).as("the stop's own seal kept the early reason")
                .contains(SealedBy.DISK_EXHAUSTION);
        assertThat(service.discardedBlocks()).isEqualTo(2);
        assertThat(clips).singleElement()
                .satisfies(clip -> assertThat(RecordedAudioTestSupport.audioOnDisk(clip)[0]).hasSize(2 * BLOCK_FRAMES));
        assertThat(pipeline.isActive()).isFalse();
        assertThat(witness.runs).hasSize(1);
    }

    @Test
    void theSealAStopRequestsNeverSignals() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);

        assertThat(stopRecording(pipeline)).hasSize(1);

        assertThat(pipeline.getCaptureFlushService().isTerminated()).isTrue();
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        assertThat(witness.runs).isEmpty();
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(SealedBy.STOP);
    }

    @Test
    void aStopsSealThatRethrowsALanesErrorNeverSignalsAlthoughTheLoopReportsIt() throws Exception {
        // The seal the stop requests rethrows the lane's Error to the loop's
        // failure handler, which re-enters the seal with WRITE_FAILURE: still
        // not an early seal.
        RecordingPipeline pipeline = newPipeline(track);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            session.addListener(new StopFaultListener(fault));
            return session;
        });
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);

        assertThat(stopRecording(pipeline)).hasSize(1);

        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(service.lastFailure()).as("fixture: the lane's Error reached the loop").containsSame(fault);
        assertThat(warnings).as("fixture: the loop reported it")
                .anySatisfy(w -> assertThat(w).contains("injected stop-listener fault"));
        assertThat(service.sealReason()).contains(SealedBy.STOP);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealedBy()).contains(SealedBy.WRITE_FAILURE);
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        assertThat(witness.runs).isEmpty();
    }

    @Test
    void aFailedStartsRollbackNeverSignals() {
        RecordingPipeline pipeline = newPipeline(track);
        // transport.record() is beginCapture()'s last step, once the take is
        // ready: a failure there rolls the start back through requestAbort.
        Runnable unsubscribe = transport.addChangeListener(kind -> {
            if (transport.getState() == TransportState.RECORDING) {
                throw new IllegalStateException("injected record failure");
            }
        });
        try {
            assertThatThrownBy(() -> startRecording(pipeline)).hasMessageContaining("injected record failure");
        } finally {
            unsubscribe.run();
        }

        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(service).as("fixture: the start got as far as its flush service").isNotNull();
        awaitTermination(service); // the rollback asked the thread to discard the take and did not wait
        assertThat(isDone(pipeline.earlySeal())).isFalse();
    }

    @Test
    void aStopThatAbandonsTheWritersWithoutASealNeverSignals() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        SignalWitness witness = new SignalWitness(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        CaptureFlushService service = pipeline.getCaptureFlushService();

        service.stopAndAbandon();
        awaitTermination(service);

        assertThat(service.isTerminated()).isTrue();
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        assertThat(witness.runs).isEmpty();
    }

    @Test
    void aPipelineStartedAgainHandsOutItsNewTakesSignalNeverThePreviousTakes() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        freeBytes.set(MIB); // the first take meets the floor at its first block and writes nothing
        startRecording(pipeline);
        CompletionStage<EarlySeal> firstTake = pipeline.earlySeal();
        feedRamp(engine, transport, pipeline, 0, 1, 1);
        assertThat(isDone(firstTake)).as("fixture: the first take was sealed early").isTrue();
        assertThat(stopRecording(pipeline)).as("fixture: it recorded nothing").isEmpty();

        freeBytes.set(10 * GIB);
        startRecording(pipeline); // the same take directory: the first take left no segment file behind
        CompletionStage<EarlySeal> secondTake = pipeline.earlySeal();
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        List<AudioClip> clips = stopRecording(pipeline);

        // Compared as references: AssertJ's CompletionStage assertions wrap
        // the stage in a new future, and its failure message cannot print a
        // minimal stage.
        assertThat(secondTake == firstTake).as("the second take hands out its own signal, not the first take's")
                .isFalse();
        assertThat(isDone(secondTake)).as("the second take was never sealed early").isFalse();
        assertThat(clips).hasSize(1);
        assertThat(firstTake.toCompletableFuture().join()).isInstanceOf(EarlySeal.DiskExhausted.class);
    }

    @Test
    void aPipelineThatWasNeverStartedHasNoSignal() {
        RecordingPipeline pipeline = newPipeline(track);

        assertThatThrownBy(pipeline::earlySeal).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been started");
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
