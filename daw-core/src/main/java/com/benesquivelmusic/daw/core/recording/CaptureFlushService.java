package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.recording.TakeManifest.GapEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code capture-flush} thread of one take (Recording Reliability book
 * §4.1, §4.3, §4.6; story 323): drains the {@link CaptureRing} strictly in
 * order, routes and gates each block per armed track exactly as the
 * recording callback used to, streams the result through each track's
 * {@link RecordingSession}, keeps the {@link TakeManifest} sidecar truthful,
 * watches disk headroom, and seals everything cleanly — on stop, on disk
 * exhaustion, on a write failure — without ever throwing out of the loop.
 *
 * <p><strong>Threads (one writer per byte, book §2.3).</strong></p>
 * <ul>
 *   <li>The audio callback calls only {@link #signal()} (one unpark) after
 *       publishing a slot; it never enters this class otherwise.</li>
 *   <li>The flush thread is the only writer of segment files, the manifest,
 *       the sessions' RAM mirrors, the loop-take stacks and the ring's read
 *       index. Modeled on the ASIO shim's {@code asio-input-drain}: park when
 *       the ring is dry with a bounded {@link #PARK_BACKSTOP} backstop, drain
 *       in order, final sweep after the stop flag, then seal.</li>
 *   <li>The caller thread (FX in the app) calls {@link #start()} — which
 *       starts every session and writes the initial manifest itself, before
 *       the thread is started, so the manifest still has one writer at a
 *       time — {@link #stopAndSeal(SealedBy)} (bounded join, never an
 *       unbounded hang: {@link #STOP_JOIN_TIMEOUT}), and reads results only
 *       after that join. {@link #awaitFlushed(Duration)} is the fence any
 *       thread but the flush thread may use to observe every block
 *       published before its call.</li>
 * </ul>
 *
 * <p><strong>Per block</strong> (context D9): (a) loop-wrap check from the
 * slot header — beat decreased while looping — seals the lane, builds the
 * lap's {@link Take} and opens the next lane; (b) the disk-headroom check
 * ({@link DiskHeadroomWatch#check(long)}; EXHAUSTED seals the take before
 * the block is written); (c) punch gating with the header's start frame,
 * beat and punch snapshot — the sample-accurate slicing, 5 ms cosine fades,
 * {@code wasInsidePunchRegion} re-entry rule and the legacy beat-based
 * {@link PunchRange} gate are the pipeline's original code, moved here
 * verbatim; (d) routing per the snapshot in each {@link TrackCapture} (every
 * row of the routed block the source did not provide is zeroed, never
 * stale); (e) the session append, which forces on cadence and rotates on
 * exact counts; (f) the truncation note for a block that was longer than
 * its slot; (g) a manifest rewrite whenever a segment opened, sealed or was
 * discarded, or a truncation episode began.</p>
 *
 * <p><strong>Loss is recorded, never hidden.</strong> Ring overflow is
 * noticed after each drain pass and recorded as one {@code gap=} line per
 * noticed episode, positioned at the end of the last block accepted before
 * the drop: the callback marks that block
 * ({@link CaptureRing#droppedAfterSequence()}) and the drain pass remembers
 * its end frame when it applies it, so blocks published after the drop and
 * applied in the same pass do not move the position. Drops at different
 * points that one pass notices together share one line, at the most recent
 * marked block. If the marked block's end was not captured — the marker
 * was not yet visible to this thread when it applied that block, or the
 * drop happened after the pass's last look at the ring, so the marked
 * block is still queued — the line falls back to the end of the last
 * block applied. A block longer than its ring slot keeps the slot's worth
 * of frames; the first such block of a run is recorded as a
 * {@code gap=*|<end of its kept frames>|0} line with a warning, and the
 * running total goes into {@code truncated-frames} with every later
 * manifest write (exact at the seal).</p>
 *
 * <p><strong>Failure.</strong> An {@link UncheckedIOException} from a
 * session or any other {@link Throwable} while a block is applied is logged
 * SEVERE, reported through the warning sink, and answered by an immediate
 * clean seal with {@code seal-status=aborted} /
 * {@code sealed-by=write-failure} ({@code disk-exhaustion} for the headroom
 * floor). A throwable that escapes the drain loop itself is reported the
 * same way and ends the thread after the seal. After an early seal the
 * thread keeps draining: further blocks are discarded and counted
 * ({@link #discardedBlocks()}) so the callback side never notices.</p>
 *
 * <p><strong>The manifest is a sidecar.</strong> A manifest write that fails
 * — an {@link IOException} or a {@link RuntimeException} from building or
 * writing it — never ends the take: the segments are healthy. On the flush
 * thread a write is attempted up to {@link #MANIFEST_WRITE_ATTEMPTS} times,
 * {@link #MANIFEST_RETRY_PAUSE} apart (a sharing violation on Windows is
 * usually that short-lived). If every attempt fails the failure is recorded
 * ({@link #lastFailure()}), the sink is warned once per failure episode,
 * capture continues, and the write is retried — one attempt, at most once
 * per {@link #MANIFEST_RETRY_INTERVAL} of the take's clock — with later
 * blocks and idle passes until it lands. The manifest on disk is then the
 * last one that was written. The same holds for the final seal: the
 * segments are sealed whether or not the manifest can say so. Only the
 * initial write in {@link #start()} is a single attempt whose failure fails
 * the start.</p>
 *
 * <p>Warnings go to the injected sink, or {@code java.util.logging} WARNING
 * when none is injected (story 339 injects the production seam). The sink
 * is foreign code: a {@link RuntimeException} it throws is logged at WARNING
 * with the message it was given and goes no further, so a warning that
 * could not be delivered never ends a take.</p>
 */
public final class CaptureFlushService implements AutoCloseable {

    /** Name of the flush thread. */
    public static final String THREAD_NAME = "capture-flush";

    /** Bounded park when the ring is dry; the callback's unpark normally wakes the thread sooner. */
    public static final Duration PARK_BACKSTOP = Duration.ofMillis(50);

    /** Bound on the join in {@link #stopAndSeal(SealedBy)}; on expiry the caller continues with whatever is sealed. */
    public static final Duration STOP_JOIN_TIMEOUT = Duration.ofSeconds(30);

    /** Default bound for {@link #awaitFlushed(Duration)} callers that pass none. */
    public static final Duration DEFAULT_AWAIT_TIMEOUT = Duration.ofSeconds(10);

    /** Attempts one manifest write makes on the flush thread before it counts as failed. */
    public static final int MANIFEST_WRITE_ATTEMPTS = 5;

    /** Pause between the attempts of one manifest write. */
    public static final Duration MANIFEST_RETRY_PAUSE = Duration.ofMillis(20);

    /** Minimum spacing, on the take's clock, of the single-attempt retries that follow a failed manifest write. */
    public static final Duration MANIFEST_RETRY_INTERVAL = Duration.ofSeconds(1);

    /** Duration, in seconds, of the cosine crossfade at the punch-in and punch-out boundaries. */
    static final double PUNCH_CROSSFADE_SECONDS = 0.005;

    private static final Logger LOG = Logger.getLogger(CaptureFlushService.class.getName());
    private static final long PARK_BACKSTOP_NANOS = PARK_BACKSTOP.toNanos();
    private static final long AWAIT_POLL_NANOS = 200_000L;
    private static final long MANIFEST_RETRY_PAUSE_NANOS = MANIFEST_RETRY_PAUSE.toNanos();
    private static final long MANIFEST_RETRY_INTERVAL_NANOS = MANIFEST_RETRY_INTERVAL.toNanos();

    /**
     * The immutable per-take configuration the flush thread works from.
     *
     * @param takeDirectory       the take directory (manifest home; segments under {@code <trackId>/})
     * @param format              the stream format
     * @param tempoBpm            tempo at record start (lap clip durations)
     * @param recordingStartBeat  the take's anchor beat
     * @param recordingStartFrame the take's anchor frame
     * @param legacyPunchRange    the beat-based punch gate, or {@code null}
     * @param loopRecord          whether loop laps become take lanes
     * @param forceCadence        the writers' force cadence (recorded in the manifest)
     * @param startedAt           the take start instant (recorded in the manifest)
     */
    public record TakeConfig(Path takeDirectory, AudioFormat format, double tempoBpm,
                             double recordingStartBeat, long recordingStartFrame,
                             PunchRange legacyPunchRange, boolean loopRecord,
                             Duration forceCadence, Instant startedAt) {
        public TakeConfig {
            Objects.requireNonNull(takeDirectory, "takeDirectory must not be null");
            Objects.requireNonNull(format, "format must not be null");
            Objects.requireNonNull(forceCadence, "forceCadence must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            if (tempoBpm <= 0) {
                throw new IllegalArgumentException("tempoBpm must be positive: " + tempoBpm);
            }
            if (recordingStartFrame < 0) {
                throw new IllegalArgumentException(
                        "recordingStartFrame must not be negative: " + recordingStartFrame);
            }
        }
    }

    private final CaptureRing ring;
    private final TakeConfig config;
    private final List<TrackCapture> captures;
    private final DiskHeadroomWatch headroom;
    private final Consumer<String> warningSink;
    private final LongSupplier nanoClock;
    private final Thread thread;
    private final TakeManifest.Builder manifest;
    private final int fadeFramesAtRate;

    private volatile boolean started;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean abortRequested;
    private volatile boolean drainPaused;
    private volatile boolean sealed;
    private volatile SealedBy sealReason;
    private volatile SealedBy requestedReason = SealedBy.STOP;
    private volatile long appliedBlocks;
    private volatile long discardedBlocks;
    /** Overflow count whose gap bookkeeping is done (the other half of the {@link #awaitFlushed} fence). */
    private volatile long overflowNoted;
    private volatile long manifestWrites;
    private volatile TakeManifest lastManifest;
    private volatile Throwable lastFailure;
    /** This take has attempted a manifest write, so the manifest files in the take directory are its own. */
    private volatile boolean manifestTouched;
    /** Test seams; see {@link #setBlockObserver}, {@link #failNextManifestWrites}, {@link #failNextManifestWriteWith}. */
    private volatile BlockObserver blockObserver;
    private volatile int injectedManifestFaults;
    private volatile Throwable injectedManifestFault;

    // Flush-thread state (also touched by start() before the thread is started).
    private boolean manifestDirty;
    /** The last manifest write failed on every attempt; cleared by the next one that lands. */
    private boolean manifestWriteFailing;
    private long lastManifestAttemptNanos;
    /** The manifest carrying the final seal status is on disk. */
    private boolean finalManifestWritten;
    private boolean sealFailed;
    private long lastOverflowSeen;
    private long lastAppliedEndFrame;
    /** Sequence and end frame of the applied block the ring's drop marker named when it was applied. */
    private long gapAnchorSequence = CaptureRing.NO_DROP;
    private long gapAnchorFrame;
    private long truncatedFramesNoted;
    private boolean truncationEpisodeOpen;
    private double previousBeat = -1.0;
    private boolean wasInsidePunchRegion;

    private final TrackCapture.SegmentEvents segmentEvents = new TrackCapture.SegmentEvents() {
        @Override
        public void onSegmentOpened(TrackCapture capture, int lane, RecordingSegment segment) {
            manifest.putSegment(new SegmentEntry(capture.trackId(), lane, segment.index(),
                    relativePath(segment.filePath()), TakeManifest.FRAMES_STREAMING, SegmentState.STREAMING));
            manifestDirty = true;
        }

        @Override
        public void onSegmentSealed(TrackCapture capture, int lane, RecordingSegment segment) {
            manifest.putSegment(new SegmentEntry(capture.trackId(), lane, segment.index(),
                    relativePath(segment.filePath()), segment.sampleCount(), SegmentState.SEALED));
            manifestDirty = true;
        }

        @Override
        public void onSegmentDiscarded(TrackCapture capture, int lane, RecordingSegment segment) {
            manifest.removeSegment(capture.trackId(), lane, segment.index());
            manifestDirty = true;
        }
    };

    /**
     * Creates the service and its (unstarted) thread. Caller thread.
     *
     * @param ring        the capture ring the callback fills
     * @param config      the take configuration
     * @param captures    the armed tracks' captures, in armed order, with unstarted lane-0 sessions
     * @param headroom    the disk-headroom watch to tick per block
     * @param warningSink warning sink, or {@code null} to log at WARNING
     * @param nanoClock   monotonic clock for the headroom tick and the manifest retry interval
     */
    CaptureFlushService(CaptureRing ring, TakeConfig config, List<TrackCapture> captures,
                        DiskHeadroomWatch headroom, Consumer<String> warningSink, LongSupplier nanoClock) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.captures = List.copyOf(Objects.requireNonNull(captures, "captures must not be null"));
        if (this.captures.isEmpty()) {
            throw new IllegalArgumentException("at least one track capture is required");
        }
        this.headroom = Objects.requireNonNull(headroom, "headroom must not be null");
        this.warningSink = warningSink != null ? warningSink : message -> LOG.log(Level.WARNING, message);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        AudioFormat format = config.format();
        this.fadeFramesAtRate = Math.max(1, (int) Math.round(PUNCH_CROSSFADE_SECONDS * format.sampleRate()));
        this.manifest = TakeManifest.builder()
                .take(takeName(config.takeDirectory()))
                .startedAt(config.startedAt())
                .sampleRate(format.sampleRate())
                .bitDepth(format.bitDepth())
                .streamChannels(format.channels())
                .startBeat(config.recordingStartBeat())
                .startFrame(config.recordingStartFrame())
                .forceCadenceMillis(config.forceCadence().toMillis())
                .ringSlots(ring.capacity())
                .ringFrames(ring.slotFrames());
        for (TrackCapture capture : this.captures) {
            manifest.addTrack(new TrackEntry(capture.trackId(), format.channels(), capture.compensationFrames()));
        }
        this.thread = Thread.ofPlatform()
                .name(THREAD_NAME)
                .daemon(true)
                .unstarted(this::runLoop);
    }

    private static String takeName(Path takeDirectory) {
        Path name = takeDirectory.toAbsolutePath().normalize().getFileName();
        return name == null ? "take" : name.toString();
    }

    /**
     * Starts the take on the caller thread: creates the take directory,
     * starts every lane-0 session (each creates {@code <trackId>/segment-000.wav.part}),
     * writes the initial manifest (one attempt: the caller thread does not
     * sit in a retry loop), then starts the flush thread. All or nothing: if
     * any step fails, every file created here is deleted again (the take
     * directory and the failing lane's own track directory are left;
     * {@code RecordingPipeline}'s rollback removes the latter through
     * {@code abortStart()}) and the failure propagates; the thread is never
     * started. The rollback deletes
     * segment files this take opened, and the manifest only once this take
     * has attempted to write it — so a start that fails on a segment an
     * earlier take left in the same directory leaves that take's segments
     * and manifest as they were.
     *
     * @throws UncheckedIOException     if a directory, segment or the manifest cannot be created
     * @throws IllegalArgumentException if the format's bit depth is unsupported
     * @throws IllegalStateException    if already started
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("capture-flush already started");
        }
        started = true;
        List<TrackCapture> begun = new ArrayList<>();
        try {
            Files.createDirectories(config.takeDirectory());
            for (TrackCapture capture : captures) {
                capture.setSegmentEvents(segmentEvents);
            }
            for (TrackCapture capture : captures) {
                capture.startLane();
                begun.add(capture);
            }
            writeManifestOnce(manifest.build());
        } catch (IOException e) {
            rollbackStart(begun);
            throw new UncheckedIOException("cannot start capture under " + config.takeDirectory(), e);
        } catch (RuntimeException | Error e) {
            rollbackStart(begun);
            throw e;
        }
        running = true;
        thread.start();
    }

    private void rollbackStart(List<TrackCapture> begun) {
        for (TrackCapture capture : begun) {
            capture.discardAllFiles();
        }
        deleteManifestFiles();
    }

    /**
     * Wakes the flush thread. Audio-callback side: one {@code unpark}, no
     * allocation, never blocks — the same call the ASIO shim's callback makes.
     */
    public void signal() {
        LockSupport.unpark(thread);
    }

    private void runLoop() {
        try {
            while (!stopping) {
                boolean progressed = !drainPaused && drainOnce();
                if (!progressed) {
                    LockSupport.parkNanos(this, PARK_BACKSTOP_NANOS);
                }
            }
            if (!abortRequested) {
                drainOnce();
                sealAll(requestedReason);
            }
        } catch (Throwable t) {
            lastFailure = t;
            LOG.log(Level.SEVERE, "capture-flush loop failed; sealing the take", t);
            warn("Recording stopped unexpectedly — " + describe(t)
                    + "; sealing everything captured so far under " + config.takeDirectory());
            try {
                // Re-entry is safe: lanes already sealed are left alone, and
                // a final manifest that did not reach the disk is written now.
                sealAll(SealedBy.WRITE_FAILURE);
            } catch (Throwable sealFailure) {
                LOG.log(Level.SEVERE, "capture-flush could not seal after a loop failure", sealFailure);
            }
        } finally {
            running = false;
        }
    }

    /** Drains every published slot in order; returns whether any was applied. */
    private boolean drainOnce() {
        boolean progressed = false;
        CaptureRing.Slot slot;
        while ((slot = ring.peek()) != null) {
            progressed = true;
            applyBlock(slot);
            // Header values are read before the release: the producer may
            // reuse the slot as soon as the read index has moved.
            long sequence = slot.sequence();
            long startFrame = slot.startFrame();
            int numFrames = slot.numFrames();
            if (sequence == ring.droppedAfterSequence()) {
                // The callback dropped the block(s) that would have followed
                // this one: the loss begins where this block ends.
                gapAnchorSequence = sequence;
                gapAnchorFrame = startFrame + numFrames;
            }
            ring.release();
            appliedBlocks = ring.releasedBlocks();
            BlockObserver observer = blockObserver;
            if (observer != null) {
                observer.onBlockApplied(sequence, startFrame, numFrames);
            }
        }
        noteOverflow();
        if (manifestDirty) {
            flushManifest(); // the idle tick of a manifest that could not be written
        }
        return progressed;
    }

    private void applyBlock(CaptureRing.Slot slot) {
        if (sealed) {
            discardedBlocks = discardedBlocks + 1;
            if (manifestDirty) {
                flushManifest();
            }
            return;
        }
        try {
            // Loop-record: a beat position that went backwards between
            // consecutive blocks while looping means a lap just completed.
            // Detected before routing so the wrapped audio goes into the new
            // lane — sample-accurate loop boundaries.
            double currentBeatPosition = slot.beatPosition();
            if (config.loopRecord()
                    && previousBeat >= 0.0
                    && currentBeatPosition < previousBeat
                    && slot.loopEnabled()) {
                finalizeLoopLap();
            }
            previousBeat = currentBeatPosition;

            if (headroom.check(nanoClock.getAsLong()) == DiskHeadroomWatch.State.EXHAUSTED) {
                // Counted before the seal: the seal may rethrow a lane's Error.
                discardedBlocks = discardedBlocks + 1;
                sealAll(SealedBy.DISK_EXHAUSTION);
                return;
            }

            long blockStart = slot.startFrame();
            int numFrames = slot.numFrames();
            long blockEnd = blockStart + numFrames;

            if (slot.punchEnabled()) {
                captureWithTransportPunch(slot, numFrames, blockStart, blockEnd,
                        slot.punchStartFrames(), slot.punchEndFrames());
            } else if (config.legacyPunchRange() != null
                    && !config.legacyPunchRange().contains(currentBeatPosition)) {
                // Legacy beat-based gating: outside the range, nothing is recorded.
            } else {
                recordToSessions(slot, 0, numFrames, false, 0, 0);
            }
            lastAppliedEndFrame = blockEnd;
            noteTruncation(slot, blockEnd);
            if (manifestDirty) {
                flushManifest();
            }
        } catch (Throwable failure) {
            failAndSeal(failure);
        }
    }

    /**
     * Records a block that was longer than its slot. The first block of a
     * run opens an episode — one {@code gap=*|<end of the kept frames>|0}
     * line, one warning, one manifest rewrite; the blocks that follow only
     * add to the total, so a stream that is over-long throughout costs one
     * line and one rewrite, not one per block. A block that fits closes the
     * episode.
     */
    private void noteTruncation(CaptureRing.Slot slot, long keptEndFrame) {
        int truncated = slot.truncatedFrames();
        if (truncated <= 0) {
            truncationEpisodeOpen = false;
            return;
        }
        truncatedFramesNoted += truncated;
        manifest.truncatedFrames(truncatedFramesNoted);
        if (truncationEpisodeOpen) {
            return;
        }
        truncationEpisodeOpen = true;
        long gapStart = Math.max(0L, keptEndFrame);
        manifest.addGap(new GapEntry(GapEntry.ALL_TRACKS, gapStart, 0));
        manifestDirty = true;
        int delivered = slot.numFrames() + truncated;
        warn("Capture block of " + delivered + " frames is longer than the " + ring.slotFrames()
                + "-frame ring slot: " + truncated + " frame(s) truncated at frame " + gapStart
                + ", and every over-long block that follows loses its tail the same way;"
                + " the take manifest records the episode and the total");
    }

    /**
     * Captures input with sample-accurate slicing against the header's punch
     * frames. Only frames in {@code [punchStart, punchEnd)} are forwarded; a
     * 5 ms cosine crossfade ramp is applied on the blocks that straddle the
     * punch-in and punch-out boundaries. Gating is re-evaluated per block, so
     * a transport that rewinds or loops back into the region resumes
     * recording for each new pass (auto-punch).
     */
    private void captureWithTransportPunch(CaptureRing.Slot slot, int numFrames,
                                           long blockStart, long blockEnd,
                                           long punchStart, long punchEnd) {
        long sliceStart = Math.max(blockStart, punchStart);
        long sliceEnd = Math.min(blockEnd, punchEnd);
        if (sliceEnd <= sliceStart) {
            wasInsidePunchRegion = false;
            return;
        }

        int offset = (int) (sliceStart - blockStart);
        int sliceFrames = (int) (sliceEnd - sliceStart);
        int fadeFrames = Math.min(fadeFramesAtRate, sliceFrames);

        // Punch-in crossfade: ramp-in when this block contains punchStart.
        // Also applies on auto-punch re-entry (wasInsidePunchRegion was false).
        int fadeInFrames = (blockStart <= punchStart && punchStart < blockEnd
                || !wasInsidePunchRegion)
                ? Math.min(fadeFrames, (int) (sliceEnd - sliceStart))
                : 0;

        // Punch-out crossfade: ramp-out when this block contains punchEnd.
        int fadeOutFrames = (blockStart < punchEnd && punchEnd <= blockEnd)
                ? Math.min(fadeFrames, (int) (sliceEnd - sliceStart))
                : 0;

        recordToSessions(slot, offset, sliceFrames, true, fadeInFrames, fadeOutFrames);

        wasInsidePunchRegion = (blockEnd < punchEnd);
    }

    /**
     * Routes {@code sliceFrames} starting at {@code offset} of the slot's
     * sources to each track's session per its routing snapshot, optionally
     * applying a cosine fade-in at the start of the slice and/or fade-out at
     * the end. Every row of the routed block is written for the slice: the
     * source's channels where it delivered them, silence everywhere else.
     */
    private void recordToSessions(CaptureRing.Slot slot, int offset, int sliceFrames,
                                  boolean punch, int fadeInFrames, int fadeOutFrames) {
        for (TrackCapture capture : captures) {
            RecordingSession session = capture.session();
            if (session == null) {
                continue;
            }
            boolean instrument = capture.isInstrument();
            int source = instrument ? capture.instrumentSource() : 0;
            int available = slot.sourceChannels(source);
            if (instrument && available == 0) {
                continue;
            }
            float[][] rows = slot.source(source);
            float[][] routed = capture.routed();
            InputRouting routing = capture.routing();
            int firstCh = instrument ? 0 : routing.firstChannel();
            int chCount = instrument ? available : routing.channelCount();
            for (int ch = 0; ch < chCount && ch < routed.length; ch++) {
                int srcCh = firstCh + ch;
                if (srcCh < available) {
                    System.arraycopy(rows[srcCh], offset, routed[ch], 0, sliceFrames);
                } else {
                    Arrays.fill(routed[ch], 0, sliceFrames, 0f);
                }
            }
            // The session reads every row of the routed block, so the rows
            // this block did not fill must not keep an earlier block's samples.
            for (int ch = Math.max(0, chCount); ch < routed.length; ch++) {
                Arrays.fill(routed[ch], 0, sliceFrames, 0f);
            }

            if (punch && (fadeInFrames > 0 || fadeOutFrames > 0)) {
                applyCosineFades(routed, chCount, sliceFrames, fadeInFrames, fadeOutFrames);
            }

            session.recordAudioData(routed, sliceFrames);
        }
    }

    /**
     * Applies an equal-power cosine fade-in at the start of the slice and/or
     * a cosine fade-out at the end. The ramp rises (or falls) from 0 to 1
     * along {@code 0.5 * (1 - cos(pi * t))} for {@code t} in {@code [0, 1]},
     * giving a click-free transition at the punch boundary.
     */
    static void applyCosineFades(float[][] buffer, int channels,
                                 int sliceFrames,
                                 int fadeInFrames, int fadeOutFrames) {
        if (fadeInFrames > 0) {
            for (int i = 0; i < fadeInFrames; i++) {
                double t = (double) i / fadeInFrames;
                float gain = (float) (0.5 * (1.0 - Math.cos(Math.PI * t)));
                for (int ch = 0; ch < channels && ch < buffer.length; ch++) {
                    buffer[ch][i] *= gain;
                }
            }
        }
        if (fadeOutFrames > 0) {
            int start = sliceFrames - fadeOutFrames;
            for (int i = 0; i < fadeOutFrames; i++) {
                double t = (double) i / fadeOutFrames;
                float gain = (float) (0.5 * (1.0 + Math.cos(Math.PI * t)));
                for (int ch = 0; ch < channels && ch < buffer.length; ch++) {
                    buffer[ch][start + i] *= gain;
                }
            }
        }
    }

    /** Loop wrap: every track seals its lane, stacks the lap as a take, and opens the next lane. */
    private void finalizeLoopLap() {
        for (TrackCapture capture : captures) {
            capture.finalizeLane(true, true);
        }
    }

    /**
     * Turns an advance of the ring's overflow counter into one {@code gap=}
     * line, positioned at the end of the last block accepted before the
     * drop (see the class note for the fallback). The fallback position —
     * the end of the last block applied — has no test: it is not reachable
     * deterministically, because both ways into it are races between single
     * loads and stores of the two threads (the callback's marker store
     * against this thread's marker load for the block it names, or a drop
     * between this thread's last look at the ring and its read of the
     * counter) and no seam holds this thread at either point — the
     * {@link BlockObserver} runs after the marker comparison and before the
     * next look at the ring, where a block published meanwhile is applied
     * and matched in order.
     */
    private void noteOverflow() {
        long overflow = ring.overflowCount();
        if (overflow <= lastOverflowSeen) {
            return;
        }
        long dropped = overflow - lastOverflowSeen;
        lastOverflowSeen = overflow;
        // Read after the count: the callback stores the marker first.
        long droppedAfter = ring.droppedAfterSequence();
        long gapStart = Math.max(0L, gapAnchorSequence == droppedAfter ? gapAnchorFrame : lastAppliedEndFrame);
        manifest.addGap(new GapEntry(GapEntry.ALL_TRACKS, gapStart, dropped)).overflowBlocks(overflow);
        manifestDirty = true;
        warn("Capture ring overflow: " + dropped + " block(s) dropped at frame " + gapStart
                + " (" + overflow + " dropped in total); the take manifest records the gap");
        flushManifest();
        overflowNoted = overflow;
    }

    private void failAndSeal(Throwable failure) {
        lastFailure = failure;
        LOG.log(Level.SEVERE, "capture write failed; sealing the take early with what is on disk", failure);
        warn("Recording stopped early — " + describe(failure)
                + "; everything captured so far is sealed under " + config.takeDirectory());
        try {
            sealAll(SealedBy.WRITE_FAILURE);
        } catch (Throwable sealFailure) {
            LOG.log(Level.SEVERE, "capture-flush could not seal after a write failure", sealFailure);
        }
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    /**
     * Seals every track's current lane (stacking it as the final take in
     * loop-record), then writes the final manifest. A lane whose seal fails
     * is logged and left as its {@code .part} for recovery; the manifest
     * then reads {@code aborted}/{@code write-failure} whatever the
     * requested reason, because the record must not claim more than the disk holds.
     *
     * <p>Every lane gets its seal attempt, whatever an earlier lane threw.
     * An {@link Error} out of a lane is not absorbed, only postponed: the
     * seal counts as failed, the walk goes on to the remaining lanes, the
     * final manifest is written, and then the first such {@code Error} is
     * rethrown to the caller; a later lane's {@code Error} is attached to it
     * as suppressed, unless it is that same instance. From the final sweep
     * that caller is the flush loop's failure handler, which logs it SEVERE
     * and reports it through the warning sink.</p>
     *
     * <p>Safe to re-enter, in two independent halves. The lanes are sealed
     * by the first call only, and that call's reason is the one that
     * sticks. The final manifest is written by whichever call finds it not
     * yet on disk — so when the first call's manifest step throws, the
     * re-entry from the loop's failure handler still leaves a manifest
     * whose {@code seal-status} is not {@code streaming}; and when a lane
     * threw in the first call, that manifest reads
     * {@code aborted}/{@code write-failure}, never {@code sealed}.</p>
     */
    private void sealAll(SealedBy reason) {
        Error firstLaneError = null;
        if (!sealed) {
            sealed = true;
            sealReason = reason;
            for (TrackCapture capture : captures) {
                try {
                    capture.finalizeLane(config.loopRecord(), false);
                } catch (RuntimeException e) {
                    sealFailed = true;
                    lastFailure = e;
                    LOG.log(Level.SEVERE, "seal failed for track " + capture.trackId()
                            + "; its streaming segment is left for recovery", e);
                    warn("Could not seal a segment of track " + capture.trackName()
                            + "; the streaming file is left under " + capture.trackDirectory());
                } catch (Error e) {
                    // Postponed, not absorbed: the lanes after this one are
                    // owed their seal, and the manifest its final write.
                    sealFailed = true;
                    lastFailure = e;
                    if (firstLaneError == null) {
                        firstLaneError = e;
                    } else if (e != firstLaneError) {
                        // A later lane's Error goes on with the first one.
                        // The same instance again must not: addSuppressed
                        // refuses self-suppression by throwing.
                        firstLaneError.addSuppressed(e);
                    }
                    LOG.log(Level.SEVERE, "an Error ended the seal of track " + capture.trackId()
                            + "; the remaining lanes are sealed before it goes on", e);
                }
            }
        }
        if (!finalManifestWritten) {
            SealedBy first = sealReason;
            if (first == SealedBy.STOP && !sealFailed) {
                manifest.sealed(SealedBy.STOP);
            } else {
                manifest.aborted(sealFailed ? SealedBy.WRITE_FAILURE : first);
            }
            manifestDirty = true;
            if (!writeManifest(MANIFEST_WRITE_ATTEMPTS)) {
                LOG.log(Level.SEVERE, "final manifest write failed under " + config.takeDirectory()
                        + "; the segments are sealed, the manifest on disk is the last one written");
            }
        }
        if (firstLaneError != null) {
            throw firstLaneError;
        }
    }

    /**
     * Writes the manifest if it is due: at once (with the full retry
     * budget) when the previous write landed, otherwise one attempt per
     * {@link #MANIFEST_RETRY_INTERVAL}, so that an unwritable manifest costs
     * the drain one retry cycle (4 × 20 ms) per failure episode and one
     * attempt a second after that. Flush thread. Never throws an
     * {@link IOException} or a {@link RuntimeException}.
     */
    private void flushManifest() {
        if (!manifestWriteFailing) {
            writeManifest(MANIFEST_WRITE_ATTEMPTS);
        } else if (nanoClock.getAsLong() - lastManifestAttemptNanos >= MANIFEST_RETRY_INTERVAL_NANOS) {
            writeManifest(1);
        }
    }

    /**
     * Builds and writes the manifest, trying up to {@code attempts} times
     * with {@link #MANIFEST_RETRY_PAUSE} in between. Flush thread.
     *
     * @return whether the manifest on disk now matches the builder; on
     *         {@code false} the manifest stays dirty, the failure is
     *         recorded and the sink has been warned once for this episode
     */
    private boolean writeManifest(int attempts) {
        Exception failure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                pause(MANIFEST_RETRY_PAUSE_NANOS);
            }
            try {
                writeManifestOnce(manifest.build());
                if (manifestWriteFailing) {
                    manifestWriteFailing = false;
                    LOG.log(Level.INFO, "take manifest under " + config.takeDirectory() + " is up to date again");
                }
                return true;
            } catch (IOException | RuntimeException e) {
                failure = e;
            }
        }
        manifestDirty = true;
        lastManifestAttemptNanos = nanoClock.getAsLong();
        lastFailure = failure;
        LOG.log(Level.WARNING, "take manifest write failed under " + config.takeDirectory()
                + " after " + attempts + " attempt(s); capture continues", failure);
        if (!manifestWriteFailing) {
            manifestWriteFailing = true;
            warn("The take manifest under " + config.takeDirectory() + " could not be written — "
                    + describe(failure) + "; recording continues and the manifest is rewritten as soon as it can be");
        }
        return false;
    }

    /** One write attempt, no failure handling: the caller thread at take start, and each attempt of {@link #writeManifest(int)}. */
    private void writeManifestOnce(TakeManifest built) throws IOException {
        throwInjectedManifestFault();
        manifestTouched = true;
        built.write(config.takeDirectory());
        lastManifest = built;
        manifestDirty = false;
        manifestWrites = manifestWrites + 1;
        if (built.sealStatus() != TakeManifest.SealStatus.STREAMING) {
            finalManifestWritten = true;
        }
    }

    /** Waits out {@code nanos} of real time; an unpark from the callback does not shorten it. */
    private static void pause(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining = nanos;
        while (remaining > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            remaining = deadline - System.nanoTime();
        }
    }

    /**
     * Deletes the manifest and its staging file — but only if this take
     * wrote (or tried to write) them. A take that failed before its first
     * manifest write leaves whatever {@code take.manifest} the directory
     * holds alone: it belongs to an earlier take.
     */
    private void deleteManifestFiles() {
        if (!manifestTouched) {
            return;
        }
        Path manifestPath = TakeManifest.manifestPath(config.takeDirectory());
        try {
            Files.deleteIfExists(manifestPath);
            Files.deleteIfExists(manifestPath.resolveSibling(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not delete " + manifestPath, e);
        }
    }

    private String relativePath(Path file) {
        return SegmentEntry.relativePathFor(config.takeDirectory(), file);
    }

    /**
     * Hands {@code message} to the sink; a {@link RuntimeException} the sink
     * throws is logged with the message and goes no further. Flush thread,
     * and the caller thread for the join-timeout warning.
     */
    private void warn(String message) {
        try {
            warningSink.accept(message);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, message + " (warning sink threw)", e);
        }
    }

    /**
     * Blocks until every block published before this call has been applied
     * — routed, written, counted, and its manifest rewrite done — and every
     * ring overflow counted before this call has its {@code gap=} line
     * recorded, or the timeout elapses. "Manifest rewrite done" means
     * attempted, or — while an earlier manifest failure is still being
     * retried — deferred to the next retry slot: a rewrite that failed is
     * retried later and is not part of the fence. Any thread but the flush
     * thread (called from inside a block's application or the overflow
     * bookkeeping it would wait for itself).
     *
     * @param timeout the bound
     * @throws IllegalStateException if the timeout elapses, or the flush
     *                               thread is no longer running with blocks
     *                               still unapplied or drops still unrecorded
     */
    public void awaitFlushed(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long target = ring.publishedBlocks();
        long targetOverflow = ring.overflowCount();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (appliedBlocks < target || overflowNoted < targetOverflow) {
            if (!running) {
                throw new IllegalStateException("capture-flush is not running: applied " + appliedBlocks
                        + " of " + target + " published block(s), noted " + overflowNoted + " of "
                        + targetOverflow + " dropped");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("awaitFlushed timed out after " + timeout + ": applied "
                        + appliedBlocks + " of " + target + " published block(s), " + ring.size()
                        + " pending in the ring, noted " + overflowNoted + " of " + targetOverflow + " dropped");
            }
            LockSupport.parkNanos(AWAIT_POLL_NANOS);
        }
    }

    /**
     * Stops the flush thread and seals the take: sets the stop flag, wakes
     * the thread, joins it for at most {@link #STOP_JOIN_TIMEOUT} (on expiry
     * logs SEVERE and continues with whatever is sealed — the caller is
     * never hung forever). The thread's final sweep drains the ring, seals
     * every active writer and writes the final manifest (a final manifest
     * that cannot be written is logged SEVERE and leaves the last written
     * one on disk; the segments are sealed regardless). Idempotent; a take
     * already sealed early (exhaustion, write failure) keeps its reason.
     *
     * @param reason what ended the take ({@link SealedBy#STOP} on the normal path)
     * @return every track's sealed segment paths in manifest order, keyed by track id
     */
    public Map<String, List<Path>> stopAndSeal(SealedBy reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (started) {
            if (!stopping) {
                requestedReason = reason;
                stopping = true;
            }
            if (thread.isAlive()) {
                LockSupport.unpark(thread);
                joinBounded();
            }
        }
        return sealedSegmentPaths();
    }

    private void joinBounded() {
        try {
            thread.join(STOP_JOIN_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOG.log(Level.SEVERE, "capture-flush did not stop within " + STOP_JOIN_TIMEOUT
                    + "; continuing with whatever is sealed under " + config.takeDirectory());
            warn("Recording finalisation is taking longer than " + STOP_JOIN_TIMEOUT.toSeconds()
                    + " s; the take under " + config.takeDirectory() + " may still be sealing");
        }
    }

    /**
     * Start-failure rollback for the pipeline: stops the thread without
     * sealing, deletes every file the captures created and — if this take
     * wrote one — the manifest. Only meaningful before any block was
     * published.
     */
    void abortStart() {
        abortRequested = true;
        stopping = true;
        if (thread.isAlive()) {
            LockSupport.unpark(thread);
            joinBounded();
        }
        for (TrackCapture capture : captures) {
            capture.discardAllFiles();
        }
        deleteManifestFiles();
    }

    /**
     * Crash simulation (test seam): stops the thread WITHOUT the final sweep
     * or any seal and abandons every writer, leaving the {@code .part}
     * files and a {@code streaming} manifest exactly as a JVM death would.
     */
    void simulateHardTermination() {
        abortRequested = true;
        stopping = true;
        if (thread.isAlive()) {
            LockSupport.unpark(thread);
            joinBounded();
        }
        for (TrackCapture capture : captures) {
            capture.abandonForCrashSimulation();
        }
    }

    /** Test seam: while {@code true} the loop parks without draining, so the ring can be filled deliberately. */
    void setDrainPaused(boolean paused) {
        drainPaused = paused;
        if (!paused) {
            LockSupport.unpark(thread);
        }
    }

    /**
     * Test seam: told about every block the flush thread has finished with,
     * in drain order, on the flush thread, right after the block's slot was
     * released and counted.
     */
    @FunctionalInterface
    interface BlockObserver {
        /**
         * @param sequence   the block's ring sequence number
         * @param startFrame the header's transport start frame
         * @param numFrames  the header's (kept) frame count
         */
        void onBlockApplied(long sequence, long startFrame, int numFrames);
    }

    /** Test seam: installs (or, with {@code null}, removes) the {@link BlockObserver}. Any thread. */
    void setBlockObserver(BlockObserver observer) {
        blockObserver = observer;
    }

    /**
     * Fault seam (test-only): the next {@code attempts} manifest write
     * attempts throw an {@link IOException} before touching the disk — the
     * shape of a staging file or a rename the OS refuses. Any thread.
     */
    void failNextManifestWrites(int attempts) {
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative: " + attempts);
        }
        injectedManifestFaults = attempts;
    }

    /**
     * Fault seam (test-only): the next manifest write attempt throws
     * {@code fault} before touching the disk. An {@link IOException} or a
     * {@link RuntimeException} is what the write's own failure handling
     * absorbs; an {@link Error} is a throw it does not. Consumed by that
     * one attempt. Any thread.
     *
     * @param fault an {@code IOException}, a {@code RuntimeException} or an {@code Error}
     * @throws IllegalArgumentException if {@code fault} is any other kind
     *                                  of throwable (a manifest write
     *                                  cannot throw it)
     */
    void failNextManifestWriteWith(Throwable fault) {
        Objects.requireNonNull(fault, "fault must not be null");
        if (!(fault instanceof IOException || fault instanceof RuntimeException || fault instanceof Error)) {
            throw new IllegalArgumentException("a manifest write cannot throw " + fault.getClass().getName());
        }
        injectedManifestFault = fault;
    }

    private void throwInjectedManifestFault() throws IOException {
        Throwable fault = injectedManifestFault;
        if (fault != null) {
            injectedManifestFault = null;
            switch (fault) {
                case IOException io -> throw io;
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                default -> throw new IllegalStateException("unreachable: checked by the seam", fault);
            }
        }
        int faults = injectedManifestFaults;
        if (faults > 0) {
            injectedManifestFaults = faults - 1;
            throw new IOException("injected manifest write failure (test seam) under " + config.takeDirectory());
        }
    }

    /** Equivalent to {@link #stopAndSeal(SealedBy)} with {@link SealedBy#STOP}. */
    @Override
    public void close() {
        stopAndSeal(SealedBy.STOP);
    }

    /** Returns every track's sealed segment paths (all lanes, manifest order), keyed by track id. */
    public Map<String, List<Path>> sealedSegmentPaths() {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (TrackCapture capture : captures) {
            List<Path> paths = new ArrayList<>();
            for (TrackCapture.SealedSegment sealedSegment : capture.sealedSegments()) {
                paths.add(sealedSegment.segment().filePath());
            }
            result.put(capture.trackId(), List.copyOf(paths));
        }
        return result;
    }

    /** Returns how many published blocks have been applied (routed, written and counted). */
    public long appliedBlocks() {
        return appliedBlocks;
    }

    /**
     * Returns how many blocks were drained and not written: the block that
     * met an exhausted disk, and every block drained after the seal.
     */
    public long discardedBlocks() {
        return discardedBlocks;
    }

    /** Returns how many incoming blocks the callback dropped because the ring was full. */
    public long overflowCount() {
        return ring.overflowCount();
    }

    /** Returns whether the take has been sealed (normally or early). */
    public boolean isSealed() {
        return sealed;
    }

    /** Returns what sealed the take, once it is sealed. */
    public Optional<SealedBy> sealReason() {
        return Optional.ofNullable(sealReason);
    }

    /** Returns whether the flush thread is running. */
    public boolean isRunning() {
        return running;
    }

    /** Returns the most recently written manifest, once one has been written. */
    public Optional<TakeManifest> lastManifest() {
        return Optional.ofNullable(lastManifest);
    }

    /** Returns how many manifest writes have reached the disk (start, rotations, seals, gaps; failed attempts are not counted). */
    public long manifestWrites() {
        return manifestWrites;
    }

    /**
     * Returns the most recent failure the flush thread recorded, if any: the
     * one that ended the take early, a lane that could not be sealed, or a
     * manifest write that failed on every attempt (which does not end the
     * take and is not cleared when a later write lands). When a write
     * failure ends the take early and a lane's seal then throws an
     * {@link Error}, that {@code Error} replaces the write failure here; the
     * write failure is in the log.
     */
    public Optional<Throwable> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /** Returns the take directory. */
    public Path takeDirectory() {
        return config.takeDirectory();
    }

    /** Returns the manifest path inside the take directory. */
    public Path manifestPath() {
        return TakeManifest.manifestPath(config.takeDirectory());
    }

    /** Returns the take configuration. */
    public TakeConfig config() {
        return config;
    }

    Thread thread() {
        return thread;
    }

    List<TrackCapture> captures() {
        return captures;
    }

    @Override
    public String toString() {
        return "CaptureFlushService[" + config.takeDirectory() + ", running=" + running
                + ", sealed=" + sealed + (sealReason == null ? "" : "/" + sealReason)
                + ", applied=" + appliedBlocks + ", discarded=" + discardedBlocks
                + ", overflow=" + ring.overflowCount() + "]";
    }
}
