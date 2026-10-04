package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitTermination;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.awaitWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.assertClipHoldsRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.assertDecodedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loop laps are finalized on the {@code capture-flush} thread, into lanes
 * that were opened before the wrap needed them (Recording Reliability book
 * §4.6): at a wrap the flush thread seals the lane, stacks the lap and swaps
 * in a session whose file is already open; it opens nothing between the last
 * block of one lane and the first block of the next. The track directory's
 * segment numbering, the manifest's keys and the files stay what a lane
 * opened at the wrap would have left; the lane no lap reached leaves no file
 * and no manifest entry, whatever ends the take.
 *
 * <p>Every block is fed on the test thread — the thread that runs the audio
 * callback here — and fenced with {@code awaitFlushed}; the opens are
 * observed through the channel-opener seam, each with the number of blocks
 * the flush thread had released when it happened. While block {@code k}
 * (counted from 0) is applied, and until it is released, that number is
 * {@code k}.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class LoopLanePreOpenContractTest {

    private static final String FLUSH_THREAD = CaptureFlushService.THREAD_NAME;

    /** One file open: the file, the thread, and the blocks the flush thread had released by then. */
    private record Open(String file, String thread, long releasedBlocks) {
    }

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private RecordingPipeline pipeline;
    private List<Open> opens;
    private long nextFrame;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        opens = new CopyOnWriteArrayList<>();
        nextFrame = 0;
    }

    /** A loop region of exactly {@code blocks} blocks, so every {@code blocks}-th block wraps. */
    private void loopOverBlocks(int blocks) {
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocks * BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
    }

    /** Opens the real file and journals the open. */
    private java.nio.channels.FileChannel observedOpen(Path path) throws IOException {
        CaptureFlushService service = pipeline.getCaptureFlushService();
        opens.add(new Open(path.getFileName().toString(), Thread.currentThread().getName(),
                service.appliedBlocks()));
        return SegmentWriter.CREATE_NEW_CHANNEL.open(path);
    }

    /** Runs the callback for the next ramp block on this thread and waits for the flush thread to finish with it. */
    private void feedOneBlock() {
        engine.processBlock(rampBlock(nextFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
        nextFrame += BLOCK_FRAMES;
        pipeline.awaitFlushed();
    }

    private Path trackDir() {
        return takeDir.resolve(track.getId());
    }

    private List<String> filesInTheTrackDirectory() throws IOException {
        if (!Files.exists(trackDir())) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(trackDir())) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** The file each segment entry of the manifest on disk stands for, sorted by name. */
    private List<String> filesTheManifestLists() throws IOException {
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        List<String> files = new ArrayList<>();
        for (TakeManifest.SegmentEntry entry : manifest.segmentsFor(track.getId())) {
            Path sealedName = entry.resolve(takeDir);
            Path file = entry.state() == TakeManifest.SegmentState.STREAMING
                    ? SegmentWriter.partPathFor(sealedName)
                    : sealedName;
            files.add(file.getFileName().toString());
        }
        return files.stream().sorted().toList();
    }

    /** {@code lane|index|state|frames} of every segment entry of the manifest on disk, in manifest order. */
    private List<String> manifestSegments() throws IOException {
        return TakeManifest.read(pipeline.getTakeManifestPath()).segmentsFor(track.getId()).stream()
                .map(entry -> entry.lane() + "|" + entry.index() + "|" + entry.state() + "|" + entry.frames())
                .toList();
    }

    @Test
    void everyLaterLaneIsCreatedOpenedAndSealedOnTheFlushThreadAndOpenedBeforeItsWrap() throws IOException {
        String feeder = Thread.currentThread().getName();
        assertThat(feeder).as("fixture: the thread that runs the callback is not the flush thread")
                .isNotEqualTo(FLUSH_THREAD);
        List<String> createdOn = new CopyOnWriteArrayList<>();
        List<String> stoppedOn = new CopyOnWriteArrayList<>();
        pipeline.setSessionFactory((armed, directory) -> {
            createdOn.add(Thread.currentThread().getName());
            RecordingSession session = new RecordingSession(MONO_16, directory, Duration.ofHours(1),
                    RecordingSession.DEFAULT_MAX_SEGMENT_BYTES);
            session.setChannelOpener(this::observedOpen);
            session.addListener(new RecordingListener() {
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
                    stoppedOn.add(Thread.currentThread().getName());
                }

                @Override
                public void onNewSegmentCreated(int segmentIndex) {
                }
            });
            return session;
        });
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);

        startRecording(pipeline);
        for (int block = 0; block < 7; block++) { // wraps at blocks 2, 4 and 6: lanes 0, 1, 2 and the start of 3
            feedOneBlock();
            assertThat(filesTheManifestLists()).as("after block %d the manifest lists exactly the files", block)
                    .isEqualTo(filesInTheTrackDirectory());
        }
        TrackCapture capture = pipeline.getCaptureFlushService().captures().getFirst();
        assertThat(capture.lane()).isEqualTo(3);
        assertThat(capture.hasStandby()).as("lane 4 stands by").isTrue();
        assertThat(manifestSegments()).as("while lane 3 records: three sealed laps, the lane, and the standby")
                .containsExactly("0|0|SEALED|1024", "1|1|SEALED|1024", "2|2|SEALED|1024",
                        "3|3|STREAMING|-1", "4|4|STREAMING|-1");
        stopRecording(pipeline);

        // Who did what: lane 0's session is built by prepare() on the caller
        // thread; every later lane's — the standby no lap reached included —
        // on the flush thread, and nothing of any lane on the feeding thread
        // once capture runs.
        assertThat(createdOn).containsExactly(feeder, FLUSH_THREAD, FLUSH_THREAD, FLUSH_THREAD, FLUSH_THREAD);
        assertThat(stoppedOn).as("three laps sealed at their wraps, the last lane at the stop, the standby discarded")
                .hasSize(5).containsOnly(FLUSH_THREAD).doesNotContain(feeder);
        assertThat(opens).extracting(Open::thread).containsOnly(FLUSH_THREAD);

        // No open on the seam: lane L's first block is block 2L, and its file
        // was opened when fewer blocks than that had been released — by the
        // take's initialisation for lane 1, after the wrap block of the lane
        // before for the others.
        assertThat(opens).extracting(open -> open.file() + "@" + open.releasedBlocks())
                .containsExactly("segment-000.wav.part@0", "segment-001.wav.part@0", "segment-002.wav.part@2",
                        "segment-003.wav.part@4", "segment-004.wav.part@6");
        for (int lane = 1; lane <= 3; lane++) {
            assertThat(opens.get(lane).releasedBlocks()).as("lane %d was open before its wrap block %d", lane, 2 * lane)
                    .isLessThan(2L * lane);
        }

        // What is left: lanes 0-3 sealed under unique (lane, index) keys, and
        // nothing of the standby.
        assertThat(manifestSegments())
                .containsExactly("0|0|SEALED|1024", "1|1|SEALED|1024", "2|2|SEALED|1024", "3|3|SEALED|512");
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertThat(manifest.gaps()).isEmpty();
        assertThat(filesInTheTrackDirectory())
                .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav", "segment-003.wav");
        assertThat(capture.hasStandby()).isFalse();

        // The laps hold the ramp end to end: nothing was lost or repeated at a seam.
        List<Take> takes = pipeline.getTakeGroups().get(track).takes();
        assertThat(takes).hasSize(4);
        long frame = 0;
        for (int lap = 0; lap < 4; lap++) {
            long frames = lap < 3 ? 1024 : 512;
            assertClipHoldsRamp(takes.get(lap).clip(), frame, frames);
            frame += frames;
        }
        assertThat(frame).isEqualTo(nextFrame);
    }

    @Test
    void aLaneThatRotatesKeepsTheNumberingALaneOpenedAtTheWrapWouldHaveLeft() throws IOException {
        // A lap of three blocks, a segment of two: every lane rotates once.
        pipeline.setSegmentLimits(Duration.ofHours(1), 2L * BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16);
        pipeline.setChannelOpener(this::observedOpen);
        loopOverBlocks(3);
        pipeline.setLoopRecord(true);

        startRecording(pipeline);
        for (int block = 0; block < 6; block++) {
            feedOneBlock();
            assertThat(filesTheManifestLists()).as("after block %d the manifest lists exactly the files", block)
                    .isEqualTo(filesInTheTrackDirectory());
            assertThat(manifestSegments()).as("after block %d no (lane, index) is listed twice", block)
                    .extracting(line -> line.substring(0, line.indexOf('|', line.indexOf('|') + 1)))
                    .doesNotHaveDuplicates();
        }
        stopRecording(pipeline);

        assertThat(manifestSegments()).as("indices are contiguous per track and rise with the lane")
                .containsExactly("0|0|SEALED|1024", "0|1|SEALED|512", "1|2|SEALED|1024", "1|3|SEALED|512");
        assertThat(filesInTheTrackDirectory())
                .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav", "segment-003.wav");
        // The rotation in block 1 needed index 1, which the standby held: the
        // lane took over the standby's open file, and a new standby was
        // opened at index 2 after the block. So every file was opened once,
        // and neither a rotation (blocks 1 and 4) nor the wrap (block 3)
        // opened one before its block was written.
        assertThat(opens).extracting(open -> open.file() + "@" + open.releasedBlocks())
                .containsExactly("segment-000.wav.part@0", "segment-001.wav.part@0", "segment-002.wav.part@1",
                        "segment-003.wav.part@3", "segment-004.wav.part@4");
        List<Take> takes = pipeline.getTakeGroups().get(track).takes();
        assertThat(takes).hasSize(2);
        assertThat(takes.get(0).clip().getSourceSegmentPaths()).as("a lap lists its segments in capture order")
                .extracting(path -> Path.of(path).getFileName().toString())
                .containsExactly("segment-000.wav", "segment-001.wav");
        assertClipHoldsRamp(takes.get(0).clip(), 0, 1536);
        assertClipHoldsRamp(takes.get(1).clip(), 1536, 1536);
    }

    @Test
    void aLaneThatEndsOnAnEmptySegmentHasItsSuccessorOpenedAtTheIndexItLeftFree() throws IOException {
        // A lap of two blocks, a segment of two: each lane rotates into an
        // empty segment just before its wrap, which the seal deletes — so
        // the standby, one index further, is not where the next lane belongs.
        pipeline.setSegmentLimits(Duration.ofHours(1), 2L * BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16);
        pipeline.setChannelOpener(this::observedOpen);
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);

        startRecording(pipeline);
        for (int block = 0; block < 5; block++) {
            feedOneBlock();
            assertThat(filesTheManifestLists()).as("after block %d the manifest lists exactly the files", block)
                    .isEqualTo(filesInTheTrackDirectory());
        }
        stopRecording(pipeline);

        assertThat(manifestSegments()).as("no index is skipped")
                .containsExactly("0|0|SEALED|1024", "1|1|SEALED|1024", "2|2|SEALED|512");
        assertThat(filesInTheTrackDirectory())
                .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav");
        List<Take> takes = pipeline.getTakeGroups().get(track).takes();
        assertThat(takes).hasSize(3);
        assertClipHoldsRamp(takes.get(0).clip(), 0, 1024);
        assertClipHoldsRamp(takes.get(1).clip(), 1024, 1024);
        assertClipHoldsRamp(takes.get(2).clip(), 2048, 512);
    }

    @Test
    void aPreOpenedLanesForceCadenceCountsFromTheWrapThatMadeItCurrent() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        Duration cadence = Duration.ofSeconds(5);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setNanoClock(clock::get);
        pipeline.setForceCadence(cadence);
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);
        startRecording(pipeline); // lane 0 and the standby of lane 1 are opened at the clock's start

        // A whole cadence later lane 0 gets its first block, which is forced
        // by that append; its second block, at the same instant, is not due.
        clock.addAndGet(cadence.toNanos());
        feedOneBlock();
        feedOneBlock();
        assertThat(journal.forces(false)).as("fixture: lane 0's first block was forced on cadence").isEqualTo(1);

        // The wrap, with the clock where it was: lane 1's file has been open
        // for a whole cadence, but its cadence counts from this wrap.
        feedOneBlock();
        assertThat(pipeline.getCaptureFlushService().captures().getFirst().lane()).isEqualTo(1);
        assertThat(journal.forces(false)).as("the first block of the pre-opened lane is not forced at once")
                .isEqualTo(1);

        // One cadence after the wrap its bytes are due.
        clock.addAndGet(cadence.toNanos());
        feedOneBlock();
        assertThat(journal.forces(false)).isEqualTo(2);
        stopRecording(pipeline);
    }

    @Test
    void aTakeThatIsNotLoopRecordedOpensNoStandbyEvenWhenTheTransportLoops() throws IOException {
        pipeline.setChannelOpener(this::observedOpen);
        loopOverBlocks(2);

        startRecording(pipeline);
        for (int block = 0; block < 5; block++) {
            feedOneBlock();
            assertThat(pipeline.getCaptureFlushService().captures().getFirst().hasStandby()).isFalse();
        }
        assertThat(manifestSegments()).containsExactly("0|0|STREAMING|-1");
        stopRecording(pipeline);

        assertThat(opens).extracting(Open::file).containsExactly("segment-000.wav.part");
        assertThat(filesInTheTrackDirectory()).containsExactly("segment-000.wav");
        assertThat(manifestSegments()).containsExactly("0|0|SEALED|2560");
    }

    @Test
    void aStandbyThatCouldNotBeOpenedLeavesTheLaneToBeOpenedAtTheWrap() throws IOException {
        AtomicBoolean refused = new AtomicBoolean();
        pipeline.setChannelOpener(path -> {
            if (path.getFileName().toString().equals("segment-001.wav.part") && refused.compareAndSet(false, true)) {
                opens.add(new Open("refused " + path.getFileName(), Thread.currentThread().getName(),
                        pipeline.getCaptureFlushService().appliedBlocks()));
                throw new IOException("injected open failure (test)");
            }
            return observedOpen(path);
        });
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);

        startRecording(pipeline);
        assertThat(filesInTheTrackDirectory()).as("the take started with lane 0 alone")
                .containsExactly("segment-000.wav.part");
        for (int block = 0; block < 5; block++) {
            feedOneBlock();
        }
        stopRecording(pipeline);

        assertThat(opens).extracting(open -> open.file() + "@" + open.releasedBlocks())
                .as("lane 1 is opened by its wrap block; lane 2 is pre-opened again")
                .containsExactly("segment-000.wav.part@0", "refused segment-001.wav.part@0",
                        "segment-001.wav.part@2", "segment-002.wav.part@2", "segment-003.wav.part@4");
        assertThat(warnings).as("nothing was lost, so nobody is warned").isEmpty();
        assertThat(manifestSegments())
                .containsExactly("0|0|SEALED|1024", "1|1|SEALED|1024", "2|2|SEALED|512");
        assertThat(filesInTheTrackDirectory())
                .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav");
        List<Take> takes = pipeline.getTakeGroups().get(track).takes();
        assertClipHoldsRamp(takes.get(0).clip(), 0, 1024);
        assertClipHoldsRamp(takes.get(1).clip(), 1024, 1024);
        assertClipHoldsRamp(takes.get(2).clip(), 2048, 512);
    }

    @Test
    void aTakeSealedEarlyInTheMiddleOfALapLeavesNothingOfTheStandby() throws IOException {
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);
        pipeline.setWarningSink(message -> { });
        startRecording(pipeline);
        for (int block = 0; block < 3; block++) { // lane 0 complete, one block in lane 1
            feedOneBlock();
        }
        assertThat(filesInTheTrackDirectory())
                .containsExactly("segment-000.wav", "segment-001.wav.part", "segment-002.wav.part");
        CaptureFlushService service = pipeline.getCaptureFlushService();

        pipeline.getSession(track).getCurrentWriter().failNextAppend();
        feedOneBlock();

        awaitWithinTheGuard(service.earlySeal(), "the early seal");
        assertThat(service.captures().getFirst().hasStandby()).isFalse();
        assertThat(filesInTheTrackDirectory()).containsExactly("segment-000.wav", "segment-001.wav");
        assertThat(manifestSegments()).containsExactly("0|0|SEALED|1024", "1|1|SEALED|512");
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        stopRecording(pipeline);
        assertThat(filesInTheTrackDirectory()).containsExactly("segment-000.wav", "segment-001.wav");
    }

    @Test
    void aStartThatIsCancelledDeletesTheStandbyWithTheRestOfTheTake() throws IOException {
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);

        awaitWithinTheGuard(pipeline.prepare(), "the take's readiness");
        assertThat(filesInTheTrackDirectory()).as("the take is ready with lane 0 and its standby open")
                .containsExactly("segment-000.wav.part", "segment-001.wav.part");
        assertThat(manifestSegments()).containsExactly("0|0|STREAMING|-1", "1|1|STREAMING|-1");

        awaitWithinTheGuard(pipeline.cancelStart(), "the capture-flush thread's termination after the cancel");

        assertThat(trackDir()).as("the track directory the take created is gone with both files").doesNotExist();
        assertThat(pipeline.getTakeManifestPath()).doesNotExist();
    }

    @Test
    void aTakeAbandonedInTheMiddleOfALapLeavesATreeTheReadersAccept() throws IOException {
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        for (int block = 0; block < 3; block++) { // lane 0 complete, one block in lane 1, lane 2 standing by
            feedOneBlock();
        }
        CaptureFlushService service = pipeline.getCaptureFlushService();

        // No final sweep, no seal, no discard: every writer is closed as it is.
        service.stopAndAbandon();
        awaitTermination(service);

        assertThat(filesInTheTrackDirectory())
                .containsExactly("segment-000.wav", "segment-001.wav.part", "segment-002.wav.part");
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.STREAMING);
        assertThat(manifestSegments())
                .containsExactly("0|0|SEALED|1024", "1|1|STREAMING|-1", "2|2|STREAMING|-1");
        assertThat(filesTheManifestLists()).isEqualTo(filesInTheTrackDirectory());

        // The §3.4 grammar reads every listed file, the empty standby included.
        List<Path> inManifestOrder = new ArrayList<>();
        List<Long> frames = new ArrayList<>();
        for (TakeManifest.SegmentEntry entry : manifest.segmentsFor(track.getId())) {
            Path sealedName = entry.resolve(takeDir);
            Path file = entry.state() == TakeManifest.SegmentState.STREAMING
                    ? SegmentWriter.partPathFor(sealedName)
                    : sealedName;
            SegmentFile.Description description = SegmentFile.describe(file);
            assertThat(description.sealed()).isEqualTo(entry.state() == TakeManifest.SegmentState.SEALED);
            assertThat(description.channels()).isEqualTo(1);
            assertThat(SegmentFile.readFrames(file)[0]).hasSize((int) description.frameCount());
            inManifestOrder.add(file);
            frames.add(description.frameCount());
        }
        assertThat(frames).as("the standby is a readable segment of no frames").containsExactly(1024L, 512L, 0L);
        assertThat(Files.size(inManifestOrder.get(2))).isEqualTo(SegmentWriter.DATA_OFFSET);
        assertDecodedRamp(SegmentFile.readFrames(inManifestOrder)[0], 0, 1536);

        // The abandoned take is over for the pipeline too.
        pipeline.requestStop();
        pipeline.completeStop();
    }
}
