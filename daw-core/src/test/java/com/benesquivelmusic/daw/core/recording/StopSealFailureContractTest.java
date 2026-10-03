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

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The failure of the seal a stop requests (Recording Reliability book §5.2,
 * the FINALIZING row's "write failure → ABORTED with partial take intact +
 * error"; story 323 review): when a lane's seal throws in that seal — a
 * failed rename, an {@link Error} — {@link RecordingPipeline#stopSealFailure()}
 * reports the first throwable a lane threw and whether every segment sealed
 * anyway, once the stop has completed; the early-seal signal
 * stays uncompleted, because that seal is no early seal. It reports nothing
 * for a stop in which no lane's seal threw, for a take the flush thread
 * sealed early — mid-take or in the final sweep of the stop, even when a lane
 * threw in that early seal — for the rollback of a failed start, or for the
 * {@code stopAndAbandon} test seam. Every wait is bounded: the fences inside
 * {@code feedRamp} and {@code setDrainPaused} by
 * {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT}, and each start's
 * readiness and each stop's termination by
 * {@link PipelineLifecycleTestSupport#LIFECYCLE_GUARD}.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class StopSealFailureContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    private static final long FLOOR = 64 * MIB;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    /** What the injected headroom probe reads, on every check (refresh zero). */
    private final AtomicLong freeBytes = new AtomicLong(10 * GIB);

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
    }

    /** A pipeline whose headroom watch probes {@link #freeBytes} on every check, on the injected clock. */
    private RecordingPipeline newPipeline(Track... tracks) {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(tracks));
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, freeBytes::get,
                GIB, FLOOR, Duration.ZERO, clock::get, warnings::add));
        pipeline.setWarningSink(warnings::add);
        pipeline.setNanoClock(clock::get);
        return pipeline;
    }

    /** One ramp block through the recording callback, with no fence. */
    private void feedOne(long firstFrame) {
        engine.processBlock(rampBlock(firstFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
    }

    private static boolean isDone(CompletionStage<?> stage) {
        return stage.toCompletableFuture().isDone();
    }

    private TakeManifest manifestOnDisk(RecordingPipeline pipeline) throws Exception {
        return TakeManifest.read(pipeline.getTakeManifestPath());
    }

    @Test
    void aStopThatSealsEveryLaneReportsNoFailure() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        assertThat(pipeline.stopSealFailure()).as("nothing before the stop's seal").isEmpty();
        feedRamp(engine, transport, pipeline, 0, 2, 1);

        assertThat(stopRecording(pipeline)).hasSize(1);

        assertThat(pipeline.stopSealFailure()).isEmpty();
        assertThat(pipeline.getCaptureFlushService().stopSealFailure()).isEmpty();
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        assertThat(manifestOnDisk(pipeline).sealStatus()).isEqualTo(SealStatus.SEALED);
        assertThat(manifestOnDisk(pipeline).sealedBy()).contains(SealedBy.STOP);
    }

    @Test
    void aStopWhoseSegmentRenameFailsReportsTheFailureAndLeavesTheSegmentAsItsPart() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        Path trackDir = takeDir.resolve(track.getId());
        Path part = trackDir.resolve("segment-000.wav.part");
        // No rotation and no early seal in this take: the stop's seal is the first rename.
        pipeline.getSession(track).getCurrentWriter().failNextRename();

        List<AudioClip> clips = stopRecording(pipeline);

        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(service.sealReason()).as("fixture: the stop's own seal sealed the take").contains(SealedBy.STOP);
        StopSealFailure failure = pipeline.stopSealFailure().orElseThrow();
        assertThat(failure.failure()).isInstanceOf(UncheckedIOException.class)
                .hasMessage("cannot seal segment " + trackDir.resolve("segment-000.wav"))
                .hasRootCauseMessage("injected rename failure (test seam) on " + part);
        assertThat(failure.everySegmentSealed()).as("the segment did not seal").isFalse();
        assertThat(service.stopSealFailure()).as("the pipeline reports its take's flush service").containsSame(failure);
        assertThat(isDone(pipeline.earlySeal())).as("the seal a stop requests is never an early seal").isFalse();
        TakeManifest manifest = manifestOnDisk(pipeline);
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.WRITE_FAILURE);
        assertThat(part).as("the segment is left as its .part for recovery").isRegularFile();
        assertThat(part.resolveSibling("segment-000.wav")).doesNotExist();
        assertThat(clips).as("the stop still returns the clip it built").singleElement()
                .satisfies(clip -> assertThat(clip.getAudioData()[0]).hasSize(2 * BLOCK_FRAMES));
        assertThat(pipeline.isActive()).isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
    }

    @Test
    void aStopWhoseLaneSealThrowsAnErrorReportsThatErrorAndStillNeverSignalsAnEarlySeal() throws Exception {
        // The lane's stop notification dies with an Error after its segment
        // sealed: the seal writes the manifest and rethrows it to the loop's
        // failure handler, whose re-entry with WRITE_FAILURE is still no
        // early seal.
        RecordingPipeline pipeline = newPipeline(track);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            session.addListener(new StopFaultListener(fault));
            return session;
        });
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);

        assertThat(stopRecording(pipeline)).hasSize(1);

        assertThat(pipeline.stopSealFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.failure()).isSameAs(fault);
            assertThat(failure.everySegmentSealed()).as("the segment sealed before the listener threw").isTrue();
        });
        assertThat(takeDir.resolve(track.getId()).resolve("segment-000.wav")).isRegularFile();
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        assertThat(pipeline.getCaptureFlushService().sealReason()).contains(SealedBy.STOP);
        assertThat(manifestOnDisk(pipeline).sealedBy()).contains(SealedBy.WRITE_FAILURE);
    }

    @Test
    void theFirstThrowableALaneThrewIsTheOneReported() throws Exception {
        // The first lane's rename fails; the second lane's stop notification
        // dies with an Error, which the seal rethrows to the loop — and which
        // the loop then records as the last failure.
        Track second = RampCaptureTestSupport.armedMonoTrack("Vocal 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            if (t == second) {
                session.addListener(new StopFaultListener(fault));
            }
            return session;
        });
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        pipeline.getSession(track).getCurrentWriter().failNextRename();

        assertThat(stopRecording(pipeline)).hasSize(2);

        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(service.lastFailure()).as("fixture: the second lane's Error came last").containsSame(fault);
        assertThat(pipeline.stopSealFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.failure()).isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("cannot seal segment");
            assertThat(failure.everySegmentSealed()).as("the first lane's segment did not seal").isFalse();
        });
        assertThat(isDone(pipeline.earlySeal())).isFalse();
    }

    @Test
    void aTakeSealedEarlyByTheHeadroomFloorHasNoStopSealFailureEvenWhenALaneThrewInThatSeal() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        pipeline.getSession(track).getCurrentWriter().failNextRename();

        freeBytes.set(MIB); // below the floor: the next block seals the take early, and that seal's rename fails
        feedRamp(engine, transport, pipeline, frame, 1, 1);
        assertThat(isDone(pipeline.earlySeal())).as("fixture: the take was sealed early").isTrue();

        assertThat(stopRecording(pipeline)).hasSize(1);

        assertThat(pipeline.earlySeal().toCompletableFuture().join())
                .isEqualTo(new EarlySeal.DiskExhausted(FLOOR, false, false));
        assertThat(manifestOnDisk(pipeline).sealedBy()).as("fixture: a lane threw in the early seal")
                .contains(SealedBy.WRITE_FAILURE);
        assertThat(pipeline.stopSealFailure()).as("the early seal reports it, not the stop").isEmpty();
    }

    @Test
    void aTakeSealedEarlyInTheFinalSweepOfAStopHasNoStopSealFailureEvenWhenALaneThrewInThatSeal()
            throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        // Held between passes: the block below waits in the ring for the stop's final sweep.
        service.setDrainPaused(true);
        pipeline.getSession(track).getCurrentWriter().failNextRename();
        freeBytes.set(MIB);
        feedOne(frame);

        assertThat(stopRecording(pipeline)).hasSize(1);

        assertThat(isDone(pipeline.earlySeal())).as("the final sweep sealed the take early").isTrue();
        assertThat(pipeline.earlySeal().toCompletableFuture().join())
                .isEqualTo(new EarlySeal.DiskExhausted(FLOOR, false, false));
        assertThat(service.sealReason()).contains(SealedBy.DISK_EXHAUSTION);
        assertThat(pipeline.stopSealFailure()).isEmpty();
    }

    @Test
    void aTakeSealedEarlyByAWriteFailureHasNoStopSealFailure() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        pipeline.getSession(track).getCurrentWriter().failNextAppend();
        feedRamp(engine, transport, pipeline, frame, 1, 1);
        assertThat(isDone(pipeline.earlySeal())).as("fixture: the failed append sealed the take early").isTrue();

        assertThat(stopRecording(pipeline)).hasSize(1);

        assertThat(pipeline.earlySeal().toCompletableFuture().join()).isInstanceOf(EarlySeal.WriteFailed.class);
        assertThat(pipeline.stopSealFailure()).isEmpty();
    }

    @Test
    void aFailedStartsRollbackHasNoStopSealFailure() {
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
        assertThat(pipeline.stopSealFailure()).isEmpty();
    }

    @Test
    void aTakeStoppedWithoutASealHasNoStopSealFailure() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        CaptureFlushService service = pipeline.getCaptureFlushService();

        service.stopAndAbandon();
        awaitTermination(service);

        assertThat(service.isTerminated()).isTrue();
        assertThat(service.isSealed()).as("fixture: nothing was sealed").isFalse();
        assertThat(pipeline.stopSealFailure()).isEmpty();
    }

    @Test
    void aPipelineThatWasNeverStartedHasNoStopSealToReport() {
        RecordingPipeline pipeline = newPipeline(track);

        assertThatThrownBy(pipeline::stopSealFailure).isInstanceOf(IllegalStateException.class)
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
