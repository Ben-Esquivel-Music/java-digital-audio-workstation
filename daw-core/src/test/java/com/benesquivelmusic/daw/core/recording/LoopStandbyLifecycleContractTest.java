package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
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
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-opened loop lane — the standby — through the paths that are not
 * the plain wrap (Recording Reliability book §4.6): several armed tracks,
 * each with a standby of its own; a standby whose start fails after its file
 * was created; a rotation that takes over the standby's file, and what the
 * manifest on disk says at that moment; a standby whose empty file cannot be
 * deleted when the take ends, or at a loop wrap; the final sweep of a stop; and the two
 * {@link RecordingSession} seams the hand-over is made of.
 *
 * <p>Every block is fed on the test thread and fenced with
 * {@code awaitFlushed} unless a test says otherwise. A file that "cannot be
 * deleted" is made so portably: the channel the standby's writer holds fails
 * its close ({@link ObservedFileChannel.Journal#failNextCloses}), so the
 * writer's abandon-and-delete fails before the delete and the file stays —
 * with the real channel closed, so nothing keeps the test from cleaning up.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class LoopStandbyLifecycleContractTest {

    private static final String FLUSH_THREAD = CaptureFlushService.THREAD_NAME;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private RecordingPipeline pipeline;
    private List<String> warnings;
    private long nextFrame;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        nextFrame = 0;
    }

    /** A loop region of exactly {@code blocks} blocks, so every {@code blocks}-th block wraps. */
    private void loopOverBlocks(int blocks) {
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocks * BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
    }

    /** Loop-record over a loop far longer than any take here: a standby is kept, and no block wraps. */
    private void loopRecordWithoutAWrap() {
        transport.setLoopRegion(0.0, 64.0);
        transport.setLoopEnabled(true);
        pipeline.setLoopRecord(true);
    }

    private void publishOneBlock(RecordingPipeline target) {
        engine.processBlock(rampBlock(nextFrame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
        nextFrame += BLOCK_FRAMES;
    }

    private void feedOneBlock(RecordingPipeline target) {
        publishOneBlock(target);
        target.awaitFlushed();
    }

    private static List<String> filesIn(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** {@code lane|index|state|frames} of every segment entry {@code manifest} holds for the track, in manifest order. */
    private static List<String> segmentsOf(TakeManifest manifest, Track ofTrack) {
        return manifest.segmentsFor(ofTrack.getId()).stream()
                .map(entry -> entry.lane() + "|" + entry.index() + "|" + entry.state() + "|" + entry.frames())
                .toList();
    }

    private static List<String> manifestSegments(RecordingPipeline of, Track ofTrack) throws IOException {
        return segmentsOf(TakeManifest.read(of.getTakeManifestPath()), ofTrack);
    }

    /** A listener that only hears of new segments. */
    private static RecordingListener onNewSegment(IntConsumer action) {
        return new RecordingListener() {
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
            }

            @Override
            public void onNewSegmentCreated(int segmentIndex) {
                action.accept(segmentIndex);
            }
        };
    }

    // ------------------------------------------------------------------
    // Several armed tracks
    // ------------------------------------------------------------------

    @Test
    void eachOfTwoArmedTracksGetsSwapsAndDiscardsAStandbyOfItsOwn() throws IOException {
        Track second = RampCaptureTestSupport.armedMonoTrack("Guitar");
        RecordingPipeline two = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track, second));
        two.setWarningSink(warnings::add);
        // file@released-blocks of every open, per track directory.
        List<String> opens = new CopyOnWriteArrayList<>();
        two.setChannelOpener(path -> {
            opens.add(path.getParent().getFileName() + "/" + path.getFileName() + "@"
                    + two.getCaptureFlushService().appliedBlocks() + " on " + Thread.currentThread().getName());
            return SegmentWriter.CREATE_NEW_CHANNEL.open(path);
        });
        loopOverBlocks(2);
        two.setLoopRecord(true);

        startRecording(two);
        for (Track armed : List.of(track, second)) {
            assertThat(filesIn(takeDir.resolve(armed.getId()))).as("%s starts with lane 0 and its standby", armed.getName())
                    .containsExactly("segment-000.wav.part", "segment-001.wav.part");
        }
        for (int block = 0; block < 5; block++) { // wraps at blocks 2 and 4
            feedOneBlock(two);
            for (TrackCapture capture : two.getCaptureFlushService().captures()) {
                assertThat(capture.hasStandby()).as("after block %d track %s holds a standby", block, capture.trackName())
                        .isTrue();
            }
        }
        for (Track armed : List.of(track, second)) {
            assertThat(manifestSegments(two, armed)).as("%s while lane 2 records", armed.getName())
                    .containsExactly("0|0|SEALED|1024", "1|1|SEALED|1024", "2|2|STREAMING|-1", "3|3|STREAMING|-1");
        }
        stopRecording(two);

        for (Track armed : List.of(track, second)) {
            String directory = armed.getId();
            assertThat(opens.stream().filter(open -> open.startsWith(directory + "/")).toList())
                    .as("%s: every lane after the first was opened ahead of its wrap, on the flush thread — none "
                            + "by a wrap block (2 and 4) before that block was written", armed.getName())
                    .containsExactly(directory + "/segment-000.wav.part@0 on " + FLUSH_THREAD,
                            directory + "/segment-001.wav.part@0 on " + FLUSH_THREAD,
                            directory + "/segment-002.wav.part@2 on " + FLUSH_THREAD,
                            directory + "/segment-003.wav.part@4 on " + FLUSH_THREAD);
            assertThat(filesIn(takeDir.resolve(directory))).as("%s: the standby no lap reached left no file", armed.getName())
                    .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav");
            assertThat(manifestSegments(two, armed))
                    .containsExactly("0|0|SEALED|1024", "1|1|SEALED|1024", "2|2|SEALED|512");
            assertThat(two.getTakeGroups().get(armed).size()).as("%s: three laps", armed.getName()).isEqualTo(3);
        }
        for (TrackCapture capture : two.getCaptureFlushService().captures()) {
            assertThat(capture.hasStandby()).isFalse();
        }
        assertThat(warnings).isEmpty();
    }

    @Test
    void aCancelledStartRemovesTheStandbyOfEveryArmedTrack() throws IOException {
        Track second = RampCaptureTestSupport.armedMonoTrack("Guitar");
        RecordingPipeline two = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track, second));
        loopOverBlocks(2);
        two.setLoopRecord(true);

        awaitWithinTheGuard(two.prepare(), "the take's readiness");
        for (Track armed : List.of(track, second)) {
            assertThat(filesIn(takeDir.resolve(armed.getId())))
                    .containsExactly("segment-000.wav.part", "segment-001.wav.part");
        }
        awaitWithinTheGuard(two.cancelStart(), "the capture-flush thread's termination after the cancel");

        for (Track armed : List.of(track, second)) {
            assertThat(takeDir.resolve(armed.getId())).as("%s: the directory is gone with both files", armed.getName())
                    .doesNotExist();
        }
    }

    // ------------------------------------------------------------------
    // A standby whose start failed after its file was created
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStandbyWhoseStartFailedWithItsFileCreatedLeavesNoFileNoOpenChannelAndNoEntry(boolean anError)
            throws IOException {
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        List<String> opened = new CopyOnWriteArrayList<>();
        AtomicInteger sessions = new AtomicInteger();
        pipeline.setSessionFactory((armed, directory) -> {
            int ordinal = sessions.getAndIncrement(); // 0: lane 0; 1: the standby of lane 1; 2: the standby of lane 2
            RecordingSession session = new RecordingSession(MONO_16, directory);
            session.setChannelOpener(path -> {
                opened.add(path.getFileName().toString());
                return journal.opener(SegmentWriter.CREATE_NEW_CHANNEL).open(path);
            });
            if (ordinal == 2) {
                // Told of its first segment once the file exists and is listed: the start fails there.
                session.addListener(onNewSegment(index -> {
                    if (anError) {
                        throw new AssertionError("listener error (test)");
                    }
                    throw new IllegalStateException("listener failure (test)");
                }));
            }
            return session;
        });
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        feedOneBlock(pipeline);
        feedOneBlock(pipeline);

        // The wrap: lane 1 is swapped in, and the pre-open of lane 2 after the block fails.
        feedOneBlock(pipeline);

        EarlySeal seal = awaitWithinTheGuard(service.earlySeal(), "the early seal");
        assertThat(seal).as("a start that failed with its file created ends the take as a write failure")
                .isInstanceOf(EarlySeal.WriteFailed.class);
        assertThat(opened).as("fixture: the failed standby had created its file")
                .containsExactly("segment-000.wav.part", "segment-001.wav.part", "segment-002.wav.part");
        TrackCapture capture = service.captures().getFirst();
        assertThat(capture.hasStandby()).isFalse();
        assertThat(filesIn(takeDir.resolve(track.getId())))
                .as("the file the failed start created is gone; the lanes are sealed")
                .containsExactly("segment-000.wav", "segment-001.wav");
        assertThat(journal.events().stream().filter("close"::equals).count())
                .as("every channel that was opened — the failed standby's too — is closed").isEqualTo(3);
        assertThat(manifestSegments(pipeline, track)).as("and its entry is not in the manifest")
                .containsExactly("0|0|SEALED|1024", "1|1|SEALED|512");
        stopRecording(pipeline);
        assertThat(filesIn(takeDir.resolve(track.getId()))).containsExactly("segment-000.wav", "segment-001.wav");
    }

    // ------------------------------------------------------------------
    // A rotation that takes over the standby's file
    // ------------------------------------------------------------------

    @Test
    void theManifestOnDiskNamesTheAdoptedFileAsTheLanesOwnBeforeTheFirstFrameIsAppendedToIt() throws IOException {
        // One and a half blocks per segment: from the second block on, every
        // block finds its segment unable to take it and rotates BEFORE it is
        // appended — into the file the standby held.
        pipeline.setSegmentLimits(Duration.ofHours(1), 3L * BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16 / 2);
        List<String> atTheHandOver = new CopyOnWriteArrayList<>();
        AtomicInteger sessions = new AtomicInteger();
        pipeline.setSessionFactory((armed, directory) -> {
            RecordingSession session = new RecordingSession(MONO_16, directory, Duration.ofHours(1),
                    3L * BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16 / 2);
            if (sessions.getAndIncrement() == 0) {
                // Lane 0's session: told of a new segment after it listed
                // the file and before it appends to it, on the flush thread.
                session.addListener(onNewSegment(index -> {
                    if (index == 0) {
                        return;
                    }
                    try {
                        Path adopted = directory.resolve(String.format("segment-%03d.wav.part", index));
                        atTheHandOver.add(index + ": " + Files.size(adopted) + " bytes, manifest "
                                + segmentsOf(TakeManifest.read(pipeline.getTakeManifestPath()), track));
                    } catch (IOException e) {
                        atTheHandOver.add(index + ": " + e);
                    }
                }));
            }
            return session;
        });
        loopRecordWithoutAWrap();
        startRecording(pipeline);
        assertThat(manifestSegments(pipeline, track)).as("fixture: lane 0 and the standby of lane 1")
                .containsExactly("0|0|STREAMING|-1", "1|1|STREAMING|-1");

        feedOneBlock(pipeline);
        feedOneBlock(pipeline); // rotates into the standby's segment-001
        feedOneBlock(pipeline); // rotates into the next standby's segment-002

        assertThat(atTheHandOver)
                .as("at each hand-over, with the adopted file still holding its 44-byte header only, the manifest "
                        + "on disk already lists that file under lane 0 and no longer under lane 1")
                .containsExactly(
                        "1: 44 bytes, manifest [0|0|SEALED|512, 0|1|STREAMING|-1]",
                        "2: 44 bytes, manifest [0|0|SEALED|512, 0|1|SEALED|512, 0|2|STREAMING|-1]");
        stopRecording(pipeline);
        assertThat(manifestSegments(pipeline, track))
                .containsExactly("0|0|SEALED|512", "0|1|SEALED|512", "0|2|SEALED|512");
        assertThat(filesIn(takeDir.resolve(track.getId())))
                .containsExactly("segment-000.wav", "segment-001.wav", "segment-002.wav");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anAdoptedFilesForceCadenceCountsFromTheRotationThatTookItOver() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        Duration cadence = Duration.ofSeconds(5);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setNanoClock(clock::get);
        pipeline.setForceCadence(cadence);
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        pipeline.setSegmentLimits(Duration.ofHours(1), 3L * BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16 / 2);
        loopRecordWithoutAWrap();
        startRecording(pipeline); // lane 0 and the standby are opened at the clock's start

        // A whole cadence later lane 0 gets its first block, which is forced by that append.
        clock.addAndGet(cadence.toNanos());
        feedOneBlock(pipeline);
        assertThat(journal.forces(false)).as("fixture: the first block was forced on cadence").isEqualTo(1);

        // The second block rotates into the standby's file, open for a whole
        // cadence already: its cadence counts from this rotation.
        feedOneBlock(pipeline);
        assertThat(pipeline.getSession(track).getSegments()).as("fixture: the lane rotated").hasSize(2);
        assertThat(journal.forces(false)).as("the first block in the adopted file is not forced at once")
                .isEqualTo(1);
        stopRecording(pipeline);
    }

    // ------------------------------------------------------------------
    // A standby whose empty file cannot be deleted
    // ------------------------------------------------------------------

    /** Opens through {@code standbys} every file of that name, and plainly every other. */
    private static SegmentWriter.ChannelOpener failingCloseOf(String fileName, ObservedFileChannel.Journal standbys) {
        return path -> path.getFileName().toString().equals(fileName)
                ? standbys.opener(SegmentWriter.CREATE_NEW_CHANNEL).open(path)
                : SegmentWriter.CREATE_NEW_CHANNEL.open(path);
    }

    @Test
    void aStandbyFileThatCannotBeDeletedAtTheStopIsReportedAndIsNotListedAsASegmentOfTheSealedTake()
            throws IOException {
        ObservedFileChannel.Journal standbys = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(failingCloseOf("segment-001.wav.part", standbys));
        loopRecordWithoutAWrap();
        startRecording(pipeline);
        feedOneBlock(pipeline);
        feedOneBlock(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        assertThat(manifestSegments(pipeline, track)).as("fixture: lane 0 and its standby")
                .containsExactly("0|0|STREAMING|-1", "1|1|STREAMING|-1");

        standbys.failNextCloses(1);
        stopRecording(pipeline);

        assertThat(filesIn(takeDir.resolve(track.getId()))).as("fixture: the standby's empty file is still there")
                .containsExactly("segment-000.wav", "segment-001.wav.part");
        assertThat(warnings).as("the file that is left is reported to the user, once").singleElement()
                .satisfies(warning -> assertThat(warning).contains("could not be deleted")
                        .contains("zero-frame .part").contains(track.getName())
                        .contains(takeDir.resolve(track.getId()).toString()));
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).as("every frame of the take is in a sealed segment")
                .isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertThat(segmentsOf(manifest, track))
                .as("a manifest that reads sealed lists no segment in progress: the stray file is no segment")
                .containsExactly("0|0|SEALED|1024");
        TrackCapture capture = service.captures().getFirst();
        assertThat(capture.hasStandby()).isFalse();
        assertThat(capture.hasUnsealedSegment()).as("no lane of the take holds a segment that did not seal").isFalse();
        assertThat(pipeline.stopSealFailure()).as("the seal did not fail").isEmpty();
        assertThat(pipeline.getTakeGroups().get(track).size()).isEqualTo(1);
    }

    @Test
    void anAbortAfterAnEarlySealStillRemovesAStandbyFileTheSealCouldNotDelete() throws IOException {
        ObservedFileChannel.Journal standbys = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(failingCloseOf("segment-001.wav.part", standbys));
        loopRecordWithoutAWrap();
        startRecording(pipeline);
        feedOneBlock(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();

        standbys.failNextCloses(1);
        pipeline.getSession(track).getCurrentWriter().failNextAppend();
        feedOneBlock(pipeline);

        awaitWithinTheGuard(service.earlySeal(), "the early seal");
        assertThat(filesIn(takeDir.resolve(track.getId()))).as("fixture: sealed early, the standby's file left")
                .containsExactly("segment-000.wav", "segment-001.wav.part");
        assertThat(warnings).as("fixture: the write failure and the file that could not be deleted")
                .anyMatch(warning -> warning.contains("could not be deleted"));
        assertThat(manifestSegments(pipeline, track)).containsExactly("0|0|SEALED|512");

        service.requestAbort();
        awaitTermination(service);

        assertThat(takeDir.resolve(track.getId()))
                .as("the discard deleted everything the take created, the standby's file included, and then the "
                        + "track directory the take had created").doesNotExist();
        PipelineLifecycleTestSupport.endTheTake(pipeline);
    }

    @Test
    void aStandbyFileThatCannotBeDeletedAtALoopWrapIsReportedAndTheTakeGoesOnRecordingPastIt() throws IOException {
        // A lap of two blocks, a segment of two: lane 0 rotates into an
        // empty segment just before its wrap, which the seal deletes — so
        // the standby, one index further (segment-002), is discarded at the
        // seam. That discard is the delete that fails here.
        ObservedFileChannel.Journal standbys = new ObservedFileChannel.Journal();
        pipeline.setSegmentLimits(Duration.ofHours(1), 2L * BLOCK_FRAMES * BYTES_PER_FRAME_MONO_16);
        pipeline.setChannelOpener(failingCloseOf("segment-002.wav.part", standbys));
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        feedOneBlock(pipeline);
        feedOneBlock(pipeline);
        assertThat(manifestSegments(pipeline, track))
                .as("fixture: lane 0 rotated into the standby's file, and the standby of lane 1 is one index further")
                .containsExactly("0|0|SEALED|1024", "0|1|STREAMING|-1", "1|2|STREAMING|-1");

        standbys.failNextCloses(1);
        feedOneBlock(pipeline); // the wrap: the standby at index 2 is discarded, and its file stays

        assertThat(service.earlySeal()).as("the take was not sealed early").isNotDone();
        assertThat(warnings).as("the file that is left is reported to the user, once").singleElement()
                .satisfies(warning -> assertThat(warning).contains("could not be deleted").contains("loop wrap")
                        .contains("zero-frame .part").contains(track.getName())
                        .contains(takeDir.resolve(track.getId()).toString()).contains("Recording continues"));
        assertThat(manifestSegments(pipeline, track))
                .as("the manifest on disk no longer lists the file that stayed; lane 1 records past it, and its "
                        + "own standby is further on")
                .containsExactly("0|0|SEALED|1024", "1|3|STREAMING|-1", "2|4|STREAMING|-1");
        assertThat(filesIn(takeDir.resolve(track.getId()))).as("fixture: the standby's empty file is still there")
                .containsExactly("segment-000.wav", "segment-002.wav.part", "segment-003.wav.part",
                        "segment-004.wav.part");

        feedOneBlock(pipeline); // lane 1 rotates
        feedOneBlock(pipeline); // the second wrap: lane 2
        assertThat(warnings).as("the later wrap deleted its standby and said nothing").hasSize(1);
        stopRecording(pipeline);

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).as("the take ran until it was stopped").contains(TakeManifest.SealedBy.STOP);
        assertThat(segmentsOf(manifest, track)).as("every lap after the failed delete is recorded and sealed")
                .containsExactly("0|0|SEALED|1024", "1|3|SEALED|1024", "2|4|SEALED|512");
        assertThat(filesIn(takeDir.resolve(track.getId())))
                .as("the file that could not be deleted is no segment of the take")
                .containsExactly("segment-000.wav", "segment-002.wav.part", "segment-003.wav", "segment-004.wav");
        assertThat(pipeline.stopSealFailure()).as("the seal did not fail").isEmpty();
        assertThat(pipeline.getTakeGroups().get(track).size()).as("three laps").isEqualTo(3);
        assertThat(service.appliedBlocks()).as("every block was recorded").isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // The final sweep
    // ------------------------------------------------------------------

    @Test
    void aWrapAppliedByTheFinalSweepOfAStopSwapsTheStandbyInAndPreparesNoOther() throws IOException {
        List<String> opens = new CopyOnWriteArrayList<>();
        pipeline.setChannelOpener(path -> {
            opens.add(path.getFileName().toString());
            return SegmentWriter.CREATE_NEW_CHANNEL.open(path);
        });
        loopOverBlocks(2);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        feedOneBlock(pipeline);
        feedOneBlock(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();

        // The wrap block is queued while the drain is held, and applied by the stop's final sweep.
        service.setDrainPaused(true);
        publishOneBlock(pipeline);
        stopRecording(pipeline);

        assertThat(service.appliedBlocks()).as("fixture: the sweep applied the wrap block").isEqualTo(3);
        assertThat(opens).as("the sweep opened nothing: the lane was the standby, and no standby follows it")
                .containsExactly("segment-000.wav.part", "segment-001.wav.part");
        assertThat(manifestSegments(pipeline, track)).containsExactly("0|0|SEALED|1024", "1|1|SEALED|512");
        assertThat(filesIn(takeDir.resolve(track.getId()))).containsExactly("segment-000.wav", "segment-001.wav");
        assertThat(warnings).isEmpty();
    }

    // ------------------------------------------------------------------
    // The two session seams of the hand-over
    // ------------------------------------------------------------------

    @Test
    void anEmptySegmentIsSurrenderedOpenAndAdoptedAsTheNextSegmentOfAnotherSessionOfTheDirectory() throws IOException {
        Path directory = takeDir.resolve("lane");
        RecordingSession lane = new RecordingSession(MONO_16, directory);
        lane.start();
        lane.recordAudioData(rampBlock(0), BLOCK_FRAMES);
        RecordingSession standby = new RecordingSession(MONO_16, directory);
        standby.setFirstSegmentIndex(1);
        List<String> stopped = new ArrayList<>();
        standby.addListener(new RecordingListener() {
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
                stopped.add("stopped");
            }

            @Override
            public void onNewSegmentCreated(int segmentIndex) {
            }
        });
        standby.start();
        assertThat(filesIn(directory)).containsExactly("segment-000.wav.part", "segment-001.wav.part");

        assertThatThrownBy(lane::surrenderEmptySegment).as("a segment that holds frames is not surrendered")
                .isInstanceOf(IllegalStateException.class);
        SegmentWriter open = standby.surrenderEmptySegment();

        assertThat(standby.isActive()).as("the session that gave its segment up is over").isFalse();
        assertThat(standby.getSegments()).isEmpty();
        assertThat(stopped).as("and its listeners were told").containsExactly("stopped");
        assertThat(open.frameCount()).isZero();
        assertThat(filesIn(directory)).as("nothing was closed or deleted")
                .containsExactly("segment-000.wav.part", "segment-001.wav.part");
        assertThatThrownBy(standby::surrenderEmptySegment).as("an inactive session has nothing to surrender")
                .isInstanceOf(IllegalStateException.class);

        RecordingSession elsewhere = new RecordingSession(MONO_16, takeDir.resolve("other"));
        elsewhere.start();
        assertThatThrownBy(() -> elsewhere.adoptAsNextSegment(open)).as("a writer at another path is refused")
                .isInstanceOf(IllegalArgumentException.class);
        elsewhere.stop();

        lane.adoptAsNextSegment(open);
        lane.stop(); // seals segment 0; nothing rotated, so the adopted writer was never made current
        open.abandon();
        assertThat(filesIn(directory)).containsExactly("segment-000.wav", "segment-001.wav.part");
    }
}
