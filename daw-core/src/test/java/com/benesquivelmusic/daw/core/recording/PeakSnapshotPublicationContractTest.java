package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.assertClipHoldsRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the peak mirror of a lane being captured reaches its sink (Recording
 * Reliability book §4.5): as immutable snapshots handed over on the
 * {@code capture-flush} thread — never on the thread that ran the audio
 * callback — at most once per {@link CaptureFlushService#PEAK_PUBLISH_INTERVAL}
 * of the take's clock while the lane gains frames, once more when the lane
 * is finalized, holding exactly the frames the lane's session recorded, and
 * starting again from frame 0 in every loop lane. A sink that throws never
 * ends the take.
 *
 * <p>The take's clock is injected and only this test moves it; every block
 * is fenced with {@code awaitFlushed}, so each assertion is made after the
 * flush thread has finished with the block.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class PeakSnapshotPublicationContractTest {

    private static final long INTERVAL_NANOS = CaptureFlushService.PEAK_PUBLISH_INTERVAL.toNanos();

    /** One sink call: what was handed over, and on which thread. */
    private record Delivery(CapturePeakSnapshot snapshot, String thread) {
    }

    @TempDir
    Path tempDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private AtomicLong clock;
    private List<Delivery> deliveries;
    private long nextFrame;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        clock = new AtomicLong(1_000_000_000L);
        deliveries = new CopyOnWriteArrayList<>();
        nextFrame = 0;
    }

    private RecordingPipeline pipelineWithRecordingSink() {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, tempDir, List.of(track));
        pipeline.setNanoClock(clock::get);
        pipeline.setPeakSnapshotSink(
                snapshot -> deliveries.add(new Delivery(snapshot, Thread.currentThread().getName())));
        return pipeline;
    }

    /** Runs the callback for one ramp block on this thread and waits for the flush thread to finish with it. */
    private void feedOneBlock(RecordingPipeline pipeline) {
        engine.processBlock(rampBlock(nextFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
        nextFrame += BLOCK_FRAMES;
        pipeline.awaitFlushed();
    }

    private List<Long> totals() {
        return deliveries.stream().map(delivery -> delivery.snapshot().totalFrames()).toList();
    }

    @Test
    void snapshotsAreHandedOverAtMostOncePerIntervalOfTheTakesClockAndOnceMoreAtTheSeal() {
        RecordingPipeline pipeline = pipelineWithRecordingSink();
        startRecording(pipeline);
        assertThat(deliveries).as("nothing recorded, nothing published").isEmpty();

        feedOneBlock(pipeline);
        assertThat(totals()).as("the first block is published at once").containsExactly(512L);

        feedOneBlock(pipeline);
        feedOneBlock(pipeline);
        assertThat(totals()).as("the clock has not moved: no second snapshot").containsExactly(512L);

        clock.addAndGet(INTERVAL_NANOS - 1);
        feedOneBlock(pipeline);
        assertThat(totals()).as("one nanosecond short of the interval").containsExactly(512L);

        clock.addAndGet(1);
        feedOneBlock(pipeline);
        assertThat(totals()).as("the interval has elapsed: the block that finds it so is published")
                .containsExactly(512L, 5 * 512L);

        clock.addAndGet(10 * INTERVAL_NANOS);
        pipeline.awaitFlushed();
        assertThat(totals()).as("time alone publishes nothing: the lane gained no frames")
                .containsExactly(512L, 5 * 512L);

        feedOneBlock(pipeline);
        assertThat(totals()).containsExactly(512L, 5 * 512L, 6 * 512L);
        feedOneBlock(pipeline);
        assertThat(totals()).as("the interval counts from the last publication").hasSize(3);

        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(totals()).as("the seal hands over the lane's last snapshot without waiting for the interval")
                .containsExactly(512L, 5 * 512L, 6 * 512L, 7 * 512L);
        CapturePeakSnapshot last = deliveries.getLast().snapshot();
        assertThat(last.totalFrames()).as("the mirror holds the frames the take holds")
                .isEqualTo(pipeline.getSession(track).getTotalSamplesRecorded())
                .isEqualTo(clips.getFirst().getSourceRateMetadata().framesPerChannel());
        assertThat(last.trackId()).isEqualTo(track.getId());
        assertThat(last.laneIndex()).isZero();
        assertThat(last.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(last.framesPerBucket()).isEqualTo(CapturePeakMirror.INITIAL_FRAMES_PER_BUCKET);
        assertThat(last.bucketCount()).isEqualTo(7 * 512 / CapturePeakMirror.INITIAL_FRAMES_PER_BUCKET);
        for (int bucket = 0; bucket < last.bucketCount(); bucket++) {
            // The ramp rises through these frames: a bucket's extremes are its first and last frame.
            long first = (long) bucket * last.framesPerBucket();
            assertThat(last.min(bucket)).as("minimum of bucket %d", bucket).isEqualTo(rampValue(first));
            assertThat(last.max(bucket)).as("maximum of bucket %d", bucket)
                    .isEqualTo(rampValue(first + last.framesPerBucket() - 1));
        }
        assertClipHoldsRamp(clips.getFirst(), 0, 7 * 512L);
    }

    @Test
    void everySnapshotIsHandedOverOnTheFlushThreadAndNeverOnTheThreadThatRanTheCallback() {
        String callbackThread = Thread.currentThread().getName();
        assertThat(callbackThread).as("fixture: this thread runs the callback and is not the flush thread")
                .isNotEqualTo(CaptureFlushService.THREAD_NAME);
        RecordingPipeline pipeline = pipelineWithRecordingSink();
        startRecording(pipeline);

        for (int block = 0; block < 6; block++) {
            clock.addAndGet(INTERVAL_NANOS);
            feedOneBlock(pipeline);
        }
        stopRecording(pipeline);

        assertThat(deliveries).as("fixture: one snapshot per block, and the seal's").hasSize(7);
        assertThat(deliveries).extracting(Delivery::thread)
                .containsOnly(CaptureFlushService.THREAD_NAME)
                .containsOnly("capture-flush")
                .doesNotContain(callbackThread);
    }

    @Test
    void aSinkThatThrowsNeverEndsTheTake() {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, tempDir, List.of(track));
        pipeline.setNanoClock(clock::get);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        AtomicInteger calls = new AtomicInteger();
        pipeline.setPeakSnapshotSink(snapshot -> {
            calls.incrementAndGet();
            throw new IllegalStateException("sink failure (test)");
        });
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();

        for (int block = 0; block < 4; block++) {
            clock.addAndGet(INTERVAL_NANOS);
            feedOneBlock(pipeline);
        }
        assertThat(service.isSealed()).as("the take is still streaming").isFalse();
        List<AudioClip> clips = stopRecording(pipeline);

        assertThat(calls.get()).as("the sink was called for every block and for the seal, and threw each time")
                .isEqualTo(5);
        assertThat(service.earlySeal().toCompletableFuture()).as("no early seal").isNotDone();
        assertThat(service.lastFailure()).isEmpty();
        assertThat(service.stopSealFailure()).isEmpty();
        assertThat(warnings).as("a peak sink that throws is not a recording problem").isEmpty();
        TakeManifest manifest = service.lastManifest().orElseThrow();
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertClipHoldsRamp(clips.getFirst(), 0, 4 * 512L);
    }

    @Test
    void theMirrorHoldsExactlyTheFramesTheSessionRecordedWhenAPunchRegionGatesTheTake() {
        // Punch in at frame 700, out at frame 1800: of four 512-frame blocks
        // the take holds 1100 frames.
        transport.setPunchRegion(new PunchRegion(700L, 1800L, true));
        RecordingPipeline pipeline = pipelineWithRecordingSink();
        startRecording(pipeline);

        for (int block = 0; block < 4; block++) {
            clock.addAndGet(INTERVAL_NANOS);
            feedOneBlock(pipeline);
        }
        List<AudioClip> clips = stopRecording(pipeline);

        long recorded = pipeline.getSession(track).getTotalSamplesRecorded();
        assertThat(recorded).as("fixture: the punch region kept 1100 of 2048 frames").isEqualTo(1100L);
        assertThat(totals()).as("each snapshot counts the frames recorded so far, not the frames delivered")
                .containsExactly(1024L - 700L, 1536L - 700L, 1100L, 1100L);
        assertThat(clips.getFirst().getSourceRateMetadata().framesPerChannel()).isEqualTo(recorded);
    }

    @Test
    void everyLoopLaneStartsItsMirrorAgainAndIsHandedOverOnceMoreWhenItsLapEnds() {
        // A loop of exactly two blocks.
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, 2 * BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        RecordingPipeline pipeline = pipelineWithRecordingSink();
        pipeline.setLoopRecord(true);
        startRecording(pipeline);

        for (int block = 0; block < 5; block++) { // lane 0: blocks 0-1; lane 1: blocks 2-3; lane 2: block 4
            clock.addAndGet(INTERVAL_NANOS);
            feedOneBlock(pipeline);
        }
        stopRecording(pipeline);

        assertThat(deliveries).extracting(delivery -> delivery.snapshot().laneIndex() + ":"
                        + delivery.snapshot().totalFrames())
                .as("lane:frames of every snapshot, in order — a lane's last one comes before the next lane's first")
                .containsExactly("0:512", "0:1024", "0:1024", "1:512", "1:1024", "1:1024", "2:512", "2:512");
        CapturePeakSnapshot firstOfLaneOne = deliveries.get(3).snapshot();
        assertThat(firstOfLaneOne.bucketCount()).isEqualTo(2);
        assertThat(firstOfLaneOne.min(0)).as("lane 1 begins with the frame after lane 0's last")
                .isEqualTo(rampValue(1024));
        assertThat(pipeline.getCaptureFlushService().captures().getFirst().peaks().backingArray())
                .as("one mirror per track, the same array through every lane").hasSize(2 * CapturePeakMirror.BUCKETS);
    }

    @Test
    void theSinkIsRefusedWhileATakeIsBeingRecordedAndMustNotBeNull() {
        RecordingPipeline pipeline = pipelineWithRecordingSink();
        assertThatThrownBy(() -> pipeline.setPeakSnapshotSink(null)).isInstanceOf(NullPointerException.class);
        startRecording(pipeline);

        assertThatThrownBy(() -> pipeline.setPeakSnapshotSink(snapshot -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peak snapshot sink");

        stopRecording(pipeline);
        pipeline.setPeakSnapshotSink(snapshot -> { });
    }
}
