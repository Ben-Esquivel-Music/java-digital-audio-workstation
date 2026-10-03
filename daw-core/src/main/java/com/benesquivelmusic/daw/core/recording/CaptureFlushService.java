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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
 *       index — and the only thread that creates or deletes the take's
 *       segment and manifest files. Its first act is the take's
 *       initialisation: it creates the take directory, starts every lane-0
 *       session (each creates {@code <trackId>/segment-000.wav.part}) and
 *       writes the initial manifest, and only then completes
 *       {@link #readiness()} and begins draining. Modeled on the ASIO shim's
 *       {@code asio-input-drain}: park when the ring is dry with a bounded
 *       {@link #PARK_BACKSTOP} backstop, drain in order, final sweep after
 *       the stop flag, then seal. Every pass — including one that finds the
 *       ring still dry after a park — runs the cadence tick (below) once more
 *       after the blocks it drained. When it seals the take on its own it
 *       completes {@link #earlySeal()} and does nothing more about it:
 *       stopping the take is the caller's. When an abort is requested it
 *       deletes the segment and manifest files it created for the take, and
 *       each track directory that leaves empty, instead of sealing it, and
 *       leaves the take directory to its caller; an abort that comes after a
 *       stop request, or after the thread has ended on its own, deletes
 *       nothing ({@link #requestAbort()}).</li>
 *   <li>The caller thread (FX in the app) calls {@link #start()}, which only
 *       starts the thread, and the requests {@link #requestStop(SealedBy)}
 *       and {@link #requestAbort()}, which set a flag and wake the thread.
 *       None of them touches storage, and none of them waits: the caller
 *       never joins the flush thread. It learns what the thread did from
 *       three signals that a holder cannot complete — {@link #readiness()},
 *       {@link #earlySeal()} and {@link #termination()} — and it reads
 *       results only once the thread has terminated ({@link #isTerminated()}).
 *       {@link #awaitFlushed(Duration)} is the fence any thread but the
 *       flush thread may use to observe every block published before its
 *       call.</li>
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
 * discarded, or a truncation episode began; (h) the cadence tick, before the
 * block is released and counted.</p>
 *
 * <p><strong>Force cadence</strong> (book §2.1, §4.3). A segment's
 * un-forced bytes are {@code force(false)}d at the first check made once
 * the force cadence has elapsed, on the writer's clock, since its last
 * force (or its open) — by the append that finds it elapsed, and otherwise
 * by the cadence tick, which runs after every block and at the end of every
 * pass. So bytes are forced on cadence when their track records nothing
 * more — a block outside the punch region or the legacy range, or one
 * without the track's instrument source — and while the ring is dry, and a
 * long pass does not wait for the ring to empty. While the take streams, a
 * byte therefore stays un-forced for at most the cadence plus the wait for
 * this thread's next check: a pass after each {@link #PARK_BACKSTOP} park
 * while the ring is dry, a check after each block while it drains (the test
 * seam {@code setDrainPaused} suspends both while it holds the loop). The
 * tick forces nothing when nothing is un-forced; it stands down once the
 * take is sealed, and during the final sweep, because the seal that follows
 * opens with a {@code force(true)} of every segment it seals; and a force
 * that throws ends the take exactly as a failed append does (below).</p>
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
 * session or any other {@link Throwable} while a block is applied or while
 * the cadence tick forces is logged SEVERE, reported through the warning
 * sink, and answered by an immediate clean seal with
 * {@code seal-status=aborted} /
 * {@code sealed-by=write-failure} ({@code disk-exhaustion} for the headroom
 * floor), after which the thread keeps draining until a stop: further
 * blocks are discarded and counted ({@link #discardedBlocks()}) so the
 * callback side never notices. A throwable that escapes the drain loop
 * itself is reported the same way and ends the thread after the seal. Each
 * such early seal, once done, completes {@link #earlySeal()}. A lane whose
 * seal throws in the seal a stop requested is no early seal: the take is
 * already stopping, and {@link #stopSealFailure()} reports it.</p>
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
 * initial write, in the take's initialisation, is a single attempt whose
 * failure fails the start ({@link #readiness()}): no block is applied before
 * readiness, and {@code RecordingPipeline} installs its recording callback
 * only once readiness has completed normally, so a refused write costs no
 * audio, and the caller hears of it at the first refusal instead of after a
 * retry budget spent on a take that has nothing in it yet to keep going.</p>
 *
 * <p>Warnings go to the injected sink, or {@code java.util.logging} WARNING
 * when none is injected (story 339 injects the production seam). The sink
 * is foreign code: a {@link RuntimeException} it throws is logged at WARNING
 * with the message it was given and goes no further, so a warning that
 * could not be delivered never ends a take.</p>
 */
public final class CaptureFlushService {

    /** Name of the flush thread. */
    public static final String THREAD_NAME = "capture-flush";

    /** Bounded park when the ring is dry; the callback's unpark normally wakes the thread sooner. */
    public static final Duration PARK_BACKSTOP = Duration.ofMillis(50);

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
    /** Completed at most once, by the flush thread's initialisation; see {@link #readiness()}. */
    private final CompletableFuture<Void> readiness = new CompletableFuture<>();
    /** The read-only view of {@link #readiness} handed out: a holder cannot complete it. */
    private final CompletionStage<Void> readinessView = readiness.minimalCompletionStage();
    /** Completed by {@link #markTerminated()}; see {@link #termination()}. */
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    /** The read-only view of {@link #termination} handed out: a holder cannot complete it. */
    private final CompletionStage<Void> terminationView = termination.minimalCompletionStage();
    /** Completed at most once, by {@link #announceEarlySeal()}; see {@link #earlySeal()}. */
    private final CompletableFuture<EarlySeal> earlySeal = new CompletableFuture<>();
    /** The read-only view of {@link #earlySeal} handed out: a holder cannot complete it. */
    private final CompletionStage<EarlySeal> earlySealView = earlySeal.minimalCompletionStage();

    private volatile boolean started;
    /**
     * The flush thread will never touch the take again: written by the
     * thread once every write to the files, the manifest, the captures and
     * the counters is done (after its rollback, when the take's
     * initialisation failed or was aborted) — or, for a thread that never
     * ran, by the stop or abort that came before any start, or by the
     * {@link #start()} whose thread could not be started. Its volatile write
     * and read are the happens-before edge for everything the thread wrote.
     */
    private volatile boolean terminated;
    /** The flush thread is draining: set just before {@link #readiness} completes normally, cleared when its loop ends. */
    private volatile boolean running;
    /** The stop flag the drain loop reads at each pass boundary; set by every request that ends the take. */
    private volatile boolean stopping;
    /** The take is to be discarded, not sealed ({@link #requestAbort()}). Written before {@link #stopping}. */
    private volatile boolean abortRequested;
    /** The writers are to be abandoned, not sealed ({@link #stopAndAbandon()}). Written before {@link #stopping}. */
    private volatile boolean abandonRequested;
    private volatile boolean drainPaused;
    /**
     * Set by the loop, between passes, each time it finds {@link #drainPaused}
     * set; {@link #setDrainPaused setDrainPaused(true)} clears it first and
     * then waits for it.
     */
    private volatile boolean drainPauseHeld;
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
    /**
     * The failure of the seal a stop requested; see {@link #stopSealFailure()}.
     * Written at most once, by the flush thread, before it terminates.
     */
    private volatile StopSealFailure stopSealFailure;
    /** Test seams; see {@link #setBlockObserver}, {@link #failNextManifestWrites}, {@link #failNextManifestWriteWith}. */
    private volatile BlockObserver blockObserver;
    private volatile int injectedManifestFaults;
    private volatile Throwable injectedManifestFault;

    // Flush-thread state.
    private boolean manifestDirty;
    /** The last manifest write failed on every attempt; cleared by the next one that lands. */
    private boolean manifestWriteFailing;
    private long lastManifestAttemptNanos;
    /** The manifest carrying the final seal status is on disk. */
    private boolean finalManifestWritten;
    /** The final sweep after the stop flag is running: the cadence tick stands down for the seal. */
    private boolean finalSweep;
    private boolean sealFailed;
    /** The lanes were sealed by {@link #sealEarly}, not by the seal a stop requested. */
    private boolean sealedEarly;
    /** The throwable behind an early seal for a write failure; {@code null} for the headroom floor. */
    private Throwable earlySealFailure;
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
     * Starts the take's flush thread and returns. Caller thread (FX in the
     * app): it touches no storage and never waits. The take's files are
     * created by the thread itself, as its first act — the take directory,
     * every lane-0 session (each creates {@code <trackId>/segment-000.wav.part}),
     * the initial manifest — and {@link #readiness()} reports how that went.
     * If the thread cannot be started, the service is marked terminated,
     * {@link #readiness()} completes exceptionally with the same throwable,
     * and that throwable propagates; nothing was created.
     *
     * @throws IllegalStateException if already started, or a stop or an abort was requested
     *                               before it was started
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("capture-flush already started");
        }
        if (stopping) {
            throw new IllegalStateException("capture-flush was stopped before it was started");
        }
        started = true;
        try {
            thread.start();
        } catch (RuntimeException | Error e) {
            markTerminated();
            readiness.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Records that the flush thread will never touch the take again and
     * completes {@link #termination()}. Called by the flush thread once it
     * is done with the take, or on the caller thread for a thread that never
     * ran. Idempotent.
     */
    private void markTerminated() {
        terminated = true;
        termination.complete(null);
    }

    /**
     * A service whose thread was never started: nothing was created and
     * nothing ever will be, so it is marked terminated, and
     * {@link #readiness()} — which no thread will complete normally now —
     * completes exceptionally with a {@link CancellationException}. Caller
     * thread. Idempotent.
     */
    private void retireUnstarted() {
        markTerminated();
        readiness.completeExceptionally(new CancellationException("capture-flush was stopped before it was"
                + " started; nothing was created under " + config.takeDirectory()));
    }

    /**
     * The take's initialisation — the flush thread's first act: creates the
     * take directory if it is missing, starts every lane-0 session, and
     * writes the initial manifest in a single attempt (class note). All or
     * nothing: when a step fails, or an abort was requested before it began
     * or before its last check, the segment and manifest files this take
     * created are deleted again, and each track directory that leaves empty
     * ({@link #discardTake()}; best-effort: what an I/O error keeps from
     * being deleted is left and the error logged), and the reason is
     * returned — an {@link IOException} wrapped as an
     * {@link UncheckedIOException}, a {@link RuntimeException} or an
     * {@link Error} as thrown, or a {@link CancellationException} for the
     * abort, carrying any other throwable of the rollback as suppressed. The
     * rollback deletes the segment files this take opened, and the manifest
     * only once this take has attempted to write it, so an initialisation
     * that fails on a segment an earlier take left in the same directory
     * leaves that take's segments and manifest as they were. The take
     * directory is left in place, even when this initialisation had to
     * create it: it is its caller's to remove. Flush thread.
     *
     * @return {@code null} when the take is ready to drain, else why it is not
     */
    private Throwable initialiseTake() {
        if (abortRequested) {
            // Nothing was created yet, and nothing will be.
            return new CancellationException(abortedBeforeReadinessMessage("it had created nothing"));
        }
        Throwable failure;
        try {
            Files.createDirectories(config.takeDirectory());
            for (TrackCapture capture : captures) {
                capture.setSegmentEvents(segmentEvents);
            }
            for (TrackCapture capture : captures) {
                capture.startLane();
            }
            writeManifestOnce(manifest.build());
            if (!abortRequested) {
                return null;
            }
            // An abort that came while the take was being created: the
            // readiness it would have completed is never completed normally.
            // The message says what the rollback below does; readiness fails
            // with it only once that rollback has run (runLoop).
            failure = new CancellationException(abortedBeforeReadinessMessage("its rollback deletes the segment"
                    + " and manifest files it had created, and each track directory that leaves empty, and leaves"
                    + " the take directory in place (what an I/O error keeps from being deleted is left and the"
                    + " error logged; any other throwable of the rollback is attached as suppressed)"));
        } catch (IOException e) {
            failure = new UncheckedIOException("cannot start capture under " + config.takeDirectory(), e);
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        try {
            discardTake();
        } catch (RuntimeException | Error rollbackFailure) {
            if (rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
        return failure;
    }

    private String abortedBeforeReadinessMessage(String outcome) {
        return "capture-flush was aborted before the take under " + config.takeDirectory() + " was ready; " + outcome;
    }

    /**
     * Deletes the segment and manifest files this take created, and each
     * track directory that leaves empty: each capture's segments, sealed and
     * streaming, and its track directory if that is empty afterwards
     * ({@link TrackCapture#discardAllFiles()}), then the manifest and its
     * staging file, if this take wrote or tried to write them. The take
     * directory is left in place. Every capture gets its turn whatever an
     * earlier one threw; the first throwable is rethrown at the end, with any
     * later one attached as suppressed. Flush thread: the rollback of a
     * failed or aborted initialisation, and the exit of a drain loop ended by
     * {@link #requestAbort()}.
     */
    private void discardTake() {
        Throwable first = null;
        for (TrackCapture capture : captures) {
            try {
                capture.discardAllFiles();
            } catch (RuntimeException | Error e) {
                if (first == null) {
                    first = e;
                } else if (e != first) {
                    first.addSuppressed(e);
                }
            }
        }
        deleteManifestFiles();
        if (first instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (first instanceof Error error) {
            throw error;
        }
    }

    /**
     * Wakes the flush thread. Audio-callback side: one {@code unpark}, no
     * allocation, never blocks — the same call the ASIO shim's callback makes.
     */
    public void signal() {
        LockSupport.unpark(thread);
    }

    /**
     * The flush thread: the take's initialisation, then the drain loop, then
     * the exit the stop flag asked for — the final sweep and the seal, the
     * discard of the take's segment and manifest files
     * ({@link #requestAbort()}), or the abandonment of the writers
     * ({@link #stopAndAbandon()}).
     *
     * <p>Once the thread runs, readiness is decided here and nowhere else. An
     * initialisation that failed or was aborted has rolled back by the time
     * it returns; the thread then marks itself terminated and only after that
     * completes {@link #readiness()} exceptionally, so a dependent that sees
     * the failure finds the rollback done and {@link #isTerminated()} true. One
     * that succeeded completes it normally and drains; an abort requested
     * after that point ends the loop at its next pass boundary, and the take
     * is discarded then.</p>
     */
    private void runLoop() {
        Throwable notReady = initialiseTake();
        if (notReady != null) {
            markTerminated();
            readiness.completeExceptionally(notReady);
            return;
        }
        running = true;
        readiness.complete(null);
        try {
            while (!stopping) {
                if (drainPaused) {
                    // Between passes: this is where setDrainPaused(true) waits for the loop to be.
                    drainPauseHeld = true;
                    LockSupport.parkNanos(this, PARK_BACKSTOP_NANOS);
                } else if (!drainOnce()) {
                    LockSupport.parkNanos(this, PARK_BACKSTOP_NANOS);
                }
            }
            if (!abortRequested && !abandonRequested) {
                // The seal right behind this sweep opens with a force(true)
                // of every segment it seals; a cadence tick in the sweep
                // would only put a force(false) in front of it.
                finalSweep = true;
                drainOnce();
                sealAll(requestedReason);
            }
        } catch (Throwable t) {
            lastFailure = t;
            if (abortRequested) {
                // The take is being discarded: sealing it first would only
                // complete an early-seal signal for files about to go.
                LOG.log(Level.SEVERE, "capture-flush loop failed while its take was being discarded", t);
            } else {
                LOG.log(Level.SEVERE, "capture-flush loop failed; sealing the take", t);
                warn("Recording stopped unexpectedly — " + describe(t)
                        + "; sealing everything captured so far under " + config.takeDirectory());
                try {
                    // Re-entry is safe: lanes already sealed are left alone, and
                    // a final manifest that did not reach the disk is written now.
                    sealEarly(SealedBy.WRITE_FAILURE, t);
                } catch (Throwable sealFailure) {
                    LOG.log(Level.SEVERE, "capture-flush could not seal after a loop failure", sealFailure);
                }
                // Before the thread terminates; nothing if a stop's seal sealed the lanes.
                announceEarlySeal();
            }
        } finally {
            try {
                if (abortRequested) {
                    discardTake();
                } else if (abandonRequested) {
                    abandonWriters();
                }
            } catch (Throwable exitFailure) {
                lastFailure = exitFailure;
                LOG.log(Level.SEVERE, "capture-flush could not " + (abortRequested ? "discard" : "abandon")
                        + " the take under " + config.takeDirectory(), exitFailure);
            } finally {
                running = false;
                // Last: every path out of the drain — a normal seal, a take
                // sealed early earlier on, a throwable that escaped the loop,
                // a lane's Error rethrown by the seal, a discard, an
                // abandonment — ends here.
                markTerminated();
            }
        }
    }

    /**
     * Closes every lane's writer with no seal and no rename
     * ({@link TrackCapture#abandonWithoutSeal()}): a writer still streaming
     * leaves its segment a {@code .part} carrying the streaming sentinel.
     * Every lane gets its turn. Flush thread, at the exit of a drain loop
     * ended by {@link #stopAndAbandon()}.
     */
    private void abandonWriters() {
        for (TrackCapture capture : captures) {
            try {
                capture.abandonWithoutSeal();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "could not abandon the writer of track " + capture.trackId(), e);
            }
        }
    }

    /**
     * Drains every published slot in order, running the cadence tick
     * ({@link #forceDueSegments()}) after each block and once more at the
     * end of the pass; returns whether any block was applied.
     */
    private boolean drainOnce() {
        boolean progressed = false;
        CaptureRing.Slot slot;
        while ((slot = ring.peek()) != null) {
            progressed = true;
            applyBlock(slot);
            // Before the release and the count, so awaitFlushed covers it.
            forceDueSegments();
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
        forceDueSegments(); // a dry ring's pass forces on cadence too
        if (manifestDirty) {
            flushManifest(); // the idle tick of a manifest that could not be written
        }
        return progressed;
    }

    /**
     * The cadence tick (book §2.1, §4.3): forces every active segment whose
     * un-forced bytes are due — bytes appended since its last force, and the
     * force cadence elapsed on the writer's clock since that force (or the
     * open) ({@link RecordingSession#forceIfCadenceElapsed()}) — whether or
     * not the block just applied appended anything to it. It runs after
     * every block and at the end of every pass ({@link #drainOnce()}), so
     * bytes are forced on cadence when their track records nothing more (a
     * block outside the punch region or the legacy range, or one without the
     * track's instrument source), while the ring is dry (the pass after each
     * park backstop), and during a long pass that never finds the ring
     * empty. It stands down once the take is sealed, and in the final sweep,
     * whose seal opens with a {@code force(true)} of every segment it seals.
     * A force that throws is answered exactly like a failed append: with
     * {@link #failAndSeal(Throwable)}, the call {@link #applyBlock} makes.
     * Flush thread.
     */
    private void forceDueSegments() {
        if (sealed || finalSweep) {
            return;
        }
        try {
            for (TrackCapture capture : captures) {
                RecordingSession session = capture.session();
                if (session != null) {
                    session.forceIfCadenceElapsed();
                }
            }
        } catch (Throwable failure) {
            failAndSeal(failure);
        }
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
                sealEarly(SealedBy.DISK_EXHAUSTION, null);
                // Reached when the seal returned; a seal that threw (a lane's
                // Error) is announced by failAndSeal, after its re-entry.
                announceEarlySeal();
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
            sealEarly(SealedBy.WRITE_FAILURE, failure);
        } catch (Throwable sealFailure) {
            LOG.log(Level.SEVERE, "capture-flush could not seal after a write failure", sealFailure);
        }
        announceEarlySeal();
    }

    /**
     * Seals the take on the flush thread's own account — the headroom floor,
     * a write failure, a throwable that escaped the drain loop — rather than
     * for a stop. A call that finds the take unsealed records it as sealed
     * early, with {@code failure}, and seals it; a call on a take already
     * sealed — early, or by the seal a stop requested — only re-enters
     * {@link #sealAll}, whose first reason sticks. Flush thread.
     *
     * @param failure the throwable behind a {@link SealedBy#WRITE_FAILURE};
     *                {@code null} for {@link SealedBy#DISK_EXHAUSTION}
     */
    private void sealEarly(SealedBy reason, Throwable failure) {
        if (!sealed) {
            sealedEarly = true;
            earlySealFailure = failure;
        }
        sealAll(reason);
    }

    /**
     * Completes {@link #earlySeal()} for a take {@link #sealEarly} sealed,
     * once that seal is done: every lane's seal attempted and the final
     * manifest write attempted. Each path that seals early calls it last:
     * the headroom branch of {@link #applyBlock} once {@code sealAll} has
     * returned, and {@link #failAndSeal} and the loop's failure handler once
     * their own seal call has returned or thrown — {@code failAndSeal} also
     * finishes a headroom seal that threw. Does
     * nothing for a take a stop's seal sealed, and nothing a second time.
     * Signals only: stopping the take is the caller's (book §2.3). Flush
     * thread, before it terminates.
     */
    private void announceEarlySeal() {
        if (!sealedEarly || earlySeal.isDone()) {
            return;
        }
        boolean everySegmentSealed = captures.stream().noneMatch(TrackCapture::hasUnsealedSegment);
        EarlySeal seal = sealReason == SealedBy.DISK_EXHAUSTION
                ? new EarlySeal.DiskExhausted(headroom.floorBytes(), headroom.isExhaustedByProbeFailures(),
                        everySegmentSealed)
                : new EarlySeal.WriteFailed(earlySealFailure, everySegmentSealed);
        earlySeal.complete(seal);
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
     *
     * <p>When that first call is the seal a stop requested — no early seal
     * sealed the lanes ({@link #sealEarly}) — and a lane threw, the first
     * throwable a lane threw is recorded for {@link #stopSealFailure()} once
     * every lane has had its attempt, before the final manifest write. An
     * early seal records nothing there: {@link #earlySeal()} reports it.</p>
     */
    private void sealAll(SealedBy reason) {
        Error firstLaneError = null;
        if (!sealed) {
            sealed = true;
            sealReason = reason;
            Throwable firstLaneFailure = null;
            for (TrackCapture capture : captures) {
                try {
                    capture.finalizeLane(config.loopRecord(), false);
                } catch (RuntimeException e) {
                    sealFailed = true;
                    lastFailure = e;
                    if (firstLaneFailure == null) {
                        firstLaneFailure = e;
                    }
                    LOG.log(Level.SEVERE, "seal failed for track " + capture.trackId()
                            + "; its streaming segment is left for recovery", e);
                    warn("Could not seal a segment of track " + capture.trackName()
                            + "; the streaming file is left under " + capture.trackDirectory());
                } catch (Error e) {
                    // Postponed, not absorbed: the lanes after this one are
                    // owed their seal, and the manifest its final write.
                    sealFailed = true;
                    lastFailure = e;
                    if (firstLaneFailure == null) {
                        firstLaneFailure = e;
                    }
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
            if (firstLaneFailure != null && !sealedEarly) {
                // The seal a stop requested: earlySeal() never completes for
                // it, so this is how its failure reaches the caller.
                boolean everySegmentSealed = captures.stream().noneMatch(TrackCapture::hasUnsealedSegment);
                stopSealFailure = new StopSealFailure(firstLaneFailure, everySegmentSealed);
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

    /** One write attempt, no failure handling: the take's initialisation, and each attempt of {@link #writeManifest(int)}. Flush thread. */
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
     * throws is logged with the message and goes no further. Flush thread.
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
     * — routed, written, counted, its manifest rewrite done and the cadence
     * tick after it run — and every ring overflow counted before this call
     * has its {@code gap=} line recorded, or the timeout elapses. The
     * end-of-pass cadence tick is not part of the fence. "Manifest rewrite
     * done" means attempted, or — while an earlier manifest failure is still being
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
     * Asks the flush thread to stop and seal the take, and returns at once:
     * it sets the stop flag and wakes the thread, and it never waits for it.
     * The thread ends its drain loop at the next pass boundary; its final
     * sweep drains the ring, seals every active writer and writes the final
     * manifest (a final manifest that cannot be written is logged SEVERE and
     * leaves the last written one on disk; the segments are sealed
     * regardless), and then it terminates — {@link #termination()} completes,
     * and only from then on may the caller read what the thread wrote
     * ({@link #sealedSegmentPaths()}, the captures). Caller thread (FX in the
     * app); no storage I/O.
     *
     * <p>Idempotent: the first stop request's reason is the one requested,
     * and a take already sealed early (exhaustion, write failure) keeps its
     * reason. After {@link #requestAbort()} or {@link #stopAndAbandon()} it
     * changes nothing: the take is discarded, or its writers abandoned. A
     * service that was never started has nothing to seal: the call marks it
     * terminated, {@link #readiness()} completes exceptionally with a
     * {@link CancellationException}, and it can no longer be started.</p>
     *
     * @param reason what ended the take ({@link SealedBy#STOP} on the normal path)
     */
    public void requestStop(SealedBy reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (!stopping) {
            requestedReason = reason;
            stopping = true;
        }
        wakeOrRetire();
    }

    /**
     * Asks the flush thread to discard the take, and returns at once: it
     * sets the abort and stop flags and wakes the thread, and it never waits
     * for it. Meant for a start that is abandoned — cancelled before capture
     * began, or rolled back after beginning capture failed. The flush thread
     * deletes the segment and manifest files it created for the take, and
     * each track directory that leaves empty — each capture's segments,
     * sealed and streaming, and the manifest if this take wrote or tried to
     * write it, never an earlier take's files — and then terminates:
     * {@link #termination()} completes after the deletions. The take
     * directory, even one the initialisation had to create, is left for its
     * caller. The abort seals nothing, and {@link #earlySeal()} and
     * {@link #stopSealFailure()} report nothing for it.
     *
     * <p>The initialisation looks at the abort when it begins and once more
     * after the take's files are created. An abort it sees makes it delete
     * the files it created, terminate, and only then complete
     * {@link #readiness()} exceptionally, with a
     * {@link CancellationException} (or with the initialisation's own
     * failure, if it failed). An abort that comes after that last look —
     * readiness has completed normally, or is about to — ends the drain loop
     * at its next pass boundary instead, and the take is discarded the same
     * way: a readiness that completed normally is never proof that capture
     * is still wanted, which the caller decides. If the flush thread had
     * already ended on its own before the abort — a throwable that escaped
     * its drain loop sealed the take early — the call deletes nothing: the
     * take stays as it was sealed early, and {@link #termination()} has
     * already completed. A service that was never started has nothing to
     * delete: the call marks it terminated, {@link #readiness()} completes
     * exceptionally with a {@link CancellationException}, and it can no
     * longer be started.</p>
     *
     * <p>A take whose stop was requested first is sealed, not discarded: the
     * call then changes nothing. Idempotent. Caller thread (FX in the app);
     * no storage I/O.</p>
     */
    public void requestAbort() {
        if (stopping && !abortRequested) {
            return;
        }
        abortRequested = true;
        stopping = true;
        wakeOrRetire();
    }

    /** Wakes the flush thread to read the stop flag; retires a service whose thread was never started. */
    private void wakeOrRetire() {
        if (started) {
            LockSupport.unpark(thread);
        } else {
            retireUnstarted();
        }
    }

    /**
     * Test seam: asks the flush thread to stop WITHOUT the final sweep or any
     * seal, and returns at once. The loop ends at its next pass boundary,
     * and the flush thread then abandons every writer itself
     * ({@link TrackCapture#abandonWithoutSeal()}): a writer still streaming
     * has its channel closed with no size patch and no rename, so its
     * segment stays a {@code .part} carrying the streaming sentinel, and the
     * manifest on disk stays the last one written before the loop ended.
     * {@link #termination()} completes once every writer is abandoned. An
     * orderly stop, not a process kill: this JVM goes on running and closes
     * the files itself. After a stop or an abort was requested it changes
     * nothing. Any thread.
     */
    void stopAndAbandon() {
        if (stopping) {
            return;
        }
        abandonRequested = true;
        stopping = true;
        wakeOrRetire();
    }

    /**
     * Test seam: {@code true} holds the loop between drain passes, so the
     * ring can be filled — and an injected clock moved — deliberately, with
     * no pass (and so no cadence tick) running meanwhile; {@code false}
     * releases it. {@code setDrainPaused(true)} returns once the loop holds,
     * so a pass that was running when it was called has ended; it does not
     * wait when the flush thread is not draining (not started yet, still
     * initialising the take, or finished) — the loop holds at its first pass
     * once it begins — once a stop has been requested (the final sweep drains
     * whether or not the loop is held), or when called on the flush thread
     * itself. Any thread.
     *
     * @throws IllegalStateException if the loop does not hold within
     *                               {@link #DEFAULT_AWAIT_TIMEOUT}
     */
    void setDrainPaused(boolean paused) {
        if (!paused) {
            drainPaused = false;
            LockSupport.unpark(thread);
            return;
        }
        drainPauseHeld = false;
        drainPaused = true;
        if (Thread.currentThread() == thread) {
            return;
        }
        LockSupport.unpark(thread);
        long deadline = System.nanoTime() + DEFAULT_AWAIT_TIMEOUT.toNanos();
        while (!drainPauseHeld && running && !stopping) {
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("capture-flush did not hold its drain within "
                        + DEFAULT_AWAIT_TIMEOUT);
            }
            LockSupport.parkNanos(AWAIT_POLL_NANOS);
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

    /**
     * Returns every track's sealed segment paths (all lanes, manifest order),
     * keyed by track id. Final once the flush thread has terminated
     * ({@link #isTerminated()}); read before that, it lists only the lanes
     * finalised so far — a lane's segments, rotated ones included, are
     * listed once its seal has finished.
     */
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

    /**
     * Returns whether the flush thread is draining: from the moment the
     * take's initialisation has completed — just before {@link #readiness()}
     * completes normally — until its drain loop ends. Any thread.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns whether the flush thread has terminated: it will never touch
     * the take — files, manifest, captures, counters — again, and everything
     * it wrote is visible to the thread that reads {@code true} here. For a
     * take whose initialisation failed or was aborted: once its rollback is
     * done. For a service whose thread never ran: once a
     * stop or an abort came before any start, or its thread could not be
     * started. Any thread.
     */
    public boolean isTerminated() {
        return terminated;
    }

    /**
     * Returns the readiness signal of the take: it completes normally, at
     * most once, on the flush thread, once the take's initialisation is done
     * — the take directory created if it was missing, every lane-0 session
     * started (each {@code <trackId>/segment-000.wav.part} exists) and the
     * initial manifest written — and the thread is draining
     * ({@link #isRunning()}). It completes exceptionally instead when the
     * initialisation fails or sees an abort ({@link #requestAbort()}), and
     * then only once the flush thread has rolled back the take's files —
     * deleting the segment and manifest files the take created, and each
     * track directory that leaves empty (best-effort: what an I/O error keeps
     * from being deleted is left and the error logged); the take directory,
     * even one the initialisation had to create, is left for its caller —
     * and has terminated ({@link #isTerminated()},
     * {@link #termination()}): with an {@link UncheckedIOException} wrapping
     * the {@link IOException} of a directory, segment or manifest that could
     * not be created; with a {@link RuntimeException} or an {@link Error} as
     * thrown (an {@link IllegalArgumentException} for an unsupported bit
     * depth, say); or with a {@link CancellationException} for the abort. An
     * abort that comes after the initialisation's last look at it does not
     * fail the signal: the signal completes normally, and the take is
     * discarded once the drain loop sees the abort ({@link #requestAbort()}).
     * A service whose thread never ran fails it too: with a
     * {@link CancellationException} when a stop or an abort came before any
     * start, or with the throwable of a thread that could not be started. So
     * it always completes, and no dependent waits forever. A holder cannot
     * complete it.
     *
     * <p>Readiness that completed normally is not a promise that capture
     * should begin: an abort requested after it still discards the take —
     * unless the flush thread has ended on its own by then
     * ({@link #requestAbort()}) — and whether capture begins is the caller's
     * decision.</p>
     *
     * @return the signal; a dependent registered with a non-async method runs
     *         on the flush thread, or on the registering thread if the signal
     *         has completed already — anything more than a hand-off belongs
     *         in an async variant with the executor of the thread that is to
     *         do it
     */
    public CompletionStage<Void> readiness() {
        return readinessView;
    }

    /**
     * Returns whether {@link #readiness()} has completed normally — a
     * non-blocking snapshot for the caller that begins capture. Any thread.
     */
    boolean isReady() {
        return readiness.isDone() && !readiness.isCompletedExceptionally();
    }

    /**
     * Returns the signal that completes normally once {@link #isTerminated()}
     * is {@code true}: on every path that ends the thread — the normal seal,
     * a take sealed early by disk exhaustion or a write failure and then
     * stopped, a throwable that escaped the drain loop, a lane's {@code Error}
     * rethrown by the seal, an abort ({@link #requestAbort()}, once the
     * take's discard is done), the {@code stopAndAbandon} test seam (once
     * the writers are abandoned), an initialisation that failed or was
     * aborted (once its rollback is done, and before {@link #readiness()}
     * fails) — and, for a thread that never ran, when a stop or an abort
     * comes before any start or the thread cannot be started. It never
     * completes exceptionally, and a holder cannot complete it. A dependent
     * registered with a non-async method may run on the flush thread as its
     * last act, or on the registering thread if the signal has completed
     * already; anything that is more than a hand-off belongs in an async
     * variant with the executor of the thread that is to do it.
     */
    public CompletionStage<Void> termination() {
        return terminationView;
    }

    /**
     * Returns the signal that the flush thread sealed this take on its own
     * ({@link EarlySeal}): the disk-headroom watch reported EXHAUSTED before
     * a block was written, applying a block or forcing a segment on cadence
     * threw, or a throwable escaped the drain loop. It completes normally,
     * at most once, on the flush thread, when that early seal is done —
     * every lane's seal attempted and the final manifest write attempted,
     * also when the seal rethrew a lane's {@code Error} — and so before
     * {@link #termination()}. A stop requested before the early seal does
     * not prevent it: an exhaustion or a write failure in the final sweep
     * completes it too, the seal the stop then requests keeps the early
     * seal's reason, and the stop returns the take as sealed early. Only the
     * draining thread seals early, so it never completes before
     * {@link #readiness()} has completed normally. It never completes for
     * the seal a stop requests (whatever that seal's outcome; a lane that
     * threw in it is reported by {@link #stopSealFailure()}), for
     * {@link #requestAbort()} or for the {@code stopAndAbandon} test seam, never on the audio
     * thread and never exceptionally, and a holder cannot complete it. The
     * flush thread only signals: after an early seal it keeps draining,
     * discarding every later block ({@link #discardedBlocks()}), until a
     * stop — or, when the seal answered a throwable that escaped the drain
     * loop, it terminates. A dependent registered with a non-async method
     * runs on the flush thread, or on the registering thread if the signal
     * has completed already; anything more than a hand-off belongs in an
     * async variant with the executor of the thread that is to do it.
     */
    public CompletionStage<EarlySeal> earlySeal() {
        return earlySealView;
    }

    /**
     * Returns the failure of the seal a stop requested ({@link StopSealFailure}),
     * if a lane's seal threw in it: its rename or one of its forces failed —
     * that segment is left as its {@code .part} for recovery — an empty
     * segment could not be discarded, or anything else threw while the lane
     * was sealed, an {@link Error} included. The value carries the first
     * throwable a lane threw, and the final manifest the flush thread writes
     * reads {@code seal-status=aborted} with {@code sealed-by=write-failure};
     * if that write fails, the manifest on disk is the last one written.
     *
     * <p>Empty when no lane's seal threw in that seal, and always empty for
     * a take the flush thread sealed early — {@link #earlySeal()} reports
     * that one, also when the early seal came in the final sweep of a stop
     * already requested, and also when a lane's seal threw in that early
     * seal — for {@link #requestAbort()}, for the {@code stopAndAbandon} test
     * seam and for a take that never became ready.</p>
     *
     * <p>Written at most once, on the flush thread, by a volatile write made
     * once every lane has had its seal attempt and before the final manifest
     * write; the flush thread's later volatile write of {@link #isTerminated()}
     * follows it in program order. So a thread that has read
     * {@code isTerminated()} as {@code true} — as a dependent of
     * {@link #termination()} has — reads the final value here; read before
     * that, empty may only mean that the seal has not run yet. Never written
     * on the audio thread. Any thread.</p>
     */
    public Optional<StopSealFailure> stopSealFailure() {
        return Optional.ofNullable(stopSealFailure);
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
