package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The peak mirror of a lane holds the frames the lane's session recorded
 * and no others, across every channel the track records (Recording
 * Reliability book §4.5): a block whose append failed before it wrote
 * anything adds nothing to the peaks, so the last snapshot of a take that a
 * write failure ended counts exactly the frames its sealed segments hold,
 * and so does one whose session was paused for a while; and the extreme
 * sample of a frame is its extreme over all routed rows, so a peak that only
 * the right channel of a stereo take carries is in the snapshot.
 *
 * <p>The hand-over never costs the take anything: a lane whose seal fails
 * still hands over its last snapshot; an {@link Error} from the sink ends no
 * take and never stands in for a seal's own failure; and with no sink
 * installed no snapshot is built at all.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class LanePeaksMatchWhatWasRecordedContractTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<CapturePeakSnapshot> snapshots = new CopyOnWriteArrayList<>();

    @Test
    void aBlockWhoseAppendFailedAddsNothingToTheLanesPeaks() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        // Headroom that never warns, whatever the machine's disk holds.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        pipeline.setWarningSink(warnings::add);
        pipeline.setPeakSnapshotSink(snapshots::add);
        startRecording(pipeline);
        int good = 3;
        long frame = feedRamp(engine, transport, pipeline, 0, good, 1);

        pipeline.getSession(track).getCurrentWriter().failNextAppend();
        feedRamp(engine, transport, pipeline, frame, 1, 1); // fails before it writes; the take is sealed early

        long recorded = pipeline.getSession(track).getTotalSamplesRecorded();
        assertThat(recorded).as("fixture: the failed block was not recorded").isEqualTo((long) good * BLOCK_FRAMES);
        assertThat(pipeline.getCaptureFlushService().sealReason()).as("fixture: the failure sealed the take")
                .contains(TakeManifest.SealedBy.WRITE_FAILURE);
        stopRecording(pipeline);

        assertThat(snapshots).as("the lane's peaks were handed over when it was sealed").isNotEmpty();
        assertThat(snapshots.getLast().totalFrames())
                .as("the lane's last snapshot counts the frames its session recorded, not the frames delivered")
                .isEqualTo(recorded);
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot.totalFrames()).isLessThanOrEqualTo(recorded));
    }

    private RecordingPipeline monoPipeline(AudioEngine engine, Transport transport, Track track) {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        // Headroom that never warns, whatever the machine's disk holds.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        pipeline.setWarningSink(warnings::add);
        return pipeline;
    }

    @Test
    void blocksDeliveredWhileTheSessionIsPausedAddNothingToTheLanesPeaks() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = monoPipeline(engine, transport, track);
        pipeline.setPeakSnapshotSink(snapshots::add);
        startRecording(pipeline);
        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        CapturePeakMirror mirror = pipeline.getCaptureFlushService().captures().getFirst().peaks();
        assertThat(mirror.totalFrames()).as("fixture: two blocks recorded").isEqualTo(2L * BLOCK_FRAMES);

        pipeline.getSession(track).pause();
        frame = feedRamp(engine, transport, pipeline, frame, 3, 1);

        assertThat(pipeline.getSession(track).getTotalSamplesRecorded())
                .as("fixture: the paused session recorded none of the three blocks").isEqualTo(2L * BLOCK_FRAMES);
        assertThat(mirror.totalFrames()).as("and the mirror gained none of their frames").isEqualTo(2L * BLOCK_FRAMES);

        pipeline.getSession(track).resume();
        feedRamp(engine, transport, pipeline, frame, 1, 1);
        stopRecording(pipeline);

        assertThat(snapshots.getLast().totalFrames())
                .as("the lane's last snapshot counts the frames the session recorded")
                .isEqualTo(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(3L * BLOCK_FRAMES);
        assertThat(warnings).isEmpty();
    }

    @Test
    void theLanesLastPeaksAreHandedOverEvenWhenItsSealFails() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = monoPipeline(engine, transport, track);
        // A clock that never moves: the first block is published, nothing after it until the seal.
        pipeline.setNanoClock(() -> 1_000_000_000L);
        pipeline.setPeakSnapshotSink(snapshots::add);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 3, 1);
        assertThat(snapshots).as("fixture: only the first block's snapshot so far").hasSize(1);

        pipeline.getSession(track).getCurrentWriter().failNextRename();
        stopRecording(pipeline);

        assertThat(pipeline.stopSealFailure()).as("fixture: the lane's seal failed").isPresent();
        assertThat(snapshots).as("the failed seal still handed over the lane's last snapshot").hasSize(2);
        assertThat(snapshots.getLast().totalFrames()).as("holding every frame the lane's session counted")
                .isEqualTo(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(3L * BLOCK_FRAMES);
    }

    @Test
    void anErrorFromThePeakSinkNeitherEndsAHealthyTakeNorReplacesAFailedSealsOwnFailure() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = monoPipeline(engine, transport, track);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        pipeline.setNanoClock(clock::get);
        AtomicInteger calls = new AtomicInteger();
        pipeline.setPeakSnapshotSink(snapshot -> {
            calls.incrementAndGet();
            throw new AssertionError("sink error (test)");
        });
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long frame = 0;
        for (int block = 0; block < 3; block++) {
            clock.addAndGet(CaptureFlushService.PEAK_PUBLISH_INTERVAL.toNanos());
            frame = feedRamp(engine, transport, pipeline, frame, 1, 1);
        }

        assertThat(calls.get()).as("fixture: the sink was called for every block and threw an Error each time")
                .isEqualTo(3);
        assertThat(service.isSealed()).as("the take is still streaming").isFalse();
        assertThat(service.lastFailure()).isEmpty();
        // The clock stands still now: a sink that throws is rate-limited like one that does not.
        feedRamp(engine, transport, pipeline, frame, 2, 1);
        assertThat(calls.get()).as("an attempt that threw counts as the interval's publication").isEqualTo(3);

        pipeline.getSession(track).getCurrentWriter().failNextRename();
        stopRecording(pipeline);

        assertThat(calls.get()).as("the seal offered the sink the lane's last snapshot").isEqualTo(4);
        assertThat(service.earlySeal().toCompletableFuture()).as("the sink's Error sealed nothing early").isNotDone();
        assertThat(pipeline.stopSealFailure()).as("the stop reports the seal's own failure").isPresent();
        Throwable failure = pipeline.stopSealFailure().orElseThrow().failure();
        assertThat(failure).as("the seal's failure, not the sink's Error").isInstanceOf(UncheckedIOException.class);
        assertThat(warnings).as("only the seal is reported; the sink's Error is no recording problem")
                .singleElement().satisfies(warning -> assertThat(warning).contains("Could not seal"));
    }

    @Test
    void withNoSinkNoSnapshotIsEverTakenOfTheMirror() {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        RecordingPipeline pipeline = monoPipeline(engine, transport, track);
        startRecording(pipeline);
        feedRamp(engine, transport, pipeline, 0, 3, 1);
        CapturePeakMirror mirror = pipeline.getCaptureFlushService().captures().getFirst().peaks();

        assertThat(mirror.totalFrames()).as("fixture: the mirror is fed all the same").isEqualTo(3L * BLOCK_FRAMES);
        assertThat(mirror.hasChangedSinceSnapshot())
                .as("no snapshot was built while the lane recorded: nothing cleared the mirror's mark").isTrue();

        stopRecording(pipeline);

        assertThat(mirror.hasChangedSinceSnapshot())
                .as("nor at the seal: with nobody to hand it to, no snapshot is copied out").isTrue();
        assertThat(warnings).isEmpty();
    }

    @Test
    void theExtremeOfAnyRoutedChannelIsInTheLanesPeaks() {
        int block = 480;
        AudioFormat stereo = new AudioFormat(44_100.0, 2, 24, block);
        AudioEngine engine = new AudioEngine(stereo);
        Transport transport = new Transport();
        Track track = new Track("Keys", TrackType.AUDIO);
        track.setArmed(true);
        track.setInputRouting(new InputRouting(0, 2));
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, stereo, takeDir, List.of(track));
        // Headroom that never warns, whatever the machine's disk holds.
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, warnings::add));
        pipeline.setWarningSink(warnings::add);
        pipeline.setPeakSnapshotSink(snapshots::add);
        startRecording(pipeline);

        // The left channel is quiet throughout; the right one carries the only loud samples.
        float[][] output = new float[2][block];
        int spikeFrame = block + 100;
        int dipFrame = 2 * block + 7;
        for (int b = 0; b < 3; b++) {
            float[][] input = new float[2][block];
            Arrays.fill(input[0], 0.1f);
            Arrays.fill(input[1], -0.2f);
            if (b == 1) {
                input[1][100] = 0.9f;
            }
            if (b == 2) {
                input[1][7] = -0.8f;
            }
            engine.processBlock(input, output, block);
            pipeline.awaitFlushed();
        }
        stopRecording(pipeline);

        assertThat(snapshots).isNotEmpty();
        CapturePeakSnapshot last = snapshots.getLast();
        assertThat(last.totalFrames()).isEqualTo(3L * block);
        assertThat(last.framesPerBucket()).as("fixture: no merge yet").isEqualTo(256L);
        assertThat(last.max(spikeFrame / 256)).as("the right channel's spike at frame %d", spikeFrame).isEqualTo(0.9f);
        assertThat(last.min(dipFrame / 256)).as("the right channel's dip at frame %d", dipFrame).isEqualTo(-0.8f);
        assertThat(last.min(0)).as("a quiet bucket spans both rows: right below, left above").isEqualTo(-0.2f);
        assertThat(last.max(0)).isEqualTo(0.1f);
        assertThat(warnings).isEmpty();
    }
}
