package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.outcomeWithinTheGuard;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link RecordingPipeline} × {@link CaptureFlushService} wiring (story 323;
 * context D9/D11): the {@code awaitFlushed} fence, ring overflow and
 * truncation bookkeeping, all-or-nothing start (and what a failed restart
 * must leave alone), the loop-wrap seal failure, manifest write failures
 * that must not end a take, idempotent stop, and the thread's identity.
 */
class RecordingPipelineFlushServiceTest {

    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    /** What the fixed-probe headroom watch of {@link #withFixedHeadroom} said; it has nothing to say. */
    private final List<String> headroomWarnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
    }

    @AfterEach
    void theFixedProbeNeverCrossedAThreshold() {
        assertThat(headroomWarnings).as("fixture: 10 GiB free is above the low-water mark").isEmpty();
    }

    private RecordingPipeline newPipeline(Track... tracks) {
        return withFixedHeadroom(new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(tracks)));
    }

    /**
     * Replaces the pipeline's default disk-headroom watch — which probes the
     * real file store of the take directory — with one that always reads
     * 10 GiB free, so the exact warning counts asserted in this class do not
     * depend on how much disk the machine running them has left (below
     * 1 GiB the real watch adds a "Disk headroom low" warning).
     */
    private RecordingPipeline withFixedHeadroom(RecordingPipeline pipeline) {
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64 * MIB,
                Duration.ZERO, System::nanoTime, headroomWarnings::add));
        return pipeline;
    }

    private void feedOne(long frame) {
        engine.processBlock(rampBlock(frame), new float[1][BLOCK_FRAMES], BLOCK_FRAMES);
        advanceOneBlock(transport);
    }

    @Test
    void awaitFlushedReturnsOnlyAfterEveryPublishedBlockIsApplied() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setRingSlots(2048);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        CaptureRing ring = pipeline.getCaptureRing();
        int blocks = 1_000;

        // The observation loop below ends when the producer does. The flag is
        // cleared in a finally and the loop has a deadline of its own, so a
        // producer that dies (the callback throws out of processBlock) or
        // stalls fails this test instead of spinning the JUnit thread forever.
        AtomicBoolean producing = new AtomicBoolean(true);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = Thread.ofPlatform().name("story323-producer").daemon(true).start(() -> {
            try {
                float[][] output = new float[1][BLOCK_FRAMES];
                for (int b = 0; b < blocks; b++) {
                    engine.processBlock(rampBlock((long) b * BLOCK_FRAMES), output, BLOCK_FRAMES);
                }
            } catch (Throwable thrown) {
                producerFailure.set(thrown);
            } finally {
                producing.set(false);
            }
        });
        long deadline = System.nanoTime() + RampCaptureTestSupport.HANG_GUARD.toNanos();
        long observations = 0;
        do {
            long applied = service.appliedBlocks();
            long published = ring.publishedBlocks();
            assertThat(applied).as("applied never runs ahead of published").isLessThanOrEqualTo(published);
            observations++;
            if (System.nanoTime() - deadline >= 0) {
                fail("the producer was still feeding after " + RampCaptureTestSupport.HANG_GUARD
                        + ": " + published + " of " + blocks + " block(s) published");
            }
        } while (producing.get());
        producer.join(10_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(producerFailure.get()).as("the producer fed every block").isNull();
        assertThat(observations).isPositive();

        pipeline.awaitFlushed();

        assertThat(ring.publishedBlocks()).isEqualTo(blocks);
        assertThat(service.appliedBlocks()).isEqualTo(blocks);
        assertThat(pipeline.getOverflowCount()).isZero();
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo((long) blocks * BLOCK_FRAMES);
        assertThat(pipeline.getSession(track).getCurrentWriter().frameCount()).isEqualTo((long) blocks * BLOCK_FRAMES);
        pipeline.stop();
    }

    @Test
    void awaitFlushedTimesOutWhileDrainingIsPausedAndReturnsOnceItResumes() throws InterruptedException {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setRingSlots(64);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        service.setDrainPaused(true);
        for (int b = 0; b < 10; b++) {
            feedOne((long) b * BLOCK_FRAMES);
        }
        assertThat(service.appliedBlocks()).isZero();

        // Nothing drains, so only the fence's own timeout ends this wait; it
        // runs off the JUnit thread so that a fence that lost its timeout
        // fails here instead of waiting forever.
        Throwable timedOut = outcomeWithinTheGuard("story323-await-timeout",
                () -> pipeline.awaitFlushed(Duration.ofMillis(200)));
        assertThat(timedOut)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("10 published");

        service.setDrainPaused(false);
        pipeline.awaitFlushed();
        assertThat(service.appliedBlocks()).isEqualTo(10);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(10L * BLOCK_FRAMES);
        pipeline.stop();
    }

    /** One applied block as the flush thread's {@link CaptureFlushService.BlockObserver} saw it. */
    private record Applied(long sequence, long startFrame, int numFrames) {
        long endFrame() {
            return startFrame + numFrames;
        }
    }

    @Test
    void ringOverflowIsCountedAndRecordedAsAGapLine() throws Exception {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setRingSlots(8);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        CaptureRing ring = pipeline.getCaptureRing();
        assertThat(ring.capacity()).isEqualTo(8);

        // The interleaving a busy take produces: the ring fills (blocks 0..7),
        // five blocks are dropped (8..12), the flush thread frees ONE slot, the
        // callback publishes block 13 into it, and the same drain pass then
        // applies 1..7 and 13 before it looks at the overflow counter. The
        // observer holds the flush thread after its first release until the
        // producer (this thread, always) has published that block.
        List<Applied> applied = new CopyOnWriteArrayList<>();
        CountDownLatch firstSlotFreed = new CountDownLatch(1);
        CountDownLatch lateBlockPublished = new CountDownLatch(1);
        AtomicBoolean heldLongEnough = new AtomicBoolean(true);
        service.setBlockObserver((sequence, startFrame, numFrames) -> {
            applied.add(new Applied(sequence, startFrame, numFrames));
            if (sequence == 0) {
                firstSlotFreed.countDown();
                try {
                    heldLongEnough.set(lateBlockPublished.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    heldLongEnough.set(false);
                }
            }
        });
        service.setDrainPaused(true);
        int offered = 13;
        for (int b = 0; b < offered; b++) {
            feedOne((long) b * BLOCK_FRAMES);
        }
        assertThat(pipeline.getOverflowCount()).as("13 offered into 8 slots").isEqualTo(5);
        assertThat(ring.publishedBlocks()).isEqualTo(8);
        assertThat(ring.droppedAfterSequence()).isEqualTo(7);

        service.setDrainPaused(false);
        assertThat(firstSlotFreed.await(10, TimeUnit.SECONDS)).as("the flush thread released block 0").isTrue();
        feedOne((long) offered * BLOCK_FRAMES); // block 13, ring sequence 8
        assertThat(ring.publishedBlocks()).as("the freed slot took the late block").isEqualTo(9);
        lateBlockPublished.countDown();
        pipeline.awaitFlushed();
        assertThat(heldLongEnough).as("the observer saw the late block published in time").isTrue();

        // In-order drain, and the stream is contiguous except at the one hole.
        assertThat(applied).extracting(Applied::sequence).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        Applied lastBeforeTheDrop = applied.stream()
                .filter(a -> a.sequence() == ring.droppedAfterSequence())
                .findFirst().orElseThrow();
        assertThat(lastBeforeTheDrop.endFrame()).isEqualTo(8L * BLOCK_FRAMES);
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.overflowBlocks()).isEqualTo(5);
        assertThat(manifest.gaps()).hasSize(1);
        TakeManifest.GapEntry gap = manifest.gaps().getFirst();
        assertThat(gap.trackId()).isEqualTo(TakeManifest.GapEntry.ALL_TRACKS);
        assertThat(gap.droppedBlocks()).isEqualTo(5);
        assertThat(gap.startFrame())
                .as("the gap is stamped at the end of the last block published before the drop, "
                        + "not at the end of the late block applied after it")
                .isEqualTo(lastBeforeTheDrop.endFrame());
        int holes = 0;
        for (int i = 1; i < applied.size(); i++) {
            Applied previous = applied.get(i - 1);
            Applied current = applied.get(i);
            assertThat(current.endFrame()).as("end frames strictly increase at block %d", i)
                    .isGreaterThan(previous.endFrame());
            if (current.startFrame() != previous.endFrame()) {
                holes++;
                assertThat(previous.endFrame()).as("the only hole is the recorded gap").isEqualTo(gap.startFrame());
                assertThat(current.startFrame() - previous.endFrame())
                        .as("the hole is exactly the dropped blocks wide")
                        .isEqualTo(gap.droppedBlocks() * BLOCK_FRAMES);
            }
        }
        assertThat(holes).as("exactly one discontinuity in the applied stream").isEqualTo(1);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(9L * BLOCK_FRAMES);
        assertThat(warnings).as("one warning for the one noticed episode").singleElement()
                .satisfies(w -> assertThat(w).contains("overflow").contains("5 block(s)"));

        service.setBlockObserver(null);
        pipeline.stop();
        String text = Files.readString(pipeline.getTakeManifestPath(), StandardCharsets.UTF_8);
        assertThat(text).contains("gap=*|" + (8L * BLOCK_FRAMES) + "|5").contains("overflow-blocks=5");
    }

    /** Collects every record given to the logger it is added to; published from whichever thread logs. */
    private static final class CollectingHandler extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<LogRecord> records() {
            return records;
        }
    }

    @Test
    void aWarningSinkThatThrowsOnARingOverflowNeverEndsTheTake() throws IOException {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setRingSlots(8);
        List<String> offered = new CopyOnWriteArrayList<>();
        IllegalStateException sinkFailure = new IllegalStateException("injected sink failure");
        pipeline.setWarningSink(message -> {
            offered.add(message);
            throw sinkFailure;
        });
        Logger flushLogger = Logger.getLogger(CaptureFlushService.class.getName());
        CollectingHandler logged = new CollectingHandler();
        flushLogger.addHandler(logged);
        try {
            pipeline.start();
            CaptureFlushService service = pipeline.getCaptureFlushService();
            service.setDrainPaused(true);
            for (int b = 0; b < 9; b++) { // eight fill the ring, the ninth is dropped
                feedOne((long) b * BLOCK_FRAMES);
            }
            assertThat(pipeline.getOverflowCount()).as("fixture: one block was dropped").isEqualTo(1);
            service.setDrainPaused(false);
            pipeline.awaitFlushed();
            feedOne(9L * BLOCK_FRAMES);   // capture goes on after the warning the sink threw on
            pipeline.awaitFlushed();

            assertThat(service.isRunning()).as("the flush thread outlived the sink's exception").isTrue();
            assertThat(service.isSealed()).as("a warning that could not be delivered does not end the take").isFalse();
            assertThat(service.lastFailure()).as("the sink's exception is not a capture failure").isEmpty();
            assertThat(offered).as("fixture: the sink was offered the overflow warning, once, and threw")
                    .singleElement()
                    .satisfies(w -> assertThat(w).contains("overflow").contains("1 block(s)"));
            assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(9L * BLOCK_FRAMES);
            TakeManifest streaming = TakeManifest.read(pipeline.getTakeManifestPath());
            assertThat(streaming.overflowBlocks()).as("the loss is recorded all the same").isEqualTo(1);
            assertThat(streaming.gaps()).containsExactly(
                    new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 8L * BLOCK_FRAMES, 1));

            assertThat(pipeline.stop()).hasSize(1);

            TakeManifest sealed = TakeManifest.read(pipeline.getTakeManifestPath());
            assertThat(sealed.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
            assertThat(sealed.sealedBy()).contains(TakeManifest.SealedBy.STOP);
            assertThat(offered).as("nothing else was reported").hasSize(1);
            assertThat(logged.records())
                    .filteredOn(record -> record.getMessage().contains("(warning sink threw)"))
                    .as("the warning the sink threw on is in the flush service's log, once")
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                        assertThat(record.getMessage()).contains("overflow").contains("1 block(s)");
                        assertThat(record.getThrown()).isSameAs(sinkFailure);
                    });
        } finally {
            flushLogger.removeHandler(logged);
        }
    }

    @Test
    void aFailedSecondStartLeavesThePreviousTakeUntouched() throws IOException {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        feedOne(0);
        feedOne(BLOCK_FRAMES);
        pipeline.awaitFlushed();
        List<AudioClip> clips = pipeline.stop();
        assertThat(clips).hasSize(1);
        Path manifestPath = pipeline.getTakeManifestPath();
        Path sealed = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        assertThat(sealed).as("fixture: the first take sealed a segment").exists();
        byte[] manifestBefore = Files.readAllBytes(manifestPath);
        byte[] sealedBefore = Files.readAllBytes(sealed);
        List<Path> treeBefore = tree(takeDir);

        // The second start fails after the per-take state was reset and
        // before its own flush service exists.
        pipeline.setSessionFactory((t, dir) -> {
            throw new IllegalStateException("injected factory failure");
        });
        assertThatThrownBy(pipeline::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected factory failure");

        assertThat(pipeline.isActive()).isFalse();
        assertThat(track.isRecording()).isFalse();
        assertThat(engine.getRecordingCallback()).isNull();
        assertThat(sealed).as("the first take's sealed segment survives").exists();
        assertThat(Files.readAllBytes(sealed)).isEqualTo(sealedBefore);
        assertThat(manifestPath).as("the first take's manifest survives").exists();
        assertThat(Files.readAllBytes(manifestPath)).isEqualTo(manifestBefore);
        assertThat(tree(takeDir)).as("the failed attempt left nothing and removed nothing").isEqualTo(treeBefore);
        assertThat(clips.getFirst().getSourceSegmentPaths())
                .allSatisfy(path -> assertThat(Path.of(path)).exists());
        assertThat(pipeline.getCaptureFlushService())
                .as("the previous take's service is no longer reachable for a rollback").isNull();
    }

    @Test
    void aRestartThatCollidesWithThePreviousTakesSegmentsLeavesThatTakeUntouched() throws IOException {
        // The same pipeline, the same take directory, no fault seam: the
        // second start runs into segment-000.wav of the first take inside
        // its own flush service's start, and that service's rollback must
        // not delete a manifest it never wrote.
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        feedOne(0);
        feedOne(BLOCK_FRAMES);
        pipeline.awaitFlushed();
        assertThat(pipeline.stop()).hasSize(1);
        Path manifestPath = pipeline.getTakeManifestPath();
        Path sealed = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        byte[] manifestBefore = Files.readAllBytes(manifestPath);
        byte[] sealedBefore = Files.readAllBytes(sealed);
        List<Path> treeBefore = tree(takeDir);

        assertThatThrownBy(pipeline::start)
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(FileAlreadyExistsException.class);

        assertThat(pipeline.isActive()).isFalse();
        assertThat(track.isRecording()).isFalse();
        assertThat(engine.getRecordingCallback()).isNull();
        assertThat(pipeline.getCaptureFlushService().thread().isAlive()).isFalse();
        assertThat(manifestPath).as("the first take's manifest survives").exists();
        assertThat(Files.readAllBytes(manifestPath)).isEqualTo(manifestBefore);
        assertThat(Files.readAllBytes(sealed)).isEqualTo(sealedBefore);
        assertThat(tree(takeDir)).as("the failed attempt left nothing and removed nothing").isEqualTo(treeBefore);
        assertThat(TakeManifest.read(manifestPath).sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
    }

    @Test
    void aStragglingCallbackAfterAFailedRestartIsHarmless() {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        // The audio thread loads the callback once per block; a block in
        // flight when stop() removes it still runs the reference it loaded.
        AudioEngine.RecordingCallback straggler = engine.getRecordingCallback();
        assertThat(straggler).isNotNull();
        feedOne(0);
        pipeline.awaitFlushed();
        pipeline.stop();
        pipeline.setSessionFactory((t, dir) -> {
            throw new IllegalStateException("injected factory failure");
        });
        assertThatThrownBy(pipeline::start).isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> straggler.onAudioCaptured(rampBlock(0), BLOCK_FRAMES)).doesNotThrowAnyException();
    }

    @Test
    void aRollbackThatThrowsStillReleasesThePipelineAndKeepsTheOriginalFailure() {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSessionFactory((t, dir) -> {
            throw new IllegalStateException("injected factory failure");
        });
        pipeline.setRollbackFault(() -> {
            throw new IllegalArgumentException("injected rollback failure");
        });

        assertThatThrownBy(pipeline::start)
                .as("the start failure is what the caller sees; the rollback's own failure rides along")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected factory failure")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .satisfies(suppressed -> assertThat(suppressed)
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("injected rollback failure")));

        assertThat(pipeline.isActive()).as("a throwing rollback must not wedge the pipeline").isFalse();
        assertThat(track.isRecording()).as("the steps after the throwing one still ran").isFalse();
        assertThat(engine.getRecordingCallback()).isNull();

        pipeline.setRollbackFault(null);
        pipeline.setSessionFactory((t, dir) -> new RecordingSession(MONO_16, dir));
        pipeline.start();
        assertThat(pipeline.isActive()).isTrue();
        pipeline.stop();
    }

    @Test
    void aSealFailureAtTheLoopWrapStacksTheLapOnceAndListsEachSegmentOnce() throws IOException {
        int blocksPerLap = 3;
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocksPerLap * (double) BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES); // two blocks per segment
        pipeline.setLoopRecord(true);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path trackDir = takeDir.resolve(track.getId());

        long frame = feedRamp(engine, transport, pipeline, 0, 2, 1);
        assertThat(trackDir.resolve("segment-000.wav")).as("fixture: the first segment rotated out").exists();
        assertThat(trackDir.resolve("segment-001.wav.part")).as("fixture: the second one is streaming").exists();
        byte[] foreign = "not ours".getBytes(StandardCharsets.US_ASCII);
        Files.write(trackDir.resolve("segment-001.wav"), foreign); // the wrap's seal must refuse to overwrite this
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1); // third block of the lap
        assertThat(service.isSealed()).as("fixture: nothing failed before the wrap").isFalse();

        feedRamp(engine, transport, pipeline, frame, 1, 1);         // the wrap: sealing segment-001 throws

        assertThat(service.isSealed()).isTrue();
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(service.lastFailure()).isPresent().get().isInstanceOf(UncheckedIOException.class);
        List<AudioClip> clips = pipeline.stop();

        String firstSegment = trackDir.resolve("segment-000.wav").toAbsolutePath().toString();
        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group).isNotNull();
        assertThat(group.size()).as("the lap is stacked once, not once per finalize call").isEqualTo(1);
        AudioClip lap = group.takes().getFirst().clip();
        assertThat(lap.getSourceSegmentPaths()).containsExactly(firstSegment);
        assertThat(lap.getAudioData()[0]).hasSize(blocksPerLap * BLOCK_FRAMES);
        assertThat(clips).containsExactly(lap);
        assertThat(track.getClips()).containsExactly(lap);
        assertThat(service.sealedSegmentPaths().get(track.getId()))
                .as("each sealed segment is listed once")
                .containsExactly(trackDir.resolve("segment-000.wav"));
        assertThat(Files.readAllBytes(trackDir.resolve("segment-001.wav"))).isEqualTo(foreign);
        assertThat(trackDir.resolve("segment-001.wav.part")).as("the unsealed segment stays for recovery").exists();

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(manifest.segmentsFor(track.getId()))
                .extracting(TakeManifest.SegmentEntry::index, TakeManifest.SegmentEntry::state)
                .containsExactly(
                        tuple(0, TakeManifest.SegmentState.SEALED),
                        tuple(1, TakeManifest.SegmentState.STREAMING));
    }

    @Test
    void anOverLongBlockIsTruncatedCountedAndRecordedAsATruncationEpisode() throws IOException {
        // The engine delivers 512-frame blocks; the pipeline was sized from a
        // 256-frame format (in the app: the project's format, not the live one).
        AudioFormat liveFormat = new AudioFormat(SAMPLE_RATE, 1, 16, 512);
        AudioFormat takeFormat = new AudioFormat(SAMPLE_RATE, 1, 16, 256);
        AudioEngine liveEngine = new AudioEngine(liveFormat);
        RecordingPipeline pipeline = withFixedHeadroom(
                new RecordingPipeline(liveEngine, transport, takeFormat, takeDir, List.of(track)));
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        assertThat(pipeline.getTruncatedFrames()).as("before start").isZero();
        pipeline.start();
        assertThat(pipeline.getCaptureRing().slotFrames()).isEqualTo(256);
        float[][] output = new float[1][512];

        liveEngine.processBlock(rampBlock(0), output, 512);
        advanceOneBlock(transport);
        pipeline.awaitFlushed();

        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).as("the slot's worth was captured").isEqualTo(256);
        assertThat(pipeline.getTruncatedFrames()).as("the tail was counted, not lost silently").isEqualTo(256);
        assertThat(pipeline.getOverflowCount()).isZero();
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.truncatedFrames()).isEqualTo(256);
        assertThat(manifest.overflowBlocks()).isZero();
        assertThat(manifest.gaps()).containsExactly(new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 256, 0));
        assertThat(manifest.gaps().getFirst().isTruncation()).isTrue();
        assertThat(Files.readString(pipeline.getTakeManifestPath(), StandardCharsets.UTF_8))
                .contains("gap=*|256|0\n").contains("truncated-frames=256\n");
        assertThat(warnings).as("one warning for the episode").hasSize(1);
        assertThat(warnings.getFirst()).contains("512").contains("256").contains("truncated");
        float[][] captured = pipeline.getSession(track).getCapturedAudio();
        assertThat(captured[0]).hasSize(256);
        for (int i = 0; i < 256; i++) {
            assertThat(captured[0][i]).as("frame %d", i).isEqualTo(RampCaptureTestSupport.rampValue(i));
        }

        // Two more over-long blocks belong to the same episode: counted, but
        // no further gap line and no further warning.
        for (int b = 1; b <= 2; b++) {
            liveEngine.processBlock(rampBlock((long) b * 512), output, 512);
            advanceOneBlock(transport);
        }
        pipeline.awaitFlushed();
        assertThat(pipeline.getTruncatedFrames()).isEqualTo(3 * 256L);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).gaps()).hasSize(1);
        assertThat(warnings).hasSize(1);

        // A block that fits ends the episode; the next over-long one starts a new one.
        liveEngine.processBlock(rampBlock(3L * 512), output, 256);
        advanceOneBlock(transport);
        liveEngine.processBlock(rampBlock(4L * 512), output, 512);
        advanceOneBlock(transport);
        pipeline.awaitFlushed();
        assertThat(pipeline.getTruncatedFrames()).isEqualTo(4 * 256L);
        assertThat(warnings).hasSize(2);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).gaps()).containsExactly(
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 256, 0),
                new TakeManifest.GapEntry(TakeManifest.GapEntry.ALL_TRACKS, 4L * 512 + 256, 0));

        pipeline.stop();

        TakeManifest sealed = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(sealed.sealStatus()).as("truncation does not end the take").isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(sealed.truncatedFrames()).as("the sealed manifest carries the exact total").isEqualTo(4 * 256L);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(5 * 256L);
    }

    @Test
    void aBlockThatFitsIsNeverCountedAsTruncated() throws IOException {
        // Non-vacuity of the truncation test: the counter, the gap line and
        // the warning come from the over-long block, not from recording as such.
        RecordingPipeline pipeline = newPipeline(track);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        feedOne(0);
        engine.processBlock(rampBlock(BLOCK_FRAMES), new float[1][BLOCK_FRAMES], 100); // a short block
        pipeline.awaitFlushed();

        assertThat(pipeline.getTruncatedFrames()).isZero();
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(BLOCK_FRAMES + 100L);
        pipeline.stop();
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.truncatedFrames()).isZero();
        assertThat(manifest.gaps()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    /** An {@link Error} no production path throws, so a test can tell its own fault from a real one. */
    private static final class InjectedFault extends Error {
        private static final long serialVersionUID = 1L;

        InjectedFault(String message) {
            super(message);
        }
    }

    @Test
    void aThrowInsideTheFinalSealsManifestStepStillLeavesASealedManifestOnDisk() throws IOException {
        RecordingPipeline pipeline = newPipeline(track);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        feedOne(0);
        feedOne(BLOCK_FRAMES);
        pipeline.awaitFlushed();
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealStatus())
                .as("fixture: streaming before the stop").isEqualTo(TakeManifest.SealStatus.STREAMING);

        service.failNextManifestWriteWith(new InjectedFault("injected manifest fault"));
        List<AudioClip> clips = pipeline.stop();

        assertThat(service.lastFailure()).isPresent().get().isInstanceOf(InjectedFault.class);
        assertThat(service.isRunning()).isFalse();
        assertThat(service.isSealed()).isTrue();
        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getSourceSegmentPaths()).hasSize(1);
        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus())
                .as("the re-entered seal wrote the manifest the first attempt could not")
                .isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(manifest.sealedBy()).as("every segment was sealed by the stop").contains(TakeManifest.SealedBy.STOP);
        assertThat(manifest.segments()).extracting(TakeManifest.SegmentEntry::state)
                .containsOnly(TakeManifest.SegmentState.SEALED);
        assertThat(warnings).as("the loop failure is reported through the sink").singleElement()
                .satisfies(w -> assertThat(w).contains("InjectedFault").contains("injected manifest fault"));
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

    @Test
    void anErrorWhileSealingALaneNeverLeavesAManifestThatClaimsSealed() throws IOException {
        // Two armed tracks; the first one's session dies with an Error AFTER
        // its segment was sealed (the stop notification). The walk over the
        // lanes goes on all the same: the second track's lane gets its seal
        // attempt, the manifest is written, and only then does the Error go
        // on to the flush loop, which reports it.
        Track second = RampCaptureTestSupport.armedMonoTrack("Audio 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            if (t == track) {
                session.addListener(new StopFaultListener(fault));
            }
            return session;
        });
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path firstSealed = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        Path secondSealed = takeDir.resolve(second.getId()).resolve("segment-000.wav");
        try {
            feedOne(0);
            feedOne(BLOCK_FRAMES);
            pipeline.awaitFlushed();

            List<AudioClip> clips = pipeline.stop();

            long fedFrames = 2L * BLOCK_FRAMES;
            assertThat(service.isRunning()).isFalse();
            assertThat(service.lastFailure()).containsSame(fault);
            assertThat(firstSealed).as("fixture: the first track's segment sealed before its listener threw").exists();
            assertThat(secondSealed)
                    .as("the Error out of the first lane did not cost the second lane its seal").exists();
            assertThat(SegmentWriter.partPathFor(secondSealed)).doesNotExist();
            SegmentFile.Description secondOnDisk = SegmentFile.describe(secondSealed);
            assertThat(secondOnDisk.sealed()).isTrue();
            assertThat(secondOnDisk.frameCount()).isEqualTo(fedFrames);

            TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
            assertThat(manifest.sealStatus())
                    .as("a seal in which a lane threw must not read 'sealed'")
                    .isEqualTo(TakeManifest.SealStatus.ABORTED);
            assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
            assertThat(manifest.segmentsFor(track.getId()))
                    .as("the first track's segment is listed once")
                    .extracting(TakeManifest.SegmentEntry::index, TakeManifest.SegmentEntry::state,
                            TakeManifest.SegmentEntry::frames)
                    .containsExactly(tuple(0, TakeManifest.SegmentState.SEALED, fedFrames));
            assertThat(manifest.segmentsFor(second.getId()))
                    .as("the second track's segment is listed sealed, with its exact frame count")
                    .extracting(TakeManifest.SegmentEntry::index, TakeManifest.SegmentEntry::state,
                            TakeManifest.SegmentEntry::frames)
                    .containsExactly(tuple(0, TakeManifest.SegmentState.SEALED, fedFrames));
            for (TakeManifest.SegmentEntry entry : manifest.segments()) {
                Path sealedFile = takeDir.resolve(entry.relativePath());
                assertThat(sealedFile).as("%s is listed sealed", entry.relativePath()).exists();
                assertThat(SegmentWriter.partPathFor(sealedFile)).doesNotExist();
            }

            assertThat(service.sealedSegmentPaths().get(track.getId()))
                    .as("the sealed segment's bookkeeping ran before the Error went on: listed exactly once")
                    .containsExactly(firstSealed);
            assertThat(service.sealedSegmentPaths().get(second.getId())).containsExactly(secondSealed);
            assertThat(clips).hasSize(2);
            assertThat(clips.getFirst().getSourceSegmentPaths())
                    .containsExactly(firstSealed.toAbsolutePath().toString());
            assertThat(clips.getLast().getSourceSegmentPaths())
                    .containsExactly(secondSealed.toAbsolutePath().toString());
            assertThat(warnings).as("the Error is reported once, by the flush loop that caught the rethrow")
                    .singleElement()
                    .satisfies(w -> assertThat(w).contains("InjectedFault").contains("injected stop-listener fault"));
        } finally {
            // Sealed lanes hold no channel and this does nothing; a lane the
            // walk failed to reach would still hold its .part open, and the
            // temporary directory could not be deleted on Windows.
            pipeline.getSession(second).abandonWithoutSeal();
        }
    }

    @Test
    void aSecondLanesErrorIsAttachedToTheFirstAsSuppressed() throws IOException {
        // Two armed tracks; each session's stop notification dies with an
        // Error of its own, after its segment was sealed. The first lane's
        // Error is the one that goes on to the flush loop; the second
        // lane's is attached to it.
        Track second = RampCaptureTestSupport.armedMonoTrack("Audio 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        InjectedFault firstFault = new InjectedFault("injected stop-listener fault, first lane");
        InjectedFault secondFault = new InjectedFault("injected stop-listener fault, second lane");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            session.addListener(new StopFaultListener(t == track ? firstFault : secondFault));
            return session;
        });
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path firstSealed = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        Path secondSealed = takeDir.resolve(second.getId()).resolve("segment-000.wav");
        try {
            feedOne(0);
            feedOne(BLOCK_FRAMES);
            pipeline.awaitFlushed();

            assertThat(pipeline.stop()).hasSize(2);

            assertThat(service.isRunning()).isFalse();
            assertThat(service.lastFailure()).as("the first lane's Error is the one that went on")
                    .containsSame(firstFault);
            assertThat(firstFault.getSuppressed()).as("the second lane's Error is attached to the first")
                    .containsExactly(secondFault);
            assertThat(secondFault.getSuppressed()).isEmpty();
            assertThat(firstSealed).as("the first lane got its seal attempt").exists();
            assertThat(SegmentWriter.partPathFor(firstSealed)).doesNotExist();
            assertThat(secondSealed).as("the second lane got its seal attempt").exists();
            assertThat(SegmentWriter.partPathFor(secondSealed)).doesNotExist();
            TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
            assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
            assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
            assertThat(warnings).as("the sink hears of the Error that went on, once").singleElement()
                    .satisfies(w -> assertThat(w).contains("InjectedFault").contains("first lane"));
        } finally {
            // As above: only a lane the walk failed to reach still holds a channel.
            pipeline.getSession(track).abandonWithoutSeal();
            pipeline.getSession(second).abandonWithoutSeal();
        }
    }

    @Test
    void theSameErrorOutOfTwoLanesIsNeverSuppressedOntoItself() throws IOException {
        // Both sessions' stop notifications throw the SAME Error instance.
        // Throwable.addSuppressed refuses self-suppression with an
        // IllegalArgumentException, which would replace the Error on its
        // way to the flush loop.
        Track second = RampCaptureTestSupport.armedMonoTrack("Audio 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        InjectedFault fault = new InjectedFault("injected stop-listener fault, both lanes");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            session.addListener(new StopFaultListener(fault));
            return session;
        });
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path firstSealed = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        Path secondSealed = takeDir.resolve(second.getId()).resolve("segment-000.wav");
        try {
            feedOne(0);
            feedOne(BLOCK_FRAMES);
            pipeline.awaitFlushed();

            assertThat(pipeline.stop()).hasSize(2);

            assertThat(service.isRunning()).isFalse();
            assertThat(service.lastFailure()).as("the Error itself went on, not a refusal to suppress it")
                    .containsSame(fault);
            assertThat(fault.getSuppressed()).isEmpty();
            assertThat(firstSealed).exists();
            assertThat(secondSealed).as("fixture: the second lane's seal ran, so its listener threw too").exists();
            assertThat(SegmentWriter.partPathFor(secondSealed)).doesNotExist();
            TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
            assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
            assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
            assertThat(warnings).singleElement()
                    .satisfies(w -> assertThat(w).contains("InjectedFault").contains("both lanes"));
        } finally {
            pipeline.getSession(track).abandonWithoutSeal();
            pipeline.getSession(second).abandonWithoutSeal();
        }
    }

    @Test
    void aLaneErrorDuringTheEarlySealOfAWriteFailureBecomesTheLastFailure() throws IOException {
        // The first track's append fails, which ends the take early; the
        // early seal then runs the second track's stop notification, which
        // dies with an Error. lastFailure() is the most recent failure: the
        // Error. The write failure that caused the seal is in the log.
        Track second = RampCaptureTestSupport.armedMonoTrack("Audio 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            if (t == second) {
                session.addListener(new StopFaultListener(fault));
            }
            return session;
        });
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Path firstSealed = takeDir.resolve(track.getId()).resolve("segment-000.wav");
        Path secondSealed = takeDir.resolve(second.getId()).resolve("segment-000.wav");
        Logger flushLogger = Logger.getLogger(CaptureFlushService.class.getName());
        CollectingHandler logged = new CollectingHandler();
        flushLogger.addHandler(logged);
        try {
            int good = 2;
            long frame = feedRamp(engine, transport, pipeline, 0, good, 1);
            assertThat(service.isSealed()).as("fixture: nothing failed yet").isFalse();

            pipeline.getSession(track).getCurrentWriter().failNextAppend();
            frame = feedRamp(engine, transport, pipeline, frame, 1, 1); // fails, seals; the second lane's listener throws
            feedRamp(engine, transport, pipeline, frame, 2, 1);         // discarded

            assertThat(service.isSealed()).isTrue();
            assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
            assertThat(service.lastFailure()).as("the most recent failure: the Error out of the early seal")
                    .containsSame(fault);
            assertThat(logged.records())
                    .filteredOn(record -> record.getThrown() instanceof UncheckedIOException)
                    .as("the write failure that caused the seal is in the log")
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                        assertThat(record.getMessage()).contains("capture write failed");
                    });
            assertThat(warnings.stream().filter(w -> w.contains("stopped early")).count()).isEqualTo(1);
            assertThat(service.isRunning()).as("the flush thread is still alive").isTrue();
            assertThat(service.thread().isAlive()).isTrue();
            assertThat(service.discardedBlocks()).as("and still draining: the blocks after the seal are discarded")
                    .isEqualTo(2);
            assertThat(service.appliedBlocks()).isEqualTo(good + 3);
            assertThat(firstSealed).exists();
            assertThat(secondSealed).as("fixture: the second lane's seal ran, so its listener threw").exists();
            TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
            assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
            assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);

            assertThat(pipeline.stop()).hasSize(2);
            assertThat(service.isRunning()).isFalse();
        } finally {
            flushLogger.removeHandler(logged);
            pipeline.getSession(track).abandonWithoutSeal();
            pipeline.getSession(second).abandonWithoutSeal();
        }
    }

    @Test
    void theBlockThatMetTheExhaustedDiskIsCountedEvenWhenTheSealRethrows() throws IOException {
        // The probe drops below the floor before the third block; sealing
        // for it runs the track's stop notification, which dies with an
        // Error that the seal rethrows.
        int exhaustedAfter = 2;
        AtomicInteger probes = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        List<String> warnings = new CopyOnWriteArrayList<>();
        InjectedFault fault = new InjectedFault("injected stop-listener fault");
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir,
                () -> probes.getAndIncrement() < exhaustedAfter ? 10 * GIB : MIB,
                GIB, 64 * MIB, Duration.ZERO, clock::get, warnings::add));
        pipeline.setWarningSink(warnings::add);
        pipeline.setNanoClock(clock::get);
        pipeline.setSessionFactory((t, dir) -> {
            RecordingSession session = new RecordingSession(MONO_16, dir);
            session.addListener(new StopFaultListener(fault));
            return session;
        });
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        try {
            int fed = exhaustedAfter + 2;
            feedRamp(engine, transport, pipeline, 0, fed, 1);

            assertThat(service.isSealed()).isTrue();
            assertThat(service.sealReason()).as("fixture: the exhaustion branch is what sealed the take")
                    .contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
            assertThat(service.lastFailure()).as("fixture: that seal rethrew the listener's Error")
                    .containsSame(fault);
            assertThat(pipeline.getSession(track).getTotalSamplesRecorded())
                    .as("fixture: the block that met the exhausted disk was not written")
                    .isEqualTo((long) exhaustedAfter * BLOCK_FRAMES);
            assertThat(service.isRunning()).isTrue();
            assertThat(service.discardedBlocks())
                    .as("the block that met the exhausted disk, and the one after it")
                    .isEqualTo(fed - exhaustedAfter);

            assertThat(pipeline.stop()).hasSize(1);
        } finally {
            pipeline.getSession(track).abandonWithoutSeal();
        }
    }

    @Test
    void aTransientManifestFailureIsAbsorbedByTheRetry() throws IOException {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long frame = feedRamp(engine, transport, pipeline, 0, 1, 1);
        assertThat(service.manifestWrites()).isEqualTo(1);

        service.failNextManifestWrites(2);                         // two attempts refused, the third lands
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1); // the rotation block rewrites the manifest

        assertThat(service.isSealed()).as("a sidecar hiccup does not end the take").isFalse();
        assertThat(service.lastFailure()).isEmpty();
        assertThat(service.manifestWrites()).isEqualTo(2);
        assertThat(warnings).isEmpty();
        TakeManifest rotated = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(rotated.segmentsFor(track.getId())).extracting(TakeManifest.SegmentEntry::state)
                .containsExactly(TakeManifest.SegmentState.SEALED, TakeManifest.SegmentState.STREAMING);

        feedRamp(engine, transport, pipeline, frame, 1, 1);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(3L * BLOCK_FRAMES);
        assertThat(pipeline.stop()).hasSize(1);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealedBy()).contains(TakeManifest.SealedBy.STOP);
    }

    @Test
    void twoFailedManifestWritesThenSuccessKeepTheTakeAndWarnOnce() throws IOException {
        AtomicLong clock = new AtomicLong();
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.setNanoClock(clock::get);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long frame = feedRamp(engine, transport, pipeline, 0, 1, 1);
        long retryInterval = CaptureFlushService.MANIFEST_RETRY_INTERVAL.toNanos();

        // Failure 1: the rotation block's write, every attempt of its retry budget.
        // Failure 2: the first single-attempt retry one interval later.
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS + 1);
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1);

        assertThat(service.isSealed()).as("the segments are healthy: capture continues").isFalse();
        assertThat(service.isRunning()).isTrue();
        assertThat(service.lastFailure()).isPresent().get().isInstanceOf(IOException.class);
        assertThat(service.manifestWrites()).as("nothing reached the disk").isEqualTo(1);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("manifest").contains("recording continues");
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).segmentsFor(track.getId()))
                .as("the manifest on disk is the last one that was written: the take start")
                .extracting(TakeManifest.SegmentEntry::state)
                .containsExactly(TakeManifest.SegmentState.STREAMING);

        frame = feedRamp(engine, transport, pipeline, frame, 1, 1);
        assertThat(service.manifestWrites()).as("no retry before the interval has passed").isEqualTo(1);

        clock.addAndGet(retryInterval);
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1); // retry: refused once more
        assertThat(service.manifestWrites()).isEqualTo(1);
        assertThat(warnings).as("one warning per failure episode").hasSize(1);

        clock.addAndGet(retryInterval);
        feedRamp(engine, transport, pipeline, frame, 1, 1);         // retry: lands

        assertThat(service.manifestWrites()).isGreaterThanOrEqualTo(2);
        assertThat(service.isSealed()).isFalse();
        assertThat(warnings).hasSize(1);
        TakeManifest caughtUp = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(caughtUp).as("the manifest on disk caught up with the take").isEqualTo(service.lastManifest().orElseThrow());
        assertThat(caughtUp.segmentsFor(track.getId()))
                .extracting(TakeManifest.SegmentEntry::index, TakeManifest.SegmentEntry::state)
                .containsExactly(
                        tuple(0, TakeManifest.SegmentState.SEALED),
                        tuple(1, TakeManifest.SegmentState.SEALED),
                        tuple(2, TakeManifest.SegmentState.STREAMING));
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(5L * BLOCK_FRAMES);

        assertThat(pipeline.stop()).hasSize(1);
        TakeManifest sealed = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(sealed.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(sealed.sealedBy()).contains(TakeManifest.SealedBy.STOP);
    }

    @Test
    void aRuntimeExceptionFromAManifestWriteIsToleratedLikeAnIoException() throws IOException {
        AtomicLong clock = new AtomicLong();
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.setNanoClock(clock::get);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        long frame = feedRamp(engine, transport, pipeline, 0, 1, 1);
        long retryInterval = CaptureFlushService.MANIFEST_RETRY_INTERVAL.toNanos();

        // The rotation block's write: its first attempt dies with a
        // RuntimeException, the rest of its retry budget is refused.
        service.failNextManifestWriteWith(new IllegalStateException("injected manifest fault, first attempt"));
        service.failNextManifestWrites(CaptureFlushService.MANIFEST_WRITE_ATTEMPTS - 1);
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1);

        assertThat(service.isSealed()).as("a RuntimeException from the sidecar does not end the take").isFalse();
        assertThat(service.isRunning()).isTrue();
        assertThat(service.discardedBlocks()).isZero();
        assertThat(service.manifestWrites()).as("nothing reached the disk").isEqualTo(1);
        assertThat(service.lastFailure()).as("the attempts after the RuntimeException were made: the last one's failure")
                .isPresent().get().isInstanceOf(IOException.class);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("manifest").contains("recording continues");

        // The single-attempt retry one interval later dies the same way.
        IllegalStateException retryFault = new IllegalStateException("injected manifest fault, retry");
        service.failNextManifestWriteWith(retryFault);
        clock.addAndGet(retryInterval);
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1);

        assertThat(service.lastFailure()).containsSame(retryFault);
        assertThat(service.isSealed()).isFalse();
        assertThat(service.manifestWrites()).isEqualTo(1);
        assertThat(warnings).as("one warning per failure episode").hasSize(1);

        clock.addAndGet(retryInterval);
        feedRamp(engine, transport, pipeline, frame, 1, 1);         // retry: lands

        assertThat(service.manifestWrites()).isGreaterThanOrEqualTo(2);
        assertThat(service.isSealed()).isFalse();
        assertThat(warnings).hasSize(1);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()))
                .as("the manifest on disk caught up with the take").isEqualTo(service.lastManifest().orElseThrow());
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(4L * BLOCK_FRAMES);

        assertThat(pipeline.stop()).hasSize(1);
        TakeManifest sealed = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(sealed.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(sealed.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertThat(warnings).noneMatch(w -> w.contains("stopped"));
    }

    @Test
    void theManifestFaultSeamRefusesAThrowableNoWriteCanThrow() {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        assertThatThrownBy(() -> service.failNextManifestWriteWith(new InterruptedException("not a write failure")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InterruptedException");

        feedOne(0);
        pipeline.awaitFlushed();
        assertThat(pipeline.stop()).as("the refused fault was not armed").hasSize(1);
        assertThat(service.lastFailure()).isEmpty();
    }

    @Test
    void aSecondStartThatFailsAtTheRingAllocationForgetsThePreviousTakesRingAndCounters()
            throws InterruptedException {
        // Take 1 on a 256-frame pipeline fed by a 512-frame engine: one
        // dropped block and one truncated block.
        AudioFormat liveFormat = new AudioFormat(SAMPLE_RATE, 1, 16, 512);
        AudioFormat takeFormat = new AudioFormat(SAMPLE_RATE, 1, 16, 256);
        AudioEngine liveEngine = new AudioEngine(liveFormat);
        RecordingPipeline pipeline = withFixedHeadroom(
                new RecordingPipeline(liveEngine, transport, takeFormat, takeDir, List.of(track)));
        pipeline.setRingSlots(8);
        pipeline.setWarningSink(message -> { });
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        float[][] output = new float[1][512];
        service.setDrainPaused(true);
        for (int b = 0; b < 9; b++) { // eight fill the ring, the ninth is dropped
            liveEngine.processBlock(rampBlock((long) b * 256), output, 256);
            advanceOneBlock(transport);
        }
        service.setDrainPaused(false);
        pipeline.awaitFlushed();
        liveEngine.processBlock(rampBlock(9L * 256), output, 512); // twice the slot: 256 frames cut off
        advanceOneBlock(transport);
        pipeline.awaitFlushed();
        assertThat(pipeline.getCaptureRing()).as("fixture: take 1 has a ring").isNotNull();
        assertThat(pipeline.getOverflowCount()).as("fixture: take 1 dropped a block").isEqualTo(1);
        assertThat(pipeline.getTruncatedFrames()).as("fixture: take 1 truncated a block").isEqualTo(256);
        assertThat(pipeline.stop()).hasSize(1);
        assertThat(pipeline.getOverflowCount()).as("the last take's counters stay readable after stop").isEqualTo(1);
        assertThat(pipeline.getTruncatedFrames()).isEqualTo(256);

        // Take 2 asks for more slots than any ring can have, so the start
        // fails at the ring allocation — after the per-take reset. Without
        // the ring's refusal the allocation never returns, so the start runs
        // on its own daemon thread: a regression fails this test instead of
        // hanging the suite (guard 10 s; the refusal itself takes no time).
        pipeline.setRingSlots(CaptureRing.MAX_SLOTS + 1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread starting = Thread.ofPlatform().name("story323-oversized-start").daemon(true).start(() -> {
            try {
                pipeline.start();
            } catch (Throwable thrown) {
                outcome.set(thrown);
            }
        });
        starting.join(10_000);
        assertThat(starting.isAlive()).as("the start returned").isFalse();
        assertThat(outcome.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedSlots");

        assertThat(pipeline.isActive()).isFalse();
        assertThat(track.isRecording()).isFalse();
        assertThat(pipeline.getCaptureRing()).as("no ring was allocated, and take 1's is forgotten").isNull();
        assertThat(pipeline.getCaptureFlushService()).isNull();
        assertThat(pipeline.getOverflowCount()).as("take 1's drop is not reported for the failed start").isZero();
        assertThat(pipeline.getTruncatedFrames()).as("nor its truncation").isZero();
    }

    @Test
    void aPersistentManifestFailureNeverEndsTheTake() throws IOException {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        List<String> warnings = new CopyOnWriteArrayList<>();
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        byte[] startManifest = Files.readAllBytes(pipeline.getTakeManifestPath());

        service.failNextManifestWrites(Integer.MAX_VALUE);
        feedRamp(engine, transport, pipeline, 0, 7, 1);

        assertThat(service.isSealed()).isFalse();
        assertThat(service.isRunning()).isTrue();
        assertThat(service.discardedBlocks()).isZero();
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo(7L * BLOCK_FRAMES);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getSourceSegmentPaths()).as("3 full segments and the tail").hasSize(4);
        assertThat(clips.getFirst().getSourceSegmentPaths()).allSatisfy(path -> {
            assertThat(Path.of(path)).exists();
            assertThat(SegmentFile.describe(Path.of(path)).sealed()).isTrue();
        });
        assertThat(clips.getFirst().getAudioData()[0]).hasSize(7 * BLOCK_FRAMES);
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.STOP);
        assertThat(service.lastFailure()).isPresent().get().isInstanceOf(IOException.class);
        assertThat(service.manifestWrites()).isEqualTo(1);
        assertThat(warnings.stream().filter(w -> w.contains("manifest")).count()).isEqualTo(1);
        assertThat(warnings).noneMatch(w -> w.contains("stopped early"));
        assertThat(Files.readAllBytes(pipeline.getTakeManifestPath()))
                .as("the manifest on disk is still the last successful write")
                .isEqualTo(startManifest);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealStatus())
                .isEqualTo(TakeManifest.SealStatus.STREAMING);
    }

    @Test
    void stopFollowsTheLoopRecordSnapshotTakenAtStart() throws Exception {
        int blocksPerLap = 2;
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocksPerLap * (double) BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.setLoopRecord(true);
        pipeline.start();
        feedRamp(engine, transport, pipeline, 0, 2 * blocksPerLap + 1, 1);

        // The setter refuses a change mid-take, so move the live field behind
        // its back: stop() must still finish the take it started.
        Field live = RecordingPipeline.class.getDeclaredField("loopRecord");
        live.setAccessible(true);
        live.setBoolean(pipeline, false);
        List<AudioClip> clips = pipeline.stop();

        TakeGroup group = pipeline.getTakeGroups().get(track);
        assertThat(group).isNotNull();
        assertThat(group.size()).isEqualTo(3);
        assertThat(track.getTakeGroups()).as("stop attached the take stack").containsKey(group.id());
        assertThat(clips).containsExactly(group.activeClip());
        assertThat(track.getClips()).containsExactly(group.activeClip());
    }

    @Test
    void segmentByteCapBeyondTheWritersLimitIsRefused() {
        RecordingPipeline pipeline = newPipeline(track);

        assertThatThrownBy(() -> pipeline.setSegmentLimits(Duration.ofMinutes(30), SegmentWriter.MAX_DATA_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_DATA_BYTES")
                .hasMessageContaining(Long.toString(SegmentWriter.MAX_DATA_BYTES));
        assertThat(pipeline.getMaxSegmentBytes()).as("a refused cap changes nothing")
                .isEqualTo(RecordingSession.DEFAULT_MAX_SEGMENT_BYTES);

        pipeline.setSegmentLimits(Duration.ofMinutes(30), SegmentWriter.MAX_DATA_BYTES);
        assertThat(pipeline.getMaxSegmentBytes()).isEqualTo(Integer.MAX_VALUE - 44L);
    }

    private static List<Path> tree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.sorted().toList();
        }
    }

    @Test
    void aSessionThatCannotStartRollsTheWholeStartBackAndPropagates() throws IOException {
        Track second = RampCaptureTestSupport.armedMonoTrack("Audio 2");
        Path blocker = Files.writeString(takeDir.resolve("blocker"), "not a directory");
        RecordingPipeline pipeline = newPipeline(track, second);
        pipeline.setSessionFactory((t, dir) -> t == second
                ? new RecordingSession(MONO_16, blocker.resolve("cannot-exist"))
                : new RecordingSession(MONO_16, dir));

        assertThatThrownBy(pipeline::start).isInstanceOf(UncheckedIOException.class);

        assertNothingStarted(pipeline, track, second);
        assertThat(pipeline.getCaptureFlushService()).isNotNull();
        assertThat(pipeline.getCaptureFlushService().isRunning()).isFalse();
        assertThat(pipeline.getCaptureFlushService().thread().isAlive()).isFalse();
        assertTakeDirectoryHoldsOnly(blocker);

        // The pipeline is reusable once the cause is gone.
        pipeline.setSessionFactory((t, dir) -> new RecordingSession(MONO_16, dir));
        pipeline.start();
        assertThat(pipeline.isActive()).isTrue();
        assertThat(pipeline.getCaptureFlushService().isRunning()).isTrue();
        pipeline.stop();
    }

    @Test
    void aSessionFactoryThatThrowsRollsBackBeforeAnyFileExists() throws IOException {
        Track second = RampCaptureTestSupport.armedMonoTrack("Audio 2");
        RecordingPipeline pipeline = newPipeline(track, second);
        pipeline.setSessionFactory((t, dir) -> {
            if (t == second) {
                throw new IllegalStateException("injected factory failure");
            }
            return new RecordingSession(MONO_16, dir);
        });

        assertThatThrownBy(pipeline::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected");

        assertNothingStarted(pipeline, track, second);
        assertThat(pipeline.getCaptureFlushService()).isNull();
        assertTakeDirectoryHoldsOnly();
    }

    private void assertNothingStarted(RecordingPipeline pipeline, Track... tracks) {
        assertThat(pipeline.isActive()).isFalse();
        for (Track t : tracks) {
            assertThat(t.isRecording()).as("%s is not flagged recording", t.getName()).isFalse();
            assertThat(pipeline.getSession(t)).isNull();
        }
        assertThat(engine.getRecordingCallback()).isNull();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThatThrownBy(pipeline::awaitFlushed).isInstanceOf(IllegalStateException.class);
    }

    private void assertTakeDirectoryHoldsOnly(Path... expected) throws IOException {
        List<Path> present = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(takeDir)) {
            for (Path entry : entries) {
                present.add(entry);
            }
        }
        assertThat(present).containsExactlyInAnyOrder(expected);
    }

    @Test
    void stopIsIdempotent() {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        feedOne(0);
        pipeline.awaitFlushed();

        List<AudioClip> first = pipeline.stop();
        List<AudioClip> second = pipeline.stop();

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(track.getClips()).hasSize(1);
        assertThat(pipeline.isActive()).isFalse();
        assertThat(pipeline.getRecordedClips()).containsKey(track);
    }

    @Test
    void theFlushThreadIsNamedDaemonAndDeadAfterStop() throws InterruptedException {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        Thread thread = service.thread();

        assertThat(thread.getName()).isEqualTo(CaptureFlushService.THREAD_NAME).isEqualTo("capture-flush");
        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.isAlive()).isTrue();
        assertThat(service.isRunning()).isTrue();

        pipeline.stop();

        assertThat(service.isTerminated()).as("the stop returns once the thread has terminated").isTrue();
        // A thread that terminated before the stop looked is not joined by
        // it and may still be exiting: the bounded join here waits that out.
        thread.join(CaptureFlushService.DEFAULT_AWAIT_TIMEOUT.toMillis());
        assertThat(thread.isAlive()).isFalse();
        assertThat(service.isRunning()).isFalse();
        assertThat(service.isSealed()).isTrue();
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.STOP);
    }

    @Test
    void preStartSeamsRefuseChangesWhileRecording() {
        RecordingPipeline pipeline = newPipeline(track);
        pipeline.start();
        AtomicLong clock = new AtomicLong();

        assertThatThrownBy(() -> pipeline.setSegmentLimits(Duration.ofMinutes(1), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.setForceCadence(Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.setNanoClock(clock::get)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.setWarningSink(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.setRingSlots(16)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.setChannelOpener(SegmentWriter.CREATE_NEW_CHANNEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel opener");
        assertThatThrownBy(() -> pipeline.setLoopRecord(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loop-record mode");
        assertThat(pipeline.isLoopRecord()).as("the refused change left the flag alone").isFalse();
        assertThat(pipeline.getTakeDirectory()).isEqualTo(takeDir);
        assertThat(pipeline.getTakeManifestPath()).isEqualTo(takeDir.resolve(TakeManifest.FILE_NAME));
        pipeline.stop();
    }
}
