package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.assertClipHoldsRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The take rescue a device loss requests (Recording Reliability book §3.4
 * RESCUED, §5.5 steps 1-2; story 327): {@link RecordingPipeline#requestStop(TakeManifest.SealedBy)}
 * with {@link SealedBy#DEVICE_LOSS} is the ordinary stop applied early —
 * the same one-shot steps, the same seal — whose manifest reads
 * {@code aborted}/{@code device-loss} with every segment sealed and a
 * {@code sealed-at}, whose clips come back from {@code completeStop()} as a
 * stop's would, and which {@link RecordingPipeline#earlySeal()} never
 * completes for. A repeated request keeps the first reason; a lane that
 * throws in the rescue's seal is reported by {@code stopSealFailure()} as
 * in a stop; a final manifest write that fails on every attempt — the
 * lanes sealed, the manifest on disk still the take start's — is reported
 * by {@code finalManifestFailure()} with the reason it meant to carry, for
 * a rescue and for a stop alike (PR #982 review); and the reasons the flush
 * thread raises itself are refused.
 * Every wait is bounded by {@link PipelineLifecycleTestSupport#LIFECYCLE_GUARD}.
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class DeviceLossSealContractTest {

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
    }

    private RecordingPipeline newPipeline() {
        return new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
    }

    private static boolean isDone(CompletionStage<?> stage) {
        return stage.toCompletableFuture().isDone();
    }

    private TakeManifest manifestOnDisk(RecordingPipeline pipeline) throws Exception {
        return TakeManifest.read(pipeline.getTakeManifestPath());
    }

    private static List<AudioClip> rescue(RecordingPipeline pipeline) {
        awaitWithinTheGuard(pipeline.requestStop(SealedBy.DEVICE_LOSS),
                "the capture-flush thread's termination after a device-loss rescue");
        return pipeline.completeStop();
    }

    @Test
    void aDeviceLossRescueSealsEveryLaneAsAbortedByDeviceLossAndReturnsTheClips() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        Instant before = Instant.now();
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        double anchor = pipeline.getRecordingStartBeat();

        List<AudioClip> clips = rescue(pipeline);

        assertThat(clips).hasSize(1);
        assertClipHoldsRamp(clips.getFirst(), 0, 2L * BLOCK_FRAMES);
        assertThat(pipeline.isActive()).isFalse();
        assertThat(pipeline.isFinalizationPending()).isFalse();
        assertThat(track.isRecording()).as("the flags are cleared as a stop clears them").isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(pipeline.getRecordingStartBeat()).as("the anchor outlives the rescue").isEqualTo(anchor);

        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(service.sealReason()).contains(SealedBy.DEVICE_LOSS);
        assertThat(service.isTerminated()).isTrue();
        assertThat(pipeline.stopSealFailure()).as("every lane sealed: nothing to report").isEmpty();
        assertThat(pipeline.finalManifestFailure()).as("the final write landed: nothing to report").isEmpty();
        assertThat(isDone(pipeline.earlySeal())).as("a requested seal is never an early seal").isFalse();

        TakeManifest manifest = manifestOnDisk(pipeline);
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.DEVICE_LOSS);
        assertThat(manifest.sealedAt()).isPresent();
        assertThat(manifest.sealedAt().orElseThrow()).isBetween(before, Instant.now());
        assertThat(manifest.segments()).isNotEmpty().allSatisfy(segment -> {
            assertThat(segment.state()).isEqualTo(SegmentState.SEALED);
            assertThat(segment.resolve(takeDir)).isRegularFile();
            assertThat(SegmentWriter.partPathFor(segment.resolve(takeDir))).doesNotExist();
        });
        assertThat(manifest.segments().stream().mapToLong(SegmentEntry::frames).sum()).isEqualTo(2L * BLOCK_FRAMES);
        try (var files = Files.walk(takeDir)) {
            assertThat(files.filter(SegmentFile::isStreamingName)).as("no .part survives the rescue").isEmpty();
        }
    }

    @Test
    void aStopsSealCarriesSealedAtToo() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        Instant before = Instant.now();
        feedRamp(engine, transport, pipeline, 0, 1, 1);

        assertThat(stopRecording(pipeline)).hasSize(1);

        TakeManifest manifest = manifestOnDisk(pipeline);
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(SealedBy.STOP);
        assertThat(manifest.sealedAt().orElseThrow()).isBetween(before, Instant.now());
    }

    @Test
    void aStopAfterTheRescueKeepsTheRescuesReason() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 1, 1);

        CompletionStage<Void> termination = pipeline.requestStop(SealedBy.DEVICE_LOSS);
        // As Object: AssertJ's CompletionStage assertion would call into the minimal stage.
        assertThat((Object) pipeline.requestStop()).as("the same termination signal again").isSameAs(termination);
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        assertThat(pipeline.completeStop()).hasSize(1);

        assertThat(pipeline.getCaptureFlushService().sealReason()).contains(SealedBy.DEVICE_LOSS);
        assertThat(manifestOnDisk(pipeline).sealedBy()).contains(SealedBy.DEVICE_LOSS);
    }

    @Test
    void aRescueAfterAStopKeepsTheStopsReason() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 1, 1);

        CompletionStage<Void> termination = pipeline.requestStop();
        assertThat((Object) pipeline.requestStop(SealedBy.DEVICE_LOSS)).isSameAs(termination);
        awaitWithinTheGuard(termination, "the capture-flush thread's termination");
        assertThat(pipeline.completeStop()).hasSize(1);

        TakeManifest manifest = manifestOnDisk(pipeline);
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(SealedBy.STOP);
    }

    @Test
    void aLaneThatThrowsInTheRescuesSealIsReportedAsAStopSealFailure() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        Path part = takeDir.resolve(track.getId()).resolve("segment-000.wav.part");
        pipeline.getSession(track).getCurrentWriter().failNextRename();

        List<AudioClip> clips = rescue(pipeline);

        assertThat(clips).as("the partial take is still published").hasSize(1);
        StopSealFailure failure = pipeline.stopSealFailure().orElseThrow();
        assertThat(failure.everySegmentSealed()).isFalse();
        assertThat(failure.failure()).hasRootCauseMessage("injected rename failure (test seam) on " + part);
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        TakeManifest manifest = manifestOnDisk(pipeline);
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).as("a lane threw: write-failure, as for a stop").contains(SealedBy.WRITE_FAILURE);
        assertThat(manifest.sealedAt()).isPresent();
        assertThat(part).as("the lane's streaming file is left for recovery").isRegularFile();
    }

    @Test
    void theReasonsTheFlushThreadRaisesItselfAreRefusedAndTheTakeGoesOn() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 1, 1);

        for (SealedBy reason : List.of(SealedBy.CRASH_RECOVERY, SealedBy.WRITE_FAILURE,
                SealedBy.DISK_EXHAUSTION, SealedBy.START_FAILURE)) {
            assertThatThrownBy(() -> pipeline.requestStop(reason))
                    .as(reason.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(reason.name());
        }
        assertThatThrownBy(() -> pipeline.requestStop(null)).isInstanceOf(NullPointerException.class);
        assertThat(pipeline.isActive()).as("a refused request changes nothing").isTrue();
        assertThat(track.isRecording()).isTrue();

        assertThat(stopRecording(pipeline)).hasSize(1);
        assertThat(manifestOnDisk(pipeline).sealedBy()).contains(SealedBy.STOP);
    }

    /** The flush thread's manifest-write warnings; the disk-headroom watch may warn on a full machine. */
    private static List<String> manifestWarnings(List<String> warnings) {
        return warnings.stream().filter(warning -> warning.contains("take manifest")).toList();
    }

    /**
     * The take's one segment is a sealed {@code .wav} with no {@code .part},
     * under a manifest on disk that is the take start's: still streaming, no
     * reason, no instant, its one entry still streaming.
     */
    private void assertEverySegmentSealedUnderAStreamingManifest(RecordingPipeline pipeline) throws Exception {
        Path wav = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        assertThat(wav).isRegularFile();
        assertThat(SegmentWriter.partPathFor(wav)).doesNotExist();
        TakeManifest onDisk = manifestOnDisk(pipeline);
        assertThat(onDisk.sealStatus()).as("the manifest on disk is the last one written: the take start's")
                .isEqualTo(SealStatus.STREAMING);
        assertThat(onDisk.sealedBy()).isEmpty();
        assertThat(onDisk.sealedAt()).isEmpty();
        assertThat(onDisk.segments()).extracting(SegmentEntry::state).containsExactly(SegmentState.STREAMING);
    }

    @Test
    void aRescueWhoseFinalManifestWriteFailsOnEveryAttemptReportsTheReasonItMeantToCarry() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long writesBeforeTheSeal = service.manifestWrites();
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);

        List<AudioClip> clips = rescue(pipeline);

        assertThat(clips).as("the take is still published").hasSize(1);
        assertClipHoldsRamp(clips.getFirst(), 0, 2L * BLOCK_FRAMES);
        assertThat(service.isTerminated()).isTrue();
        FinalManifestFailure failure = pipeline.finalManifestFailure().orElseThrow();
        assertThat(failure.intendedReason()).as("the manifest was to read aborted/device-loss")
                .isEqualTo(SealedBy.DEVICE_LOSS);
        assertThat(failure.failure()).isInstanceOf(IOException.class);
        assertThat(pipeline.stopSealFailure()).as("every lane sealed: the lanes report nothing").isEmpty();
        assertThat(isDone(pipeline.earlySeal())).isFalse();
        assertThat(service.sealReason()).contains(SealedBy.DEVICE_LOSS);
        assertThat(service.manifestWrites()).as("no write of the seal reached the disk").isEqualTo(writesBeforeTheSeal);
        assertEverySegmentSealedUnderAStreamingManifest(pipeline);
        assertThat(manifestWarnings(warnings)).as("the sink is told once, with no promise of a retry")
                .singleElement().satisfies(warning -> assertThat(warning)
                        .contains("could not be written")
                        .contains("the take has ended")
                        .contains("take recovery rebuilds it from the segments")
                        .doesNotContain("next project open")
                        .doesNotContain("retried")
                        .doesNotContain("recording continues")
                        .doesNotContain("rewritten as soon as"));
    }

    @Test
    void aStopWhoseFinalManifestWriteFailsOnEveryAttemptReportsStopAsTheReasonItMeantToCarry() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        pipeline.getCaptureFlushService().failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);

        assertThat(stopRecording(pipeline)).hasSize(1);

        FinalManifestFailure failure = pipeline.finalManifestFailure().orElseThrow();
        assertThat(failure.intendedReason()).as("the manifest was to read sealed/stop").isEqualTo(SealedBy.STOP);
        assertThat(failure.failure()).isInstanceOf(IOException.class);
        assertThat(pipeline.stopSealFailure()).isEmpty();
        assertThat(pipeline.getCaptureFlushService().sealReason()).contains(SealedBy.STOP);
        assertEverySegmentSealedUnderAStreamingManifest(pipeline);
    }

    @Test
    void aFinalManifestWriteThatLandsOnItsLastAttemptLeavesNoFailureBehind() throws Exception {
        RecordingPipeline pipeline = newPipeline();
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 1, 1);
        pipeline.getCaptureFlushService().failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS - 1);

        assertThat(rescue(pipeline)).hasSize(1);

        assertThat(pipeline.finalManifestFailure()).as("the last attempt of the budget landed").isEmpty();
        assertThat(pipeline.stopSealFailure()).isEmpty();
        TakeManifest manifest = manifestOnDisk(pipeline);
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.DEVICE_LOSS);
        assertThat(manifestWarnings(warnings)).as("refused attempts inside one write's budget are no episode").isEmpty();
    }

    @Test
    void aLaneThatThrowsAndAFinalWriteThatFailsAreBothReportedWithWriteFailureAsTheReasonItMeantToCarry()
            throws Exception {
        RecordingPipeline pipeline = newPipeline();
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 2, 1);
        Path part = takeDir.resolve(track.getId()).resolve("segment-000.wav.part");
        pipeline.getSession(track).getCurrentWriter().failNextRename();
        pipeline.getCaptureFlushService().failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS);

        List<AudioClip> clips = rescue(pipeline);

        assertThat(clips).as("the partial take is still published").hasSize(1);
        StopSealFailure laneFailure = pipeline.stopSealFailure().orElseThrow();
        assertThat(laneFailure.everySegmentSealed()).isFalse();
        FinalManifestFailure manifestFailure = pipeline.finalManifestFailure().orElseThrow();
        assertThat(manifestFailure.intendedReason())
                .as("a lane threw: the manifest was to read aborted/write-failure, not device-loss")
                .isEqualTo(SealedBy.WRITE_FAILURE);
        assertThat(part).as("the lane's streaming file is left for recovery").isRegularFile();
        TakeManifest onDisk = manifestOnDisk(pipeline);
        assertThat(onDisk.sealStatus()).as("the manifest on disk is the take start's").isEqualTo(SealStatus.STREAMING);
        assertThat(onDisk.sealedBy()).isEmpty();
    }

    @Test
    void theSealOutcomesAreRefusedBeforeATakeWasPrepared() {
        RecordingPipeline pipeline = newPipeline();

        assertThatThrownBy(pipeline::finalManifestFailure).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(pipeline::stopSealFailure).isInstanceOf(IllegalStateException.class);
    }
}
