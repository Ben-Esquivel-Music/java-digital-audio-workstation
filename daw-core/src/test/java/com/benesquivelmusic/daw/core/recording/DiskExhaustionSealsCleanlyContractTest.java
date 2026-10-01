package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.decodedRampValue;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.feedRamp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 proof (8) — disk exhaustion and write failure (book §4.3): the
 * take seals cleanly with everything captured so far intact and readable,
 * no throw escapes the flush loop, the thread stays alive draining and
 * discarding, and {@code stop()} returns clips referencing the sealed segments.
 */
class DiskExhaustionSealsCleanlyContractTest {

    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @TempDir
    Path takeDir;

    private AudioEngine engine;
    private Transport transport;
    private Track track;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(MONO_16);
        transport = new Transport();
        track = RampCaptureTestSupport.armedMonoTrack("Keys");
    }

    @Test
    void exhaustionSealsTheTakeWithEverythingCapturedSoFarAndKeepsDraining() throws IOException {
        int lowAfter = 3;       // probe k → LOW
        int exhaustedAfter = 7; // probe m → below the floor; block m is not written
        AtomicInteger probes = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> {
            int n = probes.getAndIncrement();
            return n < lowAfter ? 10 * GIB : n < exhaustedAfter ? 512 * MIB : 1 * MIB;
        }, GIB, 64 * MIB, Duration.ZERO, clock::get, warnings::add);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.setDiskHeadroomWatch(watch);
        pipeline.setWarningSink(warnings::add);
        pipeline.setNanoClock(clock::get);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        int fed = exhaustedAfter + 4;
        feedRamp(engine, transport, pipeline, 0, fed, 1);

        assertThat(probes.get()).as("one probe per applied block until the seal").isEqualTo(exhaustedAfter + 1);
        assertThat(warnings.stream().filter(w -> w.contains("headroom low")).count())
                .as("exactly one LOW warning").isEqualTo(1);
        assertThat(warnings.stream().filter(w -> w.contains("exhausted")).count()).isEqualTo(1);
        assertThat(service.isSealed()).isTrue();
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
        assertThat(service.isRunning()).as("the flush thread is still alive and healthy").isTrue();
        assertThat(service.thread().isAlive()).isTrue();
        assertThat(service.lastFailure()).isEmpty();
        assertThat(service.discardedBlocks()).as("the exhausting block and everything after it").isEqualTo(fed - exhaustedAfter);
        assertThat(service.appliedBlocks()).isEqualTo(fed);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo((long) exhaustedAfter * BLOCK_FRAMES);

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
        assertSealedSegmentsHoldExactly(manifest, exhaustedAfter, 4);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getSourceSegmentPaths()).hasSize(4);
        assertThat(clips.getFirst().getAudioData()[0]).hasSize(exhaustedAfter * BLOCK_FRAMES);
        assertThat(service.isRunning()).isFalse();
        TakeManifest afterStop = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(afterStop.sealedBy()).as("stop keeps the early-seal reason").contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
        assertThat(afterStop.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
    }

    @Test
    void aWriteFailureMidAppendSealsWithWriteFailureAndTheSameGuarantees() throws IOException {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.setWarningSink(warnings::add);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();
        int good = 3;
        long frame = feedRamp(engine, transport, pipeline, 0, good, 1);

        pipeline.getSession(track).getCurrentWriter().failNextAppend();
        frame = feedRamp(engine, transport, pipeline, frame, 1, 1);   // fails, seals
        feedRamp(engine, transport, pipeline, frame, 2, 1);           // discarded

        assertThat(service.isSealed()).isTrue();
        assertThat(service.sealReason()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertThat(service.lastFailure()).isPresent().get().isInstanceOf(UncheckedIOException.class);
        assertThat(service.isRunning()).isTrue();
        assertThat(service.discardedBlocks()).isEqualTo(2);
        assertThat(service.appliedBlocks()).isEqualTo(good + 3);
        assertThat(warnings.stream().filter(w -> w.contains("stopped early")).count()).isEqualTo(1);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo((long) good * BLOCK_FRAMES);

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.WRITE_FAILURE);
        assertSealedSegmentsHoldExactly(manifest, good, 2);

        List<AudioClip> clips = pipeline.stop();
        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getSourceSegmentPaths()).hasSize(2);
        assertThat(clips.getFirst().getAudioData()[0]).hasSize(good * BLOCK_FRAMES);
        assertThat(service.isRunning()).isFalse();
    }

    /**
     * The sink a careless integration would inject: it takes the message
     * and then throws (a sink that touches the UI off its thread does). The
     * test gives the same sink to the headroom watch it injects and, through
     * {@code setWarningSink}, to the flush service — the pairing
     * {@code RecordingPipeline.start()} makes itself when no watch is
     * injected.
     */
    private RecordingPipeline pipelineWithAThrowingSink(DiskHeadroomWatch watch, AtomicLong clock) {
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.setDiskHeadroomWatch(watch);
        pipeline.setWarningSink(this::recordThenThrow);
        pipeline.setNanoClock(clock::get);
        return pipeline;
    }

    private void recordThenThrow(String message) {
        warnings.add(message);
        throw new IllegalStateException("injected sink failure on: " + message);
    }

    @Test
    void aWarningSinkThatThrowsOnTheLowDiskWarningNeverEndsTheTake() throws IOException {
        AtomicLong clock = new AtomicLong();
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 512 * MIB, GIB, 64 * MIB,
                Duration.ZERO, clock::get, this::recordThenThrow);
        RecordingPipeline pipeline = pipelineWithAThrowingSink(watch, clock);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        int fed = 3;
        feedRamp(engine, transport, pipeline, 0, fed, 1);

        assertThat(service.isSealed()).as("a warning that could not be delivered does not end the take").isFalse();
        assertThat(service.sealReason()).isEmpty();
        assertThat(service.lastFailure()).as("the sink's exception is not a capture failure").isEmpty();
        assertThat(warnings).as("fixture: the sink was offered the LOW warning, once, and threw").singleElement()
                .satisfies(w -> assertThat(w).contains("Disk headroom low").contains("512 MiB"));
        assertThat(service.isRunning()).isTrue();
        assertThat(service.discardedBlocks()).isZero();
        assertThat(service.appliedBlocks()).isEqualTo(fed);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded()).isEqualTo((long) fed * BLOCK_FRAMES);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealStatus())
                .isEqualTo(TakeManifest.SealStatus.STREAMING);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getAudioData()[0]).hasSize(fed * BLOCK_FRAMES);
        TakeManifest sealed = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(sealed.sealStatus()).isEqualTo(TakeManifest.SealStatus.SEALED);
        assertThat(sealed.sealedBy()).contains(TakeManifest.SealedBy.STOP);
        assertSealedSegmentsHoldExactly(sealed, fed, 2);
        assertThat(warnings).as("nothing else was reported").hasSize(1);
    }

    @Test
    void aWarningSinkThatThrowsOnTheExhaustionWarningStillSealsWithDiskExhaustion() throws IOException {
        int exhaustedAfter = 2; // probe m → below the floor; block m is not written
        AtomicInteger probes = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir,
                () -> probes.getAndIncrement() < exhaustedAfter ? 10 * GIB : 1 * MIB,
                GIB, 64 * MIB, Duration.ZERO, clock::get, this::recordThenThrow);
        RecordingPipeline pipeline = pipelineWithAThrowingSink(watch, clock);
        pipeline.start();
        CaptureFlushService service = pipeline.getCaptureFlushService();

        int fed = exhaustedAfter + 2;
        feedRamp(engine, transport, pipeline, 0, fed, 1);

        assertThat(service.isSealed()).isTrue();
        assertThat(service.sealReason())
                .as("the take ended for the reason it ended, not because the sink threw")
                .contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
        assertThat(service.lastFailure()).isEmpty();
        assertThat(warnings).as("fixture: the sink was offered the exhaustion warning, once, and threw")
                .singleElement()
                .satisfies(w -> assertThat(w).contains("Disk exhausted").contains("sealing the take"));
        assertThat(service.isRunning()).isTrue();
        assertThat(service.discardedBlocks()).isEqualTo(fed - exhaustedAfter);
        assertThat(pipeline.getSession(track).getTotalSamplesRecorded())
                .isEqualTo((long) exhaustedAfter * BLOCK_FRAMES);

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
        assertSealedSegmentsHoldExactly(manifest, exhaustedAfter, 1);

        List<AudioClip> clips = pipeline.stop();

        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getAudioData()[0]).hasSize(exhaustedAfter * BLOCK_FRAMES);
        assertThat(TakeManifest.read(pipeline.getTakeManifestPath()).sealedBy())
                .as("stop keeps the early-seal reason").contains(TakeManifest.SealedBy.DISK_EXHAUSTION);
    }

    @Test
    void withoutAWarningSinkTheWarningsDegradeToTheLogAtWarningLevel() {
        // Two emitters, two loggers: the headroom watch logs its own LOW
        // crossing; the flush service logs the ring overflow. A handler on
        // their common parent sees both.
        Logger recordingLoggers = Logger.getLogger(CaptureFlushService.class.getPackageName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
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
        };
        recordingLoggers.addHandler(handler);
        try {
            AtomicLong clock = new AtomicLong();
            RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
            pipeline.setDiskHeadroomWatch(new DiskHeadroomWatch(takeDir, () -> 512 * MIB, GIB, 64 * MIB,
                    Duration.ZERO, clock::get)); // no sink
            pipeline.setNanoClock(clock::get);
            pipeline.setRingSlots(8);
            pipeline.start();                    // no setWarningSink either
            CaptureFlushService service = pipeline.getCaptureFlushService();
            service.setDrainPaused(true);
            float[][] output = new float[1][BLOCK_FRAMES];
            for (int b = 0; b < 9; b++) {        // 9 offered into 8 slots: one dropped
                engine.processBlock(RampCaptureTestSupport.rampBlock((long) b * BLOCK_FRAMES), output, BLOCK_FRAMES);
                RampCaptureTestSupport.advanceOneBlock(transport);
            }
            service.setDrainPaused(false);
            pipeline.awaitFlushed();
            pipeline.stop();

            assertThat(records).as("the LOW crossing, from the watch's logger").anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getLoggerName()).isEqualTo(DiskHeadroomWatch.class.getName());
                assertThat(record.getMessage()).contains("Disk headroom low").contains("512 MiB");
            });
            assertThat(records).as("the overflow, from the flush service's logger").anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getLoggerName()).isEqualTo(CaptureFlushService.class.getName());
                assertThat(record.getMessage()).contains("overflow").contains("1 block(s)");
            });

            // Non-vacuity: with a sink injected the same two messages go to
            // the sink and no longer to the log.
            records.clear();
            Path second = takeDir.resolve("with-sink");
            RecordingPipeline withSink = new RecordingPipeline(engine, transport, MONO_16, second, List.of(track));
            withSink.setDiskHeadroomWatch(new DiskHeadroomWatch(second, () -> 512 * MIB, GIB, 64 * MIB,
                    Duration.ZERO, clock::get, warnings::add));
            withSink.setWarningSink(warnings::add);
            withSink.setNanoClock(clock::get);
            withSink.setRingSlots(8);
            withSink.start();
            withSink.getCaptureFlushService().setDrainPaused(true);
            for (int b = 0; b < 9; b++) {
                engine.processBlock(RampCaptureTestSupport.rampBlock((long) b * BLOCK_FRAMES), output, BLOCK_FRAMES);
                RampCaptureTestSupport.advanceOneBlock(transport);
            }
            withSink.getCaptureFlushService().setDrainPaused(false);
            withSink.awaitFlushed();
            withSink.stop();

            assertThat(warnings).anySatisfy(w -> assertThat(w).contains("Disk headroom low"));
            assertThat(warnings).anySatisfy(w -> assertThat(w).contains("overflow").contains("1 block(s)"));
            assertThat(records).noneSatisfy(record ->
                    assertThat(record.getMessage()).containsAnyOf("Disk headroom low", "overflow"));
        } finally {
            recordingLoggers.removeHandler(handler);
        }
    }

    /** Every segment is sealed, readable through {@link SegmentFile}, and together they hold exactly {@code blocks} ramp blocks. */
    private void assertSealedSegmentsHoldExactly(TakeManifest manifest, int blocks, int expectedSegments) throws IOException {
        List<TakeManifest.SegmentEntry> entries = manifest.segmentsFor(track.getId());
        assertThat(entries).hasSize(expectedSegments);
        assertThat(entries).extracting(TakeManifest.SegmentEntry::state).containsOnly(TakeManifest.SegmentState.SEALED);
        long recovered = 0;
        for (TakeManifest.SegmentEntry entry : entries) {
            Path file = entry.resolve(takeDir);
            assertThat(file).exists();
            assertThat(SegmentWriter.partPathFor(file)).doesNotExist();
            SegmentFile.Description description = SegmentFile.describe(file);
            assertThat(description.sealed()).isTrue();
            assertThat(description.frameCount()).isEqualTo(entry.frames());
            float[][] frames = SegmentFile.readFrames(file);
            for (int i = 0; i < frames[0].length; i++) {
                assertThat(frames[0][i]).as("frame %d", recovered + i).isEqualTo(decodedRampValue(recovered + i));
            }
            recovered += frames[0].length;
        }
        assertThat(recovered).isEqualTo((long) blocks * BLOCK_FRAMES);
    }
}
