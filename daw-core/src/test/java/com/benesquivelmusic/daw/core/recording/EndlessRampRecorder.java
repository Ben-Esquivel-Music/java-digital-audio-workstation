package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;

/**
 * The recording JVM that {@link TakeRecoveryAfterJvmKillContractTest} starts
 * and then kills: it records the deterministic ramp of
 * {@link RampCaptureTestSupport} through a real {@link RecordingPipeline} into
 * {@code <project>/audio/takes/<take>/}, feeding the engine's callback on its
 * main thread exactly as the in-process tests do, and it goes on feeding
 * until it is killed: it only halts on its own when reading its standard
 * input ends or fails (the parent is gone), when writing its standard output
 * fails, once its take has been sealed early, once it has fed
 * {@link #FRAME_CAP} frames, or when something throws.
 *
 * <p>Usage: {@code EndlessRampRecorder <project directory>}. Standard output —
 * a file when the kill test starts the recorder — carries one line per fact,
 * each written and flushed before the thread that writes it goes on:</p>
 * <ul>
 *   <li>{@value #TAKE_DIRECTORY}{@code <absolute path>} — once the pipeline
 *       has started;</li>
 *   <li>{@value #TRACK_ID}{@code <id>} — the one armed track;</li>
 *   <li>{@value #FLUSHED_FRAMES}{@code <n> }{@value #OVERFLOW_BLOCKS}{@code <m>}{@value #LINE_END}
 *       — after every {@code awaitFlushed} fence, unless the take has been
 *       sealed early by then: the {@code capture-flush} thread has appended
 *       every frame below {@code n} that the ring did not drop to the take's
 *       segments, so its bytes have been handed to the operating system, and
 *       the ring has dropped {@code m} blocks. The recorder fences after
 *       every {@link #FENCE_EVERY_BLOCKS} blocks, and feeds the next block
 *       only once the line is flushed;</li>
 *   <li>{@value #WARNING}{@code <message>} — every warning the pipeline gives
 *       its sink, on the thread that gives it, with any line break in the
 *       message replaced by a space;</li>
 *   <li>{@value #HALTED}{@code <reason>} — just before it halts on its own,
 *       unless writing its standard output is what failed.</li>
 * </ul>
 */
final class EndlessRampRecorder {

    /**
     * Segment byte cap: 3 MiB, a whole number of 1024-byte blocks, so every
     * rotation happens right after the append that fills the segment.
     */
    static final long SEGMENT_BYTES = 3L << 20;

    /** Frames in a full segment: 1,572,864 (about 32.8 s at 48 kHz). */
    static final long SEGMENT_FRAMES = SEGMENT_BYTES / RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;

    /** Ring slots: four times {@link #FENCE_EVERY_BLOCKS}, so the fenced feed can never overflow the ring. */
    static final int RING_SLOTS = 256;

    /** Blocks fed between two {@code awaitFlushed} fences. */
    static final int FENCE_EVERY_BLOCKS = 64;

    /**
     * Frames after which the recorder halts on its own: ten minutes at
     * 48 kHz, five times the two minutes after which the parent kills it —
     * a bound on the disk and heap an orphaned recorder can use, never
     * reached in a run that goes to plan.
     */
    static final long FRAME_CAP = (long) (10 * 60 * SAMPLE_RATE);

    static final String TAKE_DIRECTORY = "take-directory=";
    static final String TRACK_ID = "track-id=";
    static final String FLUSHED_FRAMES = "flushed-frames=";
    static final String OVERFLOW_BLOCKS = "overflow-blocks=";
    /** Ends a progress line, so a line cut short by the kill never parses. */
    static final String LINE_END = " end";
    static final String WARNING = "warning=";
    static final String HALTED = "halted=";

    /** Exit status when the recorder halts because reading its standard input ended or failed: its parent is gone. */
    static final int PARENT_GONE_STATUS = 3;
    /** Exit status when the recorder halts at {@link #FRAME_CAP}. */
    static final int FRAME_CAP_STATUS = 4;
    /** Exit status when something the recorder called threw. */
    static final int FAILURE_STATUS = 5;
    /** Exit status when the recorder halts because its take was sealed early ({@link EarlySeal}). */
    static final int SEALED_EARLY_STATUS = 6;
    /** Exit status when the recorder halts because writing its standard output failed. */
    static final int OUTPUT_FAILED_STATUS = 7;

    private EndlessRampRecorder() {
    }

    static void main(String[] args) {
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("usage: EndlessRampRecorder <project directory>");
            }
            haltWhenTheParentIsGone();
            record(Path.of(args[0]));
        } catch (Throwable failure) {
            failure.printStackTrace();
            report(HALTED + "failure " + failure);
            Runtime.getRuntime().halt(FAILURE_STATUS);
        }
    }

    private static void record(Path projectDirectory) throws IOException {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        Path takeDirectory = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDirectory), Instant.now());
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDirectory, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), SEGMENT_BYTES);
        pipeline.setRingSlots(RING_SLOTS);
        pipeline.setWarningSink(message -> report(WARNING + message.replaceAll("\\R", " ")));
        startRecording(pipeline);
        // Registered once: the dependent runs on the capture-flush thread as
        // that thread signals, or here if the signal has completed already.
        AtomicReference<EarlySeal> sealedEarly = new AtomicReference<>();
        pipeline.earlySeal().thenAccept(sealedEarly::set);
        report(TAKE_DIRECTORY + takeDirectory.toAbsolutePath());
        report(TRACK_ID + track.getId());

        float[][] output = new float[1][BLOCK_FRAMES];
        long frame = 0;
        while (frame < FRAME_CAP) {
            for (int block = 0; block < FENCE_EVERY_BLOCKS; block++) {
                engine.processBlock(rampBlock(frame), output, BLOCK_FRAMES);
                advanceOneBlock(transport);
                frame += BLOCK_FRAMES;
            }
            pipeline.awaitFlushed();
            // The flush thread signals an early seal before it counts the
            // block that met the failure or the headroom floor, or any block
            // it discards after the seal; this recorder gates no block out
            // (no punch, no beat range), so every other block it counts has
            // been written. The fence returns once every block the ring
            // accepted before it is counted: when the signal has not been
            // seen here, each of those blocks was written.
            EarlySeal seal = sealedEarly.get();
            if (seal != null) {
                report(HALTED + "sealed early: " + seal);
                Runtime.getRuntime().halt(SEALED_EARLY_STATUS);
            }
            report(FLUSHED_FRAMES + frame + " " + OVERFLOW_BLOCKS + pipeline.getOverflowCount() + LINE_END);
        }
        report(HALTED + "fed the frame cap of " + FRAME_CAP + " frames without being killed");
        Runtime.getRuntime().halt(FRAME_CAP_STATUS);
    }

    /** Writes and flushes one line; halts once writing stdout has failed. */
    private static void report(String line) {
        System.out.println(line);
        if (System.out.checkError()) {
            Runtime.getRuntime().halt(OUTPUT_FAILED_STATUS);
        }
    }

    /**
     * The parent never writes to the recorder's standard input and keeps it
     * open until it has killed the recorder, so end of file there means the
     * parent is gone: halt rather than record into a directory nobody reads.
     */
    private static void haltWhenTheParentIsGone() {
        Thread.ofPlatform().name("parent-watch").daemon(true).start(() -> {
            try {
                while (System.in.read() >= 0) {
                    // Nothing is ever sent; keep reading until end of file.
                }
            } catch (IOException gone) {
                // A broken pipe means the same as end of file.
            }
            report(HALTED + "standard input closed: the parent is gone");
            Runtime.getRuntime().halt(PARENT_GONE_STATUS);
        });
    }
}
