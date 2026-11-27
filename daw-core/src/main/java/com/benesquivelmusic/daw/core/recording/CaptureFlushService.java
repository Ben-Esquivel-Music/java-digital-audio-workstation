package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputNames;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.recording.TakeManifest.GapEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

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
import java.util.concurrent.atomic.AtomicLongArray;
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
 *       the sessions' staging blocks, the lanes' peak mirrors, the loop-take
 *       stacks and the ring's read
 *       index — and the only thread that creates or deletes the take's
 *       segment and manifest files, and the only one that calls the peak
 *       sink. Its first act is the take's
 *       initialisation: it creates the take directory, starts every lane-0
 *       session (each creates {@code <trackId>/segment-000.wav.part}), in
 *       loop-record pre-opens every track's lane 1
 *       ({@code segment-001.wav.part}, empty) and
 *       writes the initial manifest, and only then completes
 *       {@link #readiness()} and begins draining. Modeled on the ASIO shim's
 *       {@code asio-input-drain}: park when the ring is dry with a bounded
 *       {@link #PARK_BACKSTOP} backstop, drain in order, and after the stop
 *       flag a bounded wait for the callback in flight to leave the ring's
 *       producer gate ({@link #PRODUCER_QUIESCENCE_BOUND}), the final
 *       sweep, then the seal. Every pass — including one that finds the
 *       ring still dry after a park — runs the cadence tick (below) once more
 *       after the blocks it drained. When it seals the take on its own it
 *       completes {@link #earlySeal()} and does nothing more about it:
 *       stopping the take is the caller's. When an abort is requested it
 *       deletes, instead of sealing the take, the segment and manifest files
 *       it created for the take, and each track directory it created, if
 *       that leaves it empty, and leaves the take directory to its caller;
 *       an abort that comes after a stop request, or after the thread has
 *       ended on its own, deletes nothing ({@link #requestAbort()}).</li>
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
 * lap's clip ({@code TrackCapture.lapClips}) and makes the pre-opened next lane the current one
 * (below); (b) the disk-headroom check
 * ({@link DiskHeadroomWatch#check(long)}; EXHAUSTED seals the take before
 * the block is written); (c) punch gating with the header's start frame,
 * beat and punch snapshot — the sample-accurate slicing, 5 ms cosine fades,
 * {@code wasInsidePunchRegion} re-entry rule and the legacy beat-based
 * {@link PunchRange} gate are the pipeline's original code, moved here
 * verbatim; (d) routing per the snapshot in each {@link TrackCapture} (every
 * row of the routed block the source did not provide is zeroed, never
 * stale); (e) the session append, which forces on cadence and rotates on
 * exact counts; (f) the truncation note for a block that was longer than
 * its slot, and, once the block is kept, the {@code gap=} line of the run
 * the ring dropped right before it (ruling E273); (g) a manifest rewrite whenever a segment opened, sealed or was
 * discarded, or a truncation episode began; (h) the cadence tick, before the
 * block is released and counted.</p>
 *
 * <p><strong>Loop lanes are opened ahead</strong> (book §4.6). In
 * loop-record every track holds a started, empty session for its next lane
 * — a <em>standby</em>, listed in the manifest as a streaming segment of
 * that lane. It is opened by the take's initialisation and, after a wrap
 * has made it the current lane or a rotation of the current lane has taken
 * over its file, once the block that did so has been applied, before that
 * block is released and counted.
 * So a wrap seals, stacks and swaps, and opens no file; only when no
 * standby is held at the index the sealed lane left free — its start was
 * refused, or the lane ended on an empty segment — is the next lane opened
 * at the wrap. Whatever ends the take discards the standby no lap reached:
 * the seal deletes its file and removes its entry before the final manifest
 * is written — the entry also when the file cannot be deleted, which is
 * reported through the warning sink ({@link #discardStandbyLanes()}) — an
 * abort deletes it with the take's other files. Outside
 * loop-record no standby is opened.</p>
 *
 * <p><strong>Peak mirror</strong> (book §4.5). The frames each lane's
 * session records are folded into the lane's bounded peak mirror, and an
 * immutable {@link CapturePeakSnapshot} of every lane that gained frames is
 * handed to the peak sink at most once per {@link #PEAK_PUBLISH_INTERVAL}
 * of the take's clock, and once more when the lane is finalized — at a
 * loop wrap and at the seal. The sink is called on this thread only; like
 * the warning sink it is foreign code; whatever it throws — an
 * {@link Error} included — is logged, once per track, and goes no
 * further: it ends no take and replaces no seal's failure. With no peak
 * sink no snapshot is built.</p>
 *
 * <p><strong>Force cadence</strong> (book §2.1, §4.3). A segment's
 * un-forced bytes are {@code force(false)}d at the first check made once
 * the force cadence has elapsed, on the writer's clock, since its last
 * force (or its open; for a pre-opened loop lane, the wrap that made it
 * current) — by the append that finds it elapsed, and otherwise
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
 * <p><strong>Loss is recorded, never hidden.</strong> Every block a capture
 * ring publishes carries the ring's drop counts at its claim
 * ({@link CaptureRing#stampOf}), so for each block it keeps this thread knows
 * the run of blocks the ring dropped right before it and what they carried:
 * delivered input or a recorded instrument's audio, input-starved silence,
 * or the silence after an input's declared stop (story 338, rulings E273,
 * E277, E281 and E287). Once the block is kept — applied in full: written
 * to the file, or passed over outside the punch region — that run gets one
 * {@code gap=} line at the end of the block the ring delivered before it,
 * never merged across a delivered block and never split, counting its
 * blocks that carried audio a track records and its input-starved blocks
 * where an applied starved block would count; a run whose starved blocks
 * lead into more of the input's starved blocks keeps its line until that
 * starved run has ended, which a declared stop that flags not every track
 * of the input does not do (ruling E301), and the silence after a declared
 * stop gets nothing, unless a block of that input the take kept shows it
 * live again after it, as a reopened stream's input does: that block marks
 * the stop and lines the whole run (ruling E302).
 * A pass over a ring rewrites the manifest for the lines it added at most
 * once, at the look that ends it, and gives at most one ring-overflow
 * warning, for the first run it lined, whose total is the take's
 * {@code overflow-blocks} so far. A run no kept block follows gets nothing
 * while the take runs: at the person's Stop the seal lines it after the
 * blocks published behind the final sweep (below), and at a device-loss seal
 * from the counts the final sweep's look read, at the end of the last block
 * applied (ruling E275); a run before the block whose write failed or the
 * block the headroom floor discarded, and every run after an early seal,
 * gets nothing, on every ring when the early seal comes in a device-loss
 * seal's final sweep (rulings E274 and E286). Each look notes the ring's
 * drops for {@link #awaitFlushed}. A block longer than its ring slot keeps
 * the slot's worth of frames; the first such block of a run is recorded as
 * a {@code gap=*|<end of its kept frames>|0} line with a warning, and the
 * running total goes into {@code truncated-frames} with every later
 * manifest write (exact at the seal). A block published behind the final
 * sweep of a stop — possible only when the wait for the callback in flight
 * timed out, or the stop was requested without the ring's producer gate
 * being closed — is recorded as a {@code gap=} line at the end of the last
 * block applied, with a warning, if it is in the ring once the lanes have
 * been sealed and carried audio a track records; nothing behind a device
 * loss's final sweep, whose blocks are that loss's, nor after an early seal,
 * is recorded (rulings E242, E264 and E274).</p>
 *
 * <p><strong>Silence the input did not deliver is marked</strong> (story
 * 338). The render stamps each block of the stream's own input it rendered
 * with no captured block behind it ({@link CaptureRing.Slot#inputStarved()}).
 * Only the frames of such blocks the take's file received inside the punch
 * region count. A run of them that a delivered block ends is written as
 * {@code input-silence} entries per track of that input, cut at the loop end
 * so that no entry runs past it, and counted into the take's dropouts
 * ({@link #starvedInputBlocks()}); the run still open at the seal is written
 * there, up to the last block the file received, and counted unless a device
 * loss sealed the take; a run that ends in the input's stop is not written,
 * because the stop covers it. When an input ends mid-take its tracks' next
 * block has zero width: each track is flagged once, gets an
 * {@code input-stopped} entry at the end of the last block the input
 * delivered (the take's start frame if it delivered none, the punch region's
 * first frame in the lap of the input's own frames at the flagging block
 * when that end lies outside the region; inside the loop range in a loop
 * take, where the resume frame may lie before the stop), and one warning,
 * which names the lap in a loop-record take. When none of an input's silence
 * reaches the take before a seal by the person's Stop, or by a device loss
 * not sealed early in its final sweep, the seal gives each track of that
 * input the input-stopped line and warning the take would have written had
 * that silence reached it, saying the track holds no audio from there
 * (rulings E277, E282 to E284 and E286). Clock events handed in through
 * {@link #noteClockEvent} are written as {@code clock} entries on the next
 * pass, or at the seal.</p>
 *
 * <p><strong>Failure.</strong> An {@link UncheckedIOException} from a
 * session or any other {@link Throwable} while a block is applied or while
 * the cadence tick forces is logged SEVERE, reported through the warning
 * sink, and answered by an immediate clean seal with
 * {@code seal-status=aborted} /
 * {@code sealed-by=write-failure} ({@code disk-exhaustion} for the headroom
 * floor), after which the thread keeps draining until a stop: further
 * blocks are discarded and counted ({@link #discardedBlocks()}) so the
 * callback side never notices, and a run of blocks a ring dropped is
 * recorded only when a block the take kept from that ring follows it
 * (rulings E274 and E286). A throwable that
 * escapes the drain loop itself is reported the same way and ends the
 * thread after the seal. Each such early seal, once done, completes
 * {@link #earlySeal()}. A lane whose seal throws in the seal a stop
 * requested is no early seal: the take is already stopping, and
 * {@link #stopSealFailure()} reports it.</p>
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
 * segments are sealed whether or not the manifest can say so. A final
 * write that fails on every attempt is recorded for
 * {@link #finalManifestFailure()}, logged SEVERE, and told to the sink once
 * per take by seal kind: for an early seal, that the take was sealed early
 * and the write is retried — the thread keeps draining until a stop, its
 * idle passes retry the write, and the seal the stop then requests makes
 * the last attempt; for the thread's last seal — the seal a stop
 * requested, or the loop's failure handler's — in words that promise no
 * retry: the take has ended, the manifest on disk does not record the
 * seal, and the recovery scan ({@link TakeRecovery#scan}; story 332 runs
 * it on project open) rebuilds it from the segments. Only the
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

    /**
     * Longest wait, after a stop request, for a callback in flight to leave
     * the ring's producer gate before the final sweep: four
     * {@link #PARK_BACKSTOP} periods (200 ms). A callback is inside the gate
     * for one bounded copy — microseconds — so the wait normally ends at its
     * first look; the bound only matters for an audio thread that was
     * preempted inside the callback, which several backstop periods outlast,
     * or one that died or hangs there, which must not keep the take from
     * being sealed (book §6.1: no unbounded park on this thread).
     */
    public static final Duration PRODUCER_QUIESCENCE_BOUND = PARK_BACKSTOP.multipliedBy(4);

    /** Default bound for {@link #awaitFlushed(Duration)} callers that pass none. */
    public static final Duration DEFAULT_AWAIT_TIMEOUT = Duration.ofSeconds(10);

    /** Attempts one manifest write makes on the flush thread before it counts as failed. */
    public static final int MANIFEST_WRITE_ATTEMPTS = 5;

    /** Pause between the attempts of one manifest write. */
    public static final Duration MANIFEST_RETRY_PAUSE = Duration.ofMillis(20);

    /** Minimum spacing, on the take's clock, of the single-attempt retries that follow a failed manifest write. */
    public static final Duration MANIFEST_RETRY_INTERVAL = Duration.ofSeconds(1);

    /**
     * Least spacing, on the take's clock, of the peak snapshots handed to
     * the peak sink while a lane records (about 30 per second); a lane's
     * last snapshot, when it is finalized, does not wait for it.
     */
    public static final Duration PEAK_PUBLISH_INTERVAL = Duration.ofMillis(33);

    /**
     * Duration, in seconds, of the cosine crossfade at the punch-in and
     * punch-out boundaries. Public since story 328: the shortest punch region
     * a creation gesture may install is twice this (both ramps must exist).
     */
    public static final double PUNCH_CROSSFADE_SECONDS = 0.005;

    private static final Logger LOG = Logger.getLogger(CaptureFlushService.class.getName());
    private static final long PARK_BACKSTOP_NANOS = PARK_BACKSTOP.toNanos();
    private static final long AWAIT_POLL_NANOS = 200_000L;
    private static final long MANIFEST_RETRY_PAUSE_NANOS = MANIFEST_RETRY_PAUSE.toNanos();
    private static final long MANIFEST_RETRY_INTERVAL_NANOS = MANIFEST_RETRY_INTERVAL.toNanos();
    private static final long PEAK_PUBLISH_INTERVAL_NANOS = PEAK_PUBLISH_INTERVAL.toNanos();
    private static final long NOTHING_DELIVERED = -1L;

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

    private CaptureRing ring;
    private CaptureRing[] inputRings;
    private int inputSource;
    private double[] sourcePreviousBeat;
    private boolean[] sourceWasInsidePunchRegion;
    private long[] sourceLastOverflow;
    private long[] sourceLastAppliedEndFrame;
    private boolean[] sourceTruncationEpisode;
    private java.util.concurrent.atomic.AtomicLongArray sourceOverflowNoted;
    private int[] openedInputWidths;
    /** Per source, the end frame of the last block its input delivered, or {@link #NOTHING_DELIVERED}. */
    private long[] sourceDeliveredEndFrame = {NOTHING_DELIVERED};
    /**
     * Per source, how many times its ring's blocks stepped back in beats
     * while the loop was enabled: the lap of that input's own frames, which
     * for an input beside the stream a stall leaves behind the transport's
     * (ruling E186). Each ring is applied in its own order, so the count
     * does not depend on how the drain interleaves the rings. Known limit: a
     * seek backwards while the loop is enabled counts as a wrap, as it does
     * for the lanes. Known limit: an overflow of this ring a lap long or
     * longer can hide a wrap from this count, as it does from the lanes, which
     * then lag the transport by the laps it hid; the overflow's gap line, its
     * warning and the take's dropout count report it, and a stop flagged after
     * it takes its region start from the lap the lanes counted (ruling E190).
     */
    private long[] sourceWraps = {0L};
    /**
     * Per source, the lap its input's delivered end lies in: {@link #sourceWraps}
     * at the last block it delivered, plus the wraps that block ran across
     * before its end; an end on the loop end stays in the lap it completed
     * (ruling E176).
     */
    private long[] sourceWrapsAtDelivery = {0L};
    /** Per source, the run of input-starved blocks written since its last delivered block. */
    private StarvedRuns starvedRuns = new StarvedRuns(1);
    private FileLossCounts fileLosses = new FileLossCounts(1);
    /** Per source, its ring's runs of dropped blocks as this thread lines them (story 338, rulings E273 to E287). */
    private DroppedRuns[] droppedRuns = {new DroppedRuns()};
    /**
     * Per source, the blocks released by the end of the last look at its ring, or by the thread's exit: the
     * block half of the {@link #awaitFlushed} fence, which so covers the {@code gap=} lines a pass writes at its
     * look (ruling E273).
     */
    private AtomicLongArray sourceLookedBlocks = new AtomicLongArray(1);
    void setOpenedInputWidths(int[] widths) { openedInputWidths = widths.clone(); }
    void setInputRings(CaptureRing[] rings) {
        if (started) throw new IllegalStateException("input rings already started");
        inputRings = rings.clone();
        sourceDeliveredEndFrame = new long[rings.length];
        Arrays.fill(sourceDeliveredEndFrame, NOTHING_DELIVERED);
        sourceWraps = new long[rings.length];
        sourceWrapsAtDelivery = new long[rings.length];
        starvedRuns = new StarvedRuns(rings.length);
        fileLosses = new FileLossCounts(rings.length);
        sourcePreviousBeat = new double[rings.length];
        sourceWasInsidePunchRegion = new boolean[rings.length];
        Arrays.fill(sourcePreviousBeat, -1.0);
        sourceLastOverflow = new long[rings.length];
        droppedRuns = new DroppedRuns[rings.length];
        Arrays.setAll(droppedRuns, _ -> new DroppedRuns());
        sourceLookedBlocks = new AtomicLongArray(rings.length);
        sourceLastAppliedEndFrame = new long[rings.length];
        sourceTruncationEpisode = new boolean[rings.length];
        sourceOverflowNoted = new java.util.concurrent.atomic.AtomicLongArray(rings.length);
    }
    private final TakeConfig config;
    private final List<TrackCapture> captures;
    private final DiskHeadroomWatch headroom;
    private final Consumer<String> warningSink;
    private final Consumer<CapturePeakSnapshot> peakSink;
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
    /**
     * Overflow count this thread has noted for {@link #awaitFlushed}, at the look that ends each pass and at the
     * seal's look ({@link #accountLateDrops}), whatever becomes of the drops' lines (ruling E273): the other half
     * of that fence.
     */
    private volatile long overflowNoted;
    /** Blocks and frames of the closed input-starved runs written to the manifest; flush thread writes. */
    private volatile long starvedInputBlocks;
    private volatile long starvedInputFrames;
    /** Truncation episodes opened; flush thread writes. */
    private volatile long truncationEpisodes;
    /** Clock events handed in from any thread; the flush thread drains them into the manifest. */
    private final java.util.concurrent.ConcurrentLinkedQueue<TakeManifest.ClockMark> clockNotes =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long manifestWrites;
    private volatile TakeManifest lastManifest;
    private volatile Throwable lastFailure;
    /**
     * This take has attempted a manifest write, so the rollback deletes
     * {@code take.manifest} and its staging file: this take's once one of
     * its writes has landed, if nothing else writes there, and before that
     * whatever is at those names — an earlier take's manifest among them
     * (story 350).
     */
    private volatile boolean manifestTouched;
    /**
     * The failure of the seal a stop requested; see {@link #stopSealFailure()}.
     * Written at most once, by the flush thread, before it terminates.
     */
    private volatile StopSealFailure stopSealFailure;
    /**
     * The failure of the final manifest write; see {@link #finalManifestFailure()}.
     * Written by the flush thread, before it terminates: set by a final write
     * that failed on every attempt, cleared by one that landed.
     */
    private volatile FinalManifestFailure finalManifestFailure;
    /**
     * The flush thread has left its drain loop for a stop and is waiting for
     * the callback in flight to leave the ring's producer gate; see
     * {@link #isAwaitingProducerExit()}.
     */
    private volatile boolean awaitingProducerExit;
    /** Test seams; see {@link #setBlockObserver}, {@link #failNextManifestWrites}, {@link #failNextManifestWriteWith}. */
    private volatile BlockObserver blockObserver;
    private volatile int injectedManifestFaults;
    private volatile Throwable injectedManifestFault;
    /** Bound of the wait before the final sweep; see {@link #setProducerQuiescenceBound}. */
    private volatile long producerQuiescenceBoundNanos = PRODUCER_QUIESCENCE_BOUND.toNanos();

    // Flush-thread state.
    private boolean manifestDirty;
    /** The last manifest write failed on every attempt; cleared by the next one that lands. */
    private boolean manifestWriteFailing;
    private long lastManifestAttemptNanos;
    /** The manifest carrying the final seal status is on disk. */
    private boolean finalManifestWritten;
    /** The sink has been told that the take's last final manifest write failed, with no retry to come; said once per take. */
    private boolean finalManifestWarned;
    /** The sink has been told that an early seal's final manifest write failed and is retried; said once per take. */
    private boolean earlySealManifestWarned;
    /**
     * The seal running is the thread's last — the seal a stop requested, or
     * the loop failure handler's — so a final manifest write that fails in it
     * is retried by nothing. Before it, an early seal's failed final write is
     * retried by the idle passes and by the seal the stop then requests.
     */
    private boolean lastSeal;
    /** The final sweep after the stop flag is running: the cadence tick stands down for the seal. */
    private boolean finalSweep;
    /** The final sweep has drained the ring: a block published from here on is left behind. */
    private boolean finalSweepDone;
    /** {@link #noteBlocksLeftBehind()} has taken its one look at the ring. */
    private boolean leftBehindNoted;
    /** The seal has written the trailing starved runs and the last clock events. */
    private boolean sealMarksWritten;
    private boolean sealFailed;
    /** The lanes were sealed by {@link #sealEarly}, not by the seal a stop requested. */
    private boolean sealedEarly;
    /** The throwable behind an early seal for a write failure; {@code null} for the headroom floor. */
    private Throwable earlySealFailure;
    private long lastOverflowSeen;
    /** Ring-overflow blocks that have a {@code gap=} line: what {@code overflow-blocks} records (ruling E273). */
    private long overflowBlocksLined;
    private long lastAppliedEndFrame;
    /** Lines of runs of dropped blocks in the manifest builder that no manifest write has carried yet. */
    private boolean runLinesUnwritten;
    /** The first run this pass lined, which the pass's one ring-overflow warning names (ruling E273). */
    private boolean passLinedARun;
    private long passRunFrame;
    private long passRunBlocks;
    /**
     * A stop is being written for silence of an input the take did not keep, dropped by a full ring or left behind
     * on it (ruling E284): at the seal, or in {@link #applyBlock} by the block with which the input delivers again,
     * whose words {@link #stopBeforeADelivery} picks (ruling E302); named when only the seal wrote such stops.
     */
    private boolean sealWritesTheStops;
    /** A delivery marks its dropped silence's stop (E302); plain: set, read and cleared on the flush thread alone. */
    private boolean stopBeforeADelivery;
    private long truncatedFramesNoted;
    private boolean truncationEpisodeOpen;
    private double previousBeat = -1.0;
    private boolean wasInsidePunchRegion;
    /** A rate-limited peak publication has happened; {@link #lastPeakPublishNanos} is its clock reading. */
    private boolean peaksPublished;
    private long lastPeakPublishNanos;

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

        /**
         * The file a standby lane held is now the rotating lane's next
         * segment: the manifest is written here, before the lane appends
         * its first frame to that file, so the manifest on disk does not go
         * on naming the file as lane + 1's while it fills with the lane's
         * audio. The write follows the retry rule of every other one
         * ({@link #flushManifest()}): while a manifest failure is being
         * retried it is one attempt per retry interval, so the manifest on
         * disk can then stay behind until a retry lands.
         */
        @Override
        public void onStandbyFileAdopted(TrackCapture capture, int lane, RecordingSegment segment) {
            flushManifest();
        }
    };

    /**
     * Creates the service and its (unstarted) thread, with no sink for peak
     * snapshots: none are delivered. Caller thread.
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
        this(ring, config, captures, headroom, warningSink, null, nanoClock);
    }

    /**
     * Creates the service and its (unstarted) thread, with a sink for the
     * lanes' peak snapshots. Caller thread.
     *
     * @param ring        the capture ring the callback fills
     * @param config      the take configuration
     * @param captures    the armed tracks' captures, in armed order, with unstarted lane-0 sessions
     * @param headroom    the disk-headroom watch to tick per block
     * @param warningSink warning sink, or {@code null} to log at WARNING
     * @param peakSink    receives every peak snapshot, on the flush thread, or
     *                    {@code null} for none: no snapshot is then built at all
     * @param nanoClock   monotonic clock for the headroom tick, the manifest retry interval
     *                    and the peak publication interval
     */
    CaptureFlushService(CaptureRing ring, TakeConfig config, List<TrackCapture> captures,
                        DiskHeadroomWatch headroom, Consumer<String> warningSink,
                        Consumer<CapturePeakSnapshot> peakSink, LongSupplier nanoClock) {
        this.peakSink = peakSink;
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
            manifest.addTrack(new TrackEntry(capture.trackId(), capture.routed().length, capture.compensationFrames()));
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
     * in loop-record each track's pre-opened lane-1 standby as well
     * ({@code segment-001.wav.part}, listed in the manifest),
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
     * created are deleted again, and each track directory this take created,
     * if that leaves it empty ({@link #discardTake()}; best-effort: what an
     * I/O error keeps from being deleted is left and the error logged), and
     * the reason is returned — an {@link IOException} wrapped as an
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
                if (peakSink != null) {
                    // With no sink the captures keep none, and build no snapshot.
                    capture.setPeakSink(peakSink);
                }
            }
            for (TrackCapture capture : captures) {
                capture.startLane();
            }
            if (config.loopRecord()) {
                // Lane 1 is opened here, long before the first wrap needs it.
                for (TrackCapture capture : captures) {
                    capture.prepareStandby();
                }
            }
            if (openedInputWidths != null) for (TrackCapture capture : captures) {
                if (!capture.isInstrument() && (long) capture.routing().firstChannel() + capture.routing().channelCount()
                        > openedInputWidths[capture.inputSource()]) flagNarrowRoute(capture, openedInputWidths[capture.inputSource()]);
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
                    + " and manifest files it had created, and each track directory it had created, if that leaves"
                    + " it empty, and leaves the take directory in place (what an I/O error keeps from being"
                    + " deleted is left and the error logged; any other throwable of the rollback is attached as"
                    + " suppressed)"));
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
     * track directory it created, if that leaves it empty: each capture's
     * segments, sealed and streaming, and its track directory if the start
     * of one of its lane sessions created it and it is empty afterwards
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
    @RealTimeSafe
    public void signal() {
        LockSupport.unpark(thread);
    }

    /**
     * The flush thread: the take's initialisation, then the drain loop, then
     * the exit the stop flag asked for — the final sweep and the seal, the
     * discard of the take's segment and manifest files
     * ({@link #requestAbort()}), or the abandonment of the writers
     * ({@link #stopAndAbandon()}). Only the seal reads the ring once more,
     * so only it waits for the callback in flight first
     * ({@link #awaitProducerQuiescence()}); a discard and an abandonment
     * read nothing a callback writes.
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
                // The fence: the sweep below is the last look at the ring,
                // so it waits for the callback in flight to be out first.
                awaitProducerQuiescence();
                // The seal right behind this sweep opens with a force(true)
                // of every segment it seals; a cadence tick in the sweep
                // would only put a force(false) in front of it.
                finalSweep = true;
                drainOnce();
                finalSweepDone = true;
                // The thread's last seal: a final manifest write that fails
                // in it is retried by nothing.
                lastSeal = true;
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
                // Before the early seal's warning: a run the failing pass lined keeps its own (rulings E274, E281).
                lineThePassBeforeTheEarlySeal();
                warn("Recording stopped unexpectedly — " + describe(t)
                        + "; sealing everything captured so far under " + config.takeDirectory());
                try {
                    // Re-entry is safe: lanes already sealed are left alone, and
                    // a final manifest that did not reach the disk is written now
                    // — the thread's last write: it terminates after this.
                    lastSeal = true;
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
                // Done with every block it released: a fence read after the thread ended reads the rings.
                markEveryRingLooked();
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
     * The flush thread's half of the stop fence (book §5.2 FINALIZING:
     * deregister → drain → seal): waits, for at most the quiescence bound
     * ({@link #PRODUCER_QUIESCENCE_BOUND}), until no callback is inside the
     * ring's producer gate ({@link CaptureRing#awaitProducerQuiescent(long)}).
     * The pipeline closes that gate before it requests the stop, so once the
     * wait has succeeded nothing is published again and the final sweep that
     * follows drains every block of the take. If the bound elapses with a
     * callback still inside, the sink is warned and the thread goes on to
     * sweep and seal. Which blocks the take then holds is decided by the
     * final sweep, not by the warning: a block that callback publishes
     * before the sweep's last look at the ring is applied and is part of
     * the take, or is discarded when the take was sealed early; one it
     * publishes after that look is not part of the take, and is recorded
     * as a gap by {@link #noteBlocksLeftBehind()} if it is in the ring
     * when that method looks and carried audio a track records, except
     * after an early seal or at a device-loss seal, where nothing records it
     * (rulings E263, E264 and E277).
     * The warning describes a Stop's seal, whatever the seal. Flush
     * thread; the thread that requested the stop never waits.
     */
    private void awaitProducerQuiescence() {
        awaitingProducerExit = true;
        boolean quiescent;
        try {
            quiescent = true;
            if (inputRings == null) quiescent = ring.awaitProducerQuiescent(producerQuiescenceBoundNanos);
            else for (CaptureRing input : inputRings) quiescent &= input.awaitProducerQuiescent(producerQuiescenceBoundNanos);
        } finally {
            awaitingProducerExit = false;
        }
        if (!quiescent) {
            warn("The audio callback had not left the capture ring "
                    + Duration.ofNanos(producerQuiescenceBoundNanos).toMillis() + " ms after the capture-flush thread"
                    + " began waiting for it; the take under " + config.takeDirectory() + " is swept and sealed"
                    + " without waiting any longer: a block it publishes before the final sweep has read the ring"
                    + " is still part of the take, and one it publishes after that is not and is recorded in the"
                    + " take manifest as a gap if it has arrived by the time the lanes are sealed and carried audio"
                    + " a track records");
        }
    }

    /**
     * The seal's one look at the rings, once every lane has had its seal attempt and before the trailing starved
     * run is written, which an input-stopped line given here covers (ruling E49). At the person's Stop, per ring
     * ({@link #lineWhatTheSweepLeft}): the blocks published behind the final sweep — which need a wait for the
     * callback in flight that timed out ({@link #awaitProducerQuiescence()}), or a stop requested without the
     * ring's producer gate closed — that carried audio a track records get one {@code gap=} line at L, the end
     * of the last block applied from that ring, with their warning; the runs waiting on the input's open starved
     * run get their line; the drops no applied block follows get one at L plus the blocks left behind times the
     * ring's block length, whichever look found them, join {@code overflow-blocks} and get the pass's
     * ring-overflow warning, so the two lines cover the missing stretch once, whatever order its blocks came in
     * (rulings E242, E275 and E281). At a device-loss seal not sealed early in its final sweep, per ring
     * ({@link #lineTheDropsBeforeTheDeviceLoss}): the waiting runs and the drops the final sweep's look found,
     * from the counts it read (ruling E286). Each line it adds for drops no kept block follows counts in the
     * take's file losses by its own extent, or by the whole run's when silence came before audio in it (rulings
     * E104, E213 and E287); a waiting run keeps the judgement made when it was noticed (ruling E281). The
     * silence after an input's declared stop gets no line, and for its silence that did not reach the take after
     * the last block kept from its ring its tracks not yet flagged get their input-stopped line here (rulings
     * E277, E282 to E284 and E303). After an early seal it records, counts and warns of nothing (rulings E264,
     * E274 and E286); late drops are only accounted, for {@link #awaitFlushed}. Does nothing unless the final
     * sweep has completed, and nothing a second time; a block published or dropped after this look, or a drop
     * whose count its callback stores after it, is not recorded (ruling E255).
     */
    private void noteBlocksLeftBehind() {
        if (!finalSweepDone || leftBehindNoted) {
            return;
        }
        leftBehindNoted = true;
        int count = inputRings == null ? 1 : inputRings.length;
        for (int source = 0; source < count; source++) {
            CaptureRing input = inputRings == null ? ring : inputRings[source];
            long overflow = input.overflowCount();
            long accounted = inputRings == null ? lastOverflowSeen : sourceLastOverflow[source];
            accountLateDrops(source, overflow, Math.max(0L, overflow - accounted));
            if (sealedEarly) {
                continue;
            }
            inputSource = source;
            if (sealReason == SealedBy.DEVICE_LOSS) {
                lineTheDropsBeforeTheDeviceLoss(input, source);
            } else {
                lineWhatTheSweepLeft(input, source);
            }
            warnOfThePassRuns();
        }
        inputSource = 0;
    }

    /**
     * At the person's Stop, the seal's look at {@code source}'s ring (see {@link #noteBlocksLeftBehind}), reading
     * the ring's drop counts total first; for the input's silence dropped since the last block kept from its ring,
     * or left behind, its tracks not yet flagged get the input-stopped line the take would have written (rulings
     * E277, E282, E283 and E303).
     */
    private void lineWhatTheSweepLeft(CaptureRing input, int source) {
        DroppedRuns runs = droppedRuns[source];
        CaptureRing.DropCounts now = input.dropCounts();
        long trailing = Math.max(0L, now.total() - runs.seen.total());
        long audio = Math.min(trailing, now.audio() - runs.seen.audio());
        long starved = Math.min(trailing - audio, now.starved() - runs.seen.starved());
        long silence = Math.min(trailing, now.inputless() - runs.seen.inputless());
        long line = Math.max(0L, inputRings == null ? lastAppliedEndFrame : sourceLastAppliedEndFrame[source]);
        int blockFrames = input.slotFrames();
        long released = input.releasedBlocks();
        long leftBehind = Math.max(0L, input.publishedBlocks() - released);
        List<CaptureRing.Slot> held = new ArrayList<>();
        for (long sequence = released; sequence < released + leftBehind; sequence++) {
            CaptureRing.Slot slot = input.peekAt(sequence);
            if (slot == null) {
                break;
            }
            held.add(slot);
        }
        long leftAudio = held.stream().filter(CaptureFlushService::carriesAudio).count();
        boolean silenceHeld = held.stream().anyMatch(slot -> slot.sourceChannels(0) == 0);
        if (leftAudio > 0L) {
            addInputGaps(source, line, leftAudio);
            if (fileLosses.reachesTheRegionByItsOwnExtent(source, line, leftAudio * blockFrames)) {
                fileLosses.countOverflowBlocks(leftAudio);
            }
            manifestDirty = true;
            warn(leftAudio + " capture block(s) were published after the final sweep at frame " + line
                    + " and are not part of the take; the manifest records the gap");
        }
        // Before the audio the stamp shows came after the silence (ruling E302).
        long silenceStart = line + Math.max(0L, trailing - silence
                - (now.audioAfterSilence() - runs.seen.audioAfterSilence())) * blockFrames;
        if (silence == 0L || !markTheDroppedSilence(source, silenceStart, silence)) {
            markTheFirstHeldSilence(source, held);
        }
        // The declared stop takes the starved drops after the last delivery only when, marked, it covers every
        // track of the input (rulings E49 and E301).
        boolean stopAfter = (silence > 0L || silenceHeld) && !hasAnUncoveredTrack(source);
        long open = runs.waitingStarved + starved;
        long afterTheLastDelivery = Math.min(now.starvedSinceDelivery(), open);
        resolveWaitingRuns(source, afterTheLastDelivery < open || !stopAfter);
        long starvedCounted = stopAfter ? starved - Math.min(afterTheLastDelivery, starved) : starved;
        long lateDrops = audio + starvedCounted;
        if (lateDrops > 0L) {
            long dropStart = line + leftBehind * blockFrames;
            boolean silenceFirst = now.audioAfterSilence() > runs.seen.audioAfterSilence();
            long judged = silenceFirst ? trailing : lateDrops;
            lineRun(source, dropStart, lateDrops,
                    fileLosses.reachesTheRegionByItsOwnExtent(source, dropStart, judged * blockFrames));
        }
    }

    /**
     * At a device-loss seal not sealed early in its final sweep, for {@code source}'s ring, from the counts the
     * final sweep's look read (ruling E286), as {@link #lineWhatTheSweepLeft} reads its own: a run waiting on the
     * input's open starved run gets its line, with its starved blocks when that starved run ended with a
     * delivery, published or dropped, and without them when it was still open at the loss, whose tail the loss
     * reports (ruling E281); the drops that look found after the last block applied, delivered before the
     * dropout, get one line at the end of that block for those that carried audio and the input-starved ones a
     * delivery followed, counted by their own extent (ruling E275); for the input's silence dropped since the
     * last block kept from its ring, its tracks not yet flagged get their input-stopped line (rulings E282 and
     * E303). What the ring received or dropped after that look is the loss's (ruling E264).
     */
    private void lineTheDropsBeforeTheDeviceLoss(CaptureRing input, int source) {
        DroppedRuns runs = droppedRuns[source];
        CaptureRing.DropCounts found = runs.atTheLoss;
        if (found == null) {
            resolveWaitingRuns(source, false);
            return;
        }
        long trailing = Math.max(0L, found.total() - runs.seen.total());
        long audio = Math.min(trailing, found.audio() - runs.seen.audio());
        long starved = Math.min(trailing - audio, found.starved() - runs.seen.starved());
        long silence = Math.min(trailing, found.inputless() - runs.seen.inputless());
        long open = runs.waitingStarved + starved;
        long afterTheLastDelivery = Math.min(found.starvedSinceDelivery(), open);
        resolveWaitingRuns(source, afterTheLastDelivery < open);
        long counted = audio + starved - Math.min(afterTheLastDelivery, starved);
        long line = Math.max(0L, inputRings == null ? lastAppliedEndFrame : sourceLastAppliedEndFrame[source]);
        int blockFrames = input.slotFrames();
        if (counted > 0L) {
            boolean silenceFirst = found.audioAfterSilence() > runs.seen.audioAfterSilence();
            long judged = silenceFirst ? trailing : counted;
            lineRun(source, line, counted,
                    fileLosses.reachesTheRegionByItsOwnExtent(source, line, judged * blockFrames));
        }
        if (silence > 0L) {
            markTheDroppedSilence(source, line + Math.max(0L, trailing - silence
                    - (found.audioAfterSilence() - runs.seen.audioAfterSilence())) * blockFrames, silence);
        }
    }

    /** Whether a block left behind carried audio a track records: any source with a channel (rulings E265, E277). */
    private static boolean carriesAudio(CaptureRing.Slot slot) {
        for (int source = 0; source < slot.sourceCount(); source++) {
            if (slot.sourceChannels(source) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The input-stopped line the take would have written for {@code source} had {@code blocks} blocks of its
     * silence from {@code start}, none of which reached it, reached it (rulings E277 and E282), judged by the
     * punch region, loop window and legacy range of the last block of that input the take received: the stop at
     * the input's delivered end with no region, inside the legacy range, or when that end lies inside the punch
     * region. For an input that stopped at or before punch-in whose silence's first block the transport played at
     * or after punch-out, in the lap the wraps before it reach (ruling E283), first at the region's first frame in
     * that lap for the tracks whose file of that lap holds frames, as an applied block of that silence would flag
     * them; under loop recording a lap after the last block of that input the take received has no file (rulings
     * E296, E297 and E300). Every other track at the region's first frame in the lap the silence first reaches it
     * in (rulings E77, E88, E179 and E186), and none when the silence never reaches the region. The resume at the
     * stop. Its warning has ruling E284's words, or E302's when a block with which the input delivers again marks
     * it. Returns whether a track was flagged. Flush thread, at the seal or in {@link #applyBlock}.
     */
    private boolean markTheDroppedSilence(int source, long start, long blocks) {
        long delivered = sourceDeliveredEndFrame[source];
        long stopFrame = Math.max(0L, delivered == NOTHING_DELIVERED ? config.recordingStartFrame() : delivered);
        int blockFrames = inputRings == null ? ring.slotFrames() : inputRings[source].slotFrames();
        FileLossCounts.Window window = fileLosses.windowOf(source);
        boolean flagged = false;
        if (window != null && window.hasPunchRegion()) {
            if (delivered <= window.regionStart() || delivered >= window.regionEnd()) {
                long lapsAhead = 0L;
                long playedStart = start;
                if (FrameSpans.looped(window.loopStart(), window.loopEnd()) && start >= window.loopEnd()) {
                    long length = window.loopEnd() - window.loopStart();
                    lapsAhead = (start - window.loopEnd()) / length + 1;
                    playedStart = start - lapsAhead * length;
                }
                // Under loop recording a later lap's lane opens only with a block of it; none reached the take.
                boolean theLapsFileIsCurrent = lapsAhead == 0L || !config.loopRecord();
                if (delivered <= window.regionStart() && playedStart >= window.regionEnd() && theLapsFileIsCurrent) {
                    flagged = flagTheInputsTracks(source, regionStartInLap(window.regionStart(), window.loopStart(),
                            window.loopEnd(), sourceWraps[source] + lapsAhead), true);
                }
                long wraps = FrameSpans.wrapsBeforeTheRegion(start, blocks * blockFrames, window.loopStart(),
                        window.loopEnd(), window.regionStart(), window.regionEnd());
                if (wraps < 0L) {
                    return flagged;
                }
                stopFrame = regionStartInLap(window.regionStart(), window.loopStart(), window.loopEnd(),
                        sourceWraps[source] + wraps);
            }
        } else if (config.legacyPunchRange() != null && !legacyRangeReached(start, blocks, blockFrames, window)) {
            return false;
        }
        return flagTheInputsTracks(source, stopFrame, false) || flagged;
    }

    /**
     * Gives each of {@code source}'s physical tracks not yet flagged, only those whose file holds frames when
     * {@code onlyTracksWithFrames}, a stop at {@code stopFrame} for silence of the input the take did not keep, the
     * resume equal; whether a track was flagged. Flush thread: at the seal, or in {@link #applyBlock} by the block
     * with which the input delivers again (ruling E302).
     */
    private boolean flagTheInputsTracks(int source, long stopFrame, boolean onlyTracksWithFrames) {
        boolean flagged = false;
        sealWritesTheStops = true;
        try {
            for (TrackCapture capture : captures) {
                if (capture.inputSource() != source || capture.isInstrument() || capture.session() == null
                        || onlyTracksWithFrames && capture.session().getTotalSamplesRecorded() <= 0) {
                    continue;
                }
                if (flagRoute(capture, 0)) {
                    recordInputStopped(capture, stopFrame, stopFrame);
                    flagged = true;
                }
            }
        } finally {
            sealWritesTheStops = false;
        }
        return flagged;
    }

    /**
     * Whether a block of {@code blocks} from {@code start} would have begun inside the legacy beat range, which
     * gates by a block's beat ({@link #applyBlock}), each start followed across the loop wrap (ruling E282).
     */
    private boolean legacyRangeReached(long start, long blocks, int blockFrames, FileLossCounts.Window window) {
        double beatsPerFrame = config.tempoBpm() / 60.0 / config.format().sampleRate();
        for (long block = 0L; block < blocks; block++) {
            long frame = start + block * blockFrames;
            if (window != null && FrameSpans.looped(window.loopStart(), window.loopEnd())
                    && frame >= window.loopEnd()) {
                frame = window.loopStart() + (frame - window.loopEnd()) % (window.loopEnd() - window.loopStart());
            }
            if (config.legacyPunchRange().contains(frame * beatsPerFrame)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Judges the blocks left behind on {@code source}'s ring, in ring order, as the take would have judged them
     * had they been applied, until one of its silence flags the input's tracks: each held block's lap counts the
     * wraps among the blocks before it the take did not receive (ruling E283), so the line and the resume read
     * from that block's header are the ones it would have given (rulings E77, E88 and E277); the warning has
     * ruling E284's words and names the lap of the last delivered frame the take received (rulings E172 and
     * E293).
     */
    private void markTheFirstHeldSilence(int source, List<CaptureRing.Slot> held) {
        double beat = inputRings == null ? previousBeat : sourcePreviousBeat[source];
        for (CaptureRing.Slot slot : held) {
            if (beat >= 0.0 && slot.beatPosition() < beat && slot.loopEnabled()) {
                sourceWraps[source] = sourceWraps[source] + 1;
            }
            beat = slot.beatPosition();
            if (slot.sourceChannels(0) == 0 && markTheInputStoppedAt(source, slot)) {
                return;
            }
        }
    }

    /** Judges a held block of {@code source}'s silence as {@link #applyBlock} would; whether a track was flagged. */
    private boolean markTheInputStoppedAt(int source, CaptureRing.Slot silence) {
        long flaggedBefore = flaggedTracks(source);
        inputSource = source;
        sealWritesTheStops = true;
        try {
            long blockStart = silence.startFrame();
            long blockEnd = blockStart + silence.numFrames();
            if (silence.punchEnabled() && !FrameSpans.overlaps(blockStart, blockEnd, silence.punchStartFrames(),
                    silence.punchEndFrames())) {
                flagStopOutsideThePunchRegion(silence, blockStart);
            } else if (!legacyRangeExcludes(silence)) {
                for (TrackCapture capture : captures) {
                    if (capture.inputSource() == source && !capture.isInstrument() && capture.session() != null) {
                        flagUnavailable(capture, 0, silence);
                    }
                }
            }
        } finally {
            sealWritesTheStops = false;
        }
        return flaggedTracks(source) > flaggedBefore;
    }

    /** The tracks recording {@code source}'s input whose route is flagged. */
    private long flaggedTracks(int source) {
        return captures.stream().filter(capture -> capture.inputSource() == source && !capture.isInstrument()
                && capture.isRoutingFlagged()).count();
    }

    /**
     * Whether a physical track of {@code source}'s input is not flagged, the tracks {@link #endStarvedRun} gives
     * an entry: a declared stop that leaves one covers none of its starved drops (rulings E49 and E301).
     */
    private boolean hasAnUncoveredTrack(int source) {
        return captures.stream().anyMatch(capture -> capture.inputSource() == source && !capture.isInstrument()
                && !capture.isRoutingFlagged());
    }

    /** The thread takes nothing more up: every block it released is done with, for {@link #awaitFlushed}. */
    private void markEveryRingLooked() {
        if (inputRings == null) {
            sourceLookedBlocks.set(0, ring.releasedBlocks());
            return;
        }
        for (int i = 0; i < inputRings.length; i++) {
            sourceLookedBlocks.set(i, inputRings[i].releasedBlocks());
        }
    }

    /** Notes ring {@code source}'s drops behind the final sweep, up to {@code overflow}, for {@link #awaitFlushed}. */
    private void accountLateDrops(int source, long overflow, long lateDrops) {
        if (lateDrops == 0L) {
            return;
        }
        if (inputRings == null) {
            lastOverflowSeen = overflow;
        } else {
            sourceLastOverflow[source] = overflow;
        }
        overflowNoted += lateDrops;
        if (sourceOverflowNoted != null) {
            sourceOverflowNoted.set(source, overflow);
        }
    }

    /**
     * Closes every lane's writer — a pre-opened standby lane's too — with
     * no seal and no rename
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
     * end of the pass; returns whether any block was applied. Each ring's
     * pass ends with its look ({@link #lookAtTheRing()}).
     */
    private boolean drainOnce() {
        if (!sealed && !finalSweep) {
            // The seal drains what the final sweep would have: it is the last look.
            drainClockNotes();
        }
        if (inputRings == null) return drainInputRing();
        boolean progressed = false;
        for (int i = 0; i < inputRings.length; i++) {
            inputSource = i;
            ring = inputRings[i];
            previousBeat = sourcePreviousBeat[i];
            wasInsidePunchRegion = sourceWasInsidePunchRegion[i];
            lastOverflowSeen = sourceLastOverflow[i];
            lastAppliedEndFrame = sourceLastAppliedEndFrame[i];
            truncationEpisodeOpen = sourceTruncationEpisode[i];
            progressed |= drainInputRing();
            sourcePreviousBeat[i] = previousBeat;
            sourceWasInsidePunchRegion[i] = wasInsidePunchRegion;
            sourceLastOverflow[i] = lastOverflowSeen;
            sourceLastAppliedEndFrame[i] = lastAppliedEndFrame;
            sourceTruncationEpisode[i] = truncationEpisodeOpen;
        }
        inputSource = 0; ring = inputRings[0];
        return progressed;
    }
    private boolean drainInputRing() {
        boolean progressed = false;
        CaptureRing.Slot slot;
        long passEnd = ring.publishedBlocks();
        while (ring.releasedBlocks() < passEnd && (slot = ring.peek()) != null) {
            progressed = true;
            applyBlock(slot);
            // Before the release and the count, so awaitFlushed covers it.
            forceDueSegments();
            prepareStandbyLanes();
            // Header values are read before the release: the producer may
            // reuse the slot as soon as the read index has moved.
            long sequence = slot.sequence();
            long startFrame = slot.startFrame();
            int numFrames = slot.numFrames();
            // Counted and observed before the release, and so before the
            // look that ends awaitFlushed's wait (story 336, studio-engineer
            // ruling R992-22). The slot is released even when the observer
            // throws.
            appliedBlocks = appliedBlocks + 1;
            BlockObserver observer = blockObserver;
            try {
                if (observer != null) {
                    observer.onBlockApplied(sequence, startFrame, numFrames);
                }
            } finally {
                ring.release();
            }
        }
        lookAtTheRing();
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
     * open, or the wrap that made a pre-opened lane current)
     * ({@link RecordingSession#forceIfCadenceElapsed()}) — whether or
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

    /**
     * Loop-record's pre-open (book §4.6): gives every track whose current
     * lane has no standby one ({@link TrackCapture#prepareStandby()}) — the
     * lane a wrap has just swapped in, or one whose rotation took over the
     * standby's file — and writes the manifest that lists the new segment.
     * It runs after each block, once the block is on its way to disk and
     * before it is released and counted, so the open is never between a
     * lane's seal and the first block of the next lane. It stands down
     * outside loop-record, once the take is sealed, and in the final sweep,
     * whose seal would only discard the standby again. A start that throws
     * is answered like a failed append ({@link #failAndSeal(Throwable)}).
     * Flush thread.
     */
    private void prepareStandbyLanes() {
        if (!config.loopRecord() || sealed || finalSweep) {
            return;
        }
        try {
            for (TrackCapture capture : captures) {
                capture.prepareStandby();
            }
            if (manifestDirty) {
                flushManifest();
            }
        } catch (Throwable failure) {
            failAndSeal(failure);
        }
    }

    /**
     * The rate-limited peak publication: if {@link #PEAK_PUBLISH_INTERVAL}
     * has elapsed at {@code now} since the last one (or there was none),
     * every lane whose mirror gained frames since its last snapshot hands
     * the sink a new one. A call that finds no lane changed publishes
     * nothing and leaves the interval running. The sink is foreign code,
     * and the peaks are a picture of the take, not the take: whatever it
     * throws — a {@link RuntimeException} or an {@link Error} — is
     * contained by the capture ({@link TrackCapture#publishPeaksIfChanged()}),
     * logged once per track, and never ends a take; the attempt counts as
     * a publication, so a sink that throws is still called at most once per
     * interval. With no sink this does nothing. Flush thread, after a block
     * was applied; {@code now} is the block's one clock reading.
     */
    private void publishPeaksIfDue(long now) {
        if (peaksPublished && now - lastPeakPublishNanos < PEAK_PUBLISH_INTERVAL_NANOS) {
            return;
        }
        boolean published = false;
        for (TrackCapture capture : captures) {
            published |= capture.publishPeaksIfChanged();
        }
        if (published) {
            peaksPublished = true;
            lastPeakPublishNanos = now;
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
            // First: the window, lap, lane and delivered end are still those of the silence before this block.
            markTheSilenceBeforeTheInputDeliversAgain(slot);
            // Loop-record: a beat position that went backwards between
            // consecutive blocks while looping means a lap just completed.
            // Detected before routing so the wrapped audio goes into the new
            // lane — sample-accurate loop boundaries.
            double currentBeatPosition = slot.beatPosition();
            boolean wrapped = previousBeat >= 0.0
                    && currentBeatPosition < previousBeat
                    && slot.loopEnabled();
            if (config.loopRecord() && wrapped) {
                // The lane opened at the wrap starts where its first frame is
                // written — this block's, or a later one's under punch
                // gating (TrackCapture.noteWrittenSlice; PR #984 review).
                finalizeLoopLap();
            }
            if (wrapped) {
                sourceWraps[inputSource] = sourceWraps[inputSource] + 1;
            }
            previousBeat = currentBeatPosition;

            long now = nanoClock.getAsLong();
            if (headroom.check(now) == DiskHeadroomWatch.State.EXHAUSTED) {
                // Counted before the seal: the seal may rethrow a lane's Error.
                discardedBlocks = discardedBlocks + 1;
                lineThePassBeforeTheEarlySeal();
                sealEarly(SealedBy.DISK_EXHAUSTION, null);
                // Reached when the seal returned; a seal that threw (a lane's
                // Error) is announced by failAndSeal, after its re-entry.
                announceEarlySeal();
                return;
            }

            long blockStart = slot.startFrame();
            int numFrames = slot.numFrames();
            long blockEnd = blockStart + numFrames;
            fileLosses.blockReceived(inputSource,
                    slot.punchEnabled() ? slot.punchStartFrames() : FrameSpans.UNBOUNDED_START,
                    slot.punchEnabled() ? slot.punchEndFrames() : FrameSpans.UNBOUNDED_END,
                    slot.loopStartFrame(), slot.loopEndFrame());
            // Before the punch gate: where an input stopped is a fact of the
            // stream, whether or not this block is recorded.
            noteInputDelivery(slot, blockStart, blockEnd);

            if (slot.punchEnabled()) {
                captureWithTransportPunch(slot, numFrames, blockStart, blockEnd,
                        slot.punchStartFrames(), slot.punchEndFrames());
            } else if (config.legacyPunchRange() != null
                    && !config.legacyPunchRange().contains(currentBeatPosition)) {
                // Legacy beat-based gating: outside the range, nothing is recorded.
            } else {
                recordToSessions(slot, 0, numFrames, false, 0, 0);
            }
            // After the write: a block whose write failed never reached the
            // file, so the tail the failure's seal writes stops before it.
            noteStarvedBlockWritten(slot, blockStart, blockEnd);
            long runLine = Math.max(0L, lastAppliedEndFrame);
            lastAppliedEndFrame = blockEnd;
            // Kept: written, or passed over outside the region, so the run before it is the take's (ruling E274).
            lineTheRunBefore(slot, runLine, blockStart, wrapped);
            publishPeaksIfDue(now);
            noteTruncation(slot, blockEnd);
            if (manifestDirty) {
                flushManifest();
            }
        } catch (Throwable failure) {
            failAndSeal(failure);
        }
    }

    /**
     * When {@code slot}, a kept block with channels of its input, follows drops that held the input's silence, the
     * input delivers again after a declared stop in this take, which only a reopened stream gives: its tracks get
     * the input-stopped line the seal gives such silence (ruling E282), judged by the state before this block, in
     * the words of a stop before a delivery (ruling E302); {@link #lineTheRunBefore} then lines the whole run.
     * The silence is placed before the audio the stamp shows came after it. Flush thread, in {@link #applyBlock}.
     */
    private void markTheSilenceBeforeTheInputDeliversAgain(CaptureRing.Slot slot) {
        DroppedRuns runs = droppedRuns[inputSource];
        CaptureRing.DropCounts stamp = ring.stampOf(slot);
        long silence = stamp.inputless() - runs.seen.inputless();
        if (slot.sourceChannels(0) == 0 || silence <= 0L) {
            return;
        }
        long blocks = stamp.total() - runs.seen.total();
        long audioAfterIt = stamp.audioAfterSilence() - runs.seen.audioAfterSilence();
        long start = Math.max(0L, lastAppliedEndFrame)
                + Math.max(0L, blocks - silence - audioAfterIt) * ring.slotFrames();
        stopBeforeADelivery = true;
        try {
            markTheDroppedSilence(inputSource, start, silence);
        } finally {
            stopBeforeADelivery = false;
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
        fileLosses.truncated(inputSource, !truncationEpisodeOpen, keptEndFrame, truncated,
                regionStart(slot), regionEnd(slot), slot.loopStartFrame(), slot.loopEndFrame());
        if (truncationEpisodeOpen) {
            return;
        }
        truncationEpisodeOpen = true;
        truncationEpisodes = truncationEpisodes + 1;
        long gapStart = Math.max(0L, keptEndFrame);
        addInputGaps(inputSource, gapStart, 0);
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
            if (capture.inputSource() != inputSource) continue;
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
            boolean unavailable = !instrument && (long) firstCh + chCount > available;
            if (unavailable) flagUnavailable(capture, available, slot);
            for (int ch = 0; ch < chCount && ch < routed.length; ch++) {
                int srcCh = firstCh + ch;
                if (!unavailable && srcCh < available) {
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

            long framesBefore = session.getTotalSamplesRecorded();
            try {
                session.recordAudioData(routed, sliceFrames);
            } finally {
                // The mirror gets the frames the session counted, however the
                // append ended: none while it is paused, the written part of
                // a block whose append failed.
                int counted = (int) Math.min(sliceFrames, session.getTotalSamplesRecorded() - framesBefore);
                capture.notePeaks(Math.clamp(chCount, 0, routed.length), counted);
                if (counted > 0) {
                    // The lane's first written frame anchors its clip on the
                    // timeline, wherever punch gating let writing begin.
                    capture.noteWrittenSlice(slot.beatPosition(), offset);
                }
            }
            if (!instrument && !unavailable && !slot.inputStarved()
                    && session.getTotalSamplesRecorded() > framesBefore) {
                capture.noteRoutedDeliveredBlock();
            }
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

    /**
     * Loop wrap: every track seals its lane, stacks the lap as a take, and
     * makes its pre-opened standby the current lane — or opens the next
     * lane here when it holds none at the right index
     * ({@link TrackCapture#finalizeLane}). A standby that had to be
     * discarded at the wrap and whose empty file could not be deleted does
     * not end the take: it is handled as the same failure is at the stop
     * ({@link #discardStandbyLanes()}) — the entry is gone from the
     * manifest, the capture keeps the session for a later retry of the
     * delete, the warning sink is told — and the track's next lane is
     * recording, opened past that file. The lane opened here has no start
     * yet: the first slice written into it sets one
     * ({@link #recordToSessions}, {@link TrackCapture#noteWrittenSlice}).
     */
    private void finalizeLoopLap() {
        for (TrackCapture capture : captures) {
            if (capture.inputSource() != inputSource) continue;
            try {
                capture.finalizeLane(true, true);
            } finally {
                RuntimeException undeletable = capture.takeStandbyDiscardFailure();
                if (undeletable != null) {
                    manifestDirty = true;
                    LOG.log(Level.WARNING, "could not delete the pre-opened, empty loop lane of track "
                            + capture.trackId() + " at a loop wrap; its file is left in " + capture.trackDirectory()
                            + ", no longer listed in the manifest, and the take goes on", undeletable);
                    warn("The empty, pre-opened loop lane file of track " + capture.trackName() + " could not be"
                            + " deleted at a loop wrap — " + describe(undeletable) + "; a zero-frame .part file is"
                            + " left under " + capture.trackDirectory() + " that the take manifest does not list."
                            + " Recording continues.");
                }
            }
        }
    }

    /** A run held until the input's open starved run ends: its line, and its count and reach either way. */
    private record WaitingRun(long line, long ifStarvedCount, boolean reachesIfStarvedCount, long ifNot,
                              boolean reachesIfNot) {
    }

    /** One input ring's runs of dropped blocks on the flush thread (story 338, rulings E273 to E287). */
    private static final class DroppedRuns {
        /** The drop counts the last block kept from the ring was stamped with: where the next run begins. */
        CaptureRing.DropCounts seen = new CaptureRing.DropCounts(0L, 0L, 0L, 0L, 0L, 0L);
        /** Runs whose line waits for the input's open starved run to end (ruling E281). */
        final List<WaitingRun> waiting = new ArrayList<>();
        /** The input-starved drops of {@link #waiting}. */
        long waitingStarved;
        /** The ring's counts the final sweep's look read at a device-loss seal (ruling E286). */
        CaptureRing.DropCounts atTheLoss;
    }

    /**
     * The run of blocks the ring dropped right before the block just kept (ruling E273), from the counts that
     * block was stamped with: lined at {@code line}, the end of the block the ring delivered before it, for its
     * blocks that carried audio a track records and, where they count, its input-starved blocks (ruling E281);
     * held whole while its starved blocks lead into more of the input's starved blocks, until that starved run
     * ends; its starved drops wait while the stop flags not every track of the input (ruling E301); the silence
     * after a declared stop gets nothing (ruling E277), unless this block, with channels of the input, delivers
     * again after it: then every block of the run is lined and counted, and the waiting runs end counted (ruling
     * E302). Flush thread, in {@link #applyBlock}.
     */
    private void lineTheRunBefore(CaptureRing.Slot slot, long line, long next, boolean wrapped) {
        DroppedRuns runs = droppedRuns[inputSource];
        CaptureRing.DropCounts stamp = ring.stampOf(slot);
        long blocks = stamp.total() - runs.seen.total();
        long audio = stamp.audio() - runs.seen.audio();
        long starved = stamp.starved() - runs.seen.starved();
        boolean silenceFirst = stamp.audioAfterSilence() > runs.seen.audioAfterSilence();
        boolean deliversAfterItsSilence = slot.sourceChannels(0) > 0 && stamp.inputless() > runs.seen.inputless();
        runs.seen = stamp;
        boolean delivered = slot.sourceChannels(0) > 0 && !slot.inputStarved();
        // This block's own flags are set: applyBlock judged its stop before it lines the run.
        boolean stopCovers = !delivered && !slot.inputStarved() && !hasAnUncoveredTrack(inputSource);
        long open = runs.waitingStarved + starved;
        // The starved drops since a block with delivered input was last claimed, kept or dropped.
        long afterTheLastDelivery = Math.min(stamp.starvedSinceDelivery(), open);
        if (delivered || deliversAfterItsSilence || afterTheLastDelivery < open) {
            resolveWaitingRuns(inputSource, true);
        } else if (stopCovers) {
            resolveWaitingRuns(inputSource, false);
        }
        long waits = delivered || deliversAfterItsSilence ? 0L : Math.min(afterTheLastDelivery, starved);
        long counted = deliversAfterItsSilence ? blocks : audio + starved - waits;
        if (!delivered && !stopCovers && waits > 0L) {
            runs.waiting.add(new WaitingRun(line, audio + starved,
                    runReaches(line, audio + starved, blocks, silenceFirst, next, wrapped), counted,
                    runReaches(line, counted, blocks, silenceFirst, next, wrapped)));
            runs.waitingStarved += waits;
        } else if (counted > 0L) {
            lineRun(inputSource, line, counted, runReaches(line, counted, blocks, silenceFirst, next, wrapped));
        }
    }

    /**
     * Whether a run of {@code blocks} dropped blocks, {@code counted} of which count, reaches into the punch
     * region: a whole run, or one whose silence-only blocks came before its audio, by the frames to the next
     * block (rulings E273, E285 and E287); any other by the own extent of the blocks it counts, which lead it
     * (rulings E104, E277 and E281). Flush thread, for the ring being drained.
     */
    private boolean runReaches(long line, long counted, long blocks, boolean silenceFirst, long next, boolean wrapped) {
        int blockFrames = ring.slotFrames();
        if (counted == blocks || silenceFirst) {
            return fileLosses.runReachesTheRegion(inputSource, line, blocks * blockFrames, next, wrapped);
        }
        return fileLosses.reachesTheRegionByItsOwnExtent(inputSource, line, counted * blockFrames);
    }

    /**
     * Ends the waiting runs of {@code source}'s open starved run: each gets its line, counting its input-starved
     * blocks when {@code starvedCount} — the run ended with its input delivering again, or the take was sealed at
     * the person's Stop or early with no declared stop covering every track of the input after it — and without
     * them otherwise (rulings E281 and E301).
     */
    private void resolveWaitingRuns(int source, boolean starvedCount) {
        DroppedRuns runs = droppedRuns[source];
        for (WaitingRun run : runs.waiting) {
            long blocks = starvedCount ? run.ifStarvedCount() : run.ifNot();
            if (blocks > 0L) {
                lineRun(source, run.line(), blocks, starvedCount ? run.reachesIfStarvedCount() : run.reachesIfNot());
            }
        }
        runs.waiting.clear();
        runs.waitingStarved = 0L;
    }

    /** Adds a run's line to the manifest builder, for the look's one write and the pass's one warning (E273). */
    private void lineRun(int source, long line, long blocks, boolean reachesTheRegion) {
        addInputGaps(source, line, blocks);
        if (reachesTheRegion) {
            fileLosses.countOverflowBlocks(blocks);
        }
        overflowBlocksLined += blocks;
        manifest.overflowBlocks(overflowBlocksLined);
        runLinesUnwritten = true;
        if (!passLinedARun) {
            passLinedARun = true;
            passRunFrame = line;
            passRunBlocks = blocks;
        }
    }

    /**
     * The pass's one ring-overflow warning, for the first run it lined, whose total is the take's
     * {@code overflow-blocks} so far: the pass's other runs are in that total and in the manifest (ruling E273).
     */
    private void warnOfThePassRuns() {
        if (!passLinedARun) {
            return;
        }
        passLinedARun = false;
        warn(overflowWarning(passRunBlocks, passRunFrame, overflowBlocksLined));
    }

    /**
     * Before the flush thread seals the take early: the runs waiting on an open starved run get their line, their
     * starved blocks counted (ruling E281), and the pass's ring-overflow warning is given before the early seal's,
     * so the record coordinator shows it while the take still records (ruling E274). The runs only while the
     * take is not sealed. Flush thread; besides the warning sink it touches what lining a run does: the manifest
     * builder, the take's file losses and its {@code overflow-blocks} count.
     */
    private void lineThePassBeforeTheEarlySeal() {
        if (!sealed) {
            for (int source = 0; source < droppedRuns.length; source++) {
                resolveWaitingRuns(source, true);
            }
        }
        warnOfThePassRuns();
    }

    /**
     * The look that ends every pass over the ring: writes the manifest, once, for the runs the pass lined, gives
     * the pass's one ring-overflow warning, notes the ring's drops for {@link #awaitFlushed} and moves that fence
     * past the blocks the pass released (ruling E273); at the final sweep of a device-loss seal it first keeps the
     * ring's drop counts for the seal, which lines what they hold unless the take is sealed early (rulings E275
     * and E286). Flush thread.
     */
    private void lookAtTheRing() {
        if (finalSweep && requestedReason == SealedBy.DEVICE_LOSS) {
            droppedRuns[inputSource].atTheLoss = ring.dropCounts();
        }
        if (runLinesUnwritten) {
            manifestDirty = true;
            flushManifest();
        }
        warnOfThePassRuns();
        noteOverflow();
        sourceLookedBlocks.set(inputSource, ring.releasedBlocks());
    }

    /**
     * Notes the ring's drops for {@link #awaitFlushed} as this look counts them, whatever becomes of their lines:
     * a run is lined only once a block the take keeps follows it, its line can wait for an input's starved run to
     * end, and some runs get none (rulings E273 to E281). Flush thread, at the look that ends each pass.
     */
    private void noteOverflow() {
        long overflow = ring.overflowCount();
        if (overflow <= lastOverflowSeen) {
            return;
        }
        overflowNoted += overflow - lastOverflowSeen;
        lastOverflowSeen = overflow;
        if (sourceOverflowNoted != null) sourceOverflowNoted.set(inputSource, overflow);
    }

    private static String overflowWarning(long dropped, long gapStart, long total) {
        return "Capture ring overflow: " + dropped + " block(s) dropped at frame " + gapStart
                + " (" + total + " dropped in total); the take manifest records the gap";
    }

    private long totalOverflow() {
        if (inputRings == null) return ring.overflowCount();
        long total = 0;
        for (CaptureRing input : inputRings) total += input.overflowCount();
        return total;
    }
    private void addInputGaps(int source, long start, long dropped) {
        if (inputRings == null || inputRings.length == 1) manifest.addGap(new GapEntry(GapEntry.ALL_TRACKS, start, dropped));
        else for (TrackCapture capture : captures) if (capture.inputSource() == source)
            manifest.addGap(new GapEntry(capture.trackId(), start, dropped));
    }
    /**
     * A route the input is too narrow for: found at open time (any width, 0
     * included), or a delivered block narrower than the route mid-take
     * (story 338, ruling E54). Flags the track once and warns with the
     * routing-mismatch sentence.
     */
    private void flagNarrowRoute(TrackCapture capture, int available) {
        if (!flagRoute(capture, available)) return;
        InputRouting routing = capture.routing();
        warn("Track '" + capture.trackName() + "', device '" + capture.inputDeviceName() + "': "
                + routing.displayName() + " exceeds " + available + " opened input channels; this track captures silence");
    }

    /**
     * A block of the take that could not fill the track's route. A zero-width
     * block mid-take means the input ended — its publisher closed, or the
     * engine declared it stalled — so the track is flagged once, its stop is
     * recorded as an {@code input-stopped} entry and warned in the words of
     * story 338's ruling E14 — "the audio input" for an input with no name to
     * show (ruling E127); a narrower block is a routing mismatch. In a take
     * recorded with loop recording the warning names the lap that holds the
     * input's last delivered frame, with that frame's position in the lap's
     * own file (ruling E172).
     *
     * <p>In a punch take the stop is a frame of the file: the input's
     * delivered end when it lies inside the punch region, otherwise the first
     * frame of the region in the lap of the input's own frames at the block
     * that flags it — the lane whose file the flag is written in — where the
     * file's silence begins: the punch-in frame in lap 1, the later of the
     * punch-in and the loop start in a later lap (rulings E77, E88, E179 and
     * E186). The stop's lap is the lap its frame lies in: for a delivered
     * end, the input's own lap at its last delivery, one more for each wrap
     * that block ran across before its end; an end on the loop end stays in
     * the lap it completed (ruling E176). The resume's lap is the transport's at the flagging block, which the render
     * thread stamped on it ({@link CaptureRing.Slot#transportLap()}); a slot
     * with no stamp is in the input's own lap. While the two laps are the
     * same the resume frame is never before the stop; otherwise it is the
     * output clock's frame, never before that lap's first frame of the region,
     * so a resume before the stop says the wrap lies between them (rulings
     * E89, E179 and E186). The stream's own input has no clock frame: its
     * resume is its stop. "Delivered no audio" means no delivered block
     * reached the track's file in any lap.</p>
     */
    private void flagUnavailable(TrackCapture capture, int available, CaptureRing.Slot slot) {
        if (available > 0) {
            flagNarrowRoute(capture, available);
            return;
        }
        if (!flagRoute(capture, available)) return;
        int source = capture.inputSource();
        long delivered = sourceDeliveredEndFrame[source];
        long stopFrame = Math.max(0L, delivered == NOTHING_DELIVERED ? config.recordingStartFrame() : delivered);
        long transportLap = slot.transportLap() != CaptureRing.NO_TRANSPORT_LAP
                ? slot.transportLap() : sourceWraps[source];
        boolean stopInTheTransportsLap = true;
        if (slot.punchEnabled()) {
            boolean deliveredInside = delivered > slot.punchStartFrames() && delivered < slot.punchEndFrames();
            long stopLap = deliveredInside ? sourceWrapsAtDelivery[source] : sourceWraps[source];
            stopFrame = deliveredInside ? delivered
                    : regionStartInLap(slot.punchStartFrames(), slot.loopStartFrame(), slot.loopEndFrame(), stopLap);
            stopInTheTransportsLap = stopLap == transportLap;
        }
        long outputClockFrame = slot.outputClockFrame();
        long resumeFrame = stopFrame;
        if (outputClockFrame != CaptureRing.NO_OUTPUT_CLOCK_FRAME) {
            resumeFrame = Math.max(0L, outputClockFrame);
            if (slot.punchEnabled()) {
                // In the stop's lap a clock frame before the stop is the block across the region's start.
                resumeFrame = Math.max(resumeFrame, stopInTheTransportsLap ? stopFrame
                        : regionStartInLap(slot.punchStartFrames(), slot.loopStartFrame(), slot.loopEndFrame(),
                                transportLap));
            }
        }
        recordInputStopped(capture, stopFrame, resumeFrame);
    }

    /**
     * Writes the track's input-stopped line and warns in the words of story 338's ruling E14, which E172 gives a
     * lap in a loop-record take; a stop the seal writes because none of the input's silence reached the take
     * says that the track holds no audio from there (ruling E284); a stop marked by the block with which the input
     * delivers again after its dropped silence says only where it stopped, since audio follows (ruling E302).
     */
    private void recordInputStopped(TrackCapture capture, long stopFrame, long resumeFrame) {
        manifest.addInputStopped(new TakeManifest.InputStopped(capture.trackId(), stopFrame, resumeFrame));
        String lead = "Track '" + capture.trackName() + "': " + InputNames.shown(capture.inputDeviceName())
                .map(name -> "input from '" + name + "' ").orElse("the audio input ");
        String stoppedIn = config.loopRecord()
                ? "lap " + (capture.laneAtLastDelivery() + 1) + " of the take" : "the take";
        String stoppedAt = "stopped delivering audio at "
                + takePosition(capture.recordedAtLastDelivery(), config.format().sampleRate()) + " into " + stoppedIn;
        if (stopBeforeADelivery) {
            warn(capture.hasRoutedDeliveredBlock()
                    ? lead + stoppedAt + "."
                    : lead + "delivered no audio before it stopped.");
            return;
        }
        String holds = sealWritesTheStops ? "holds no audio" : "records silence";
        warn(capture.hasRoutedDeliveredBlock()
                ? lead + stoppedAt + "; the track " + holds + " from there."
                : lead + "delivered no audio; the track " + holds + ".");
    }

    /**
     * The first frame of the punch region from {@code punchIn} played in the lap after
     * {@code wraps} wraps: the punch-in frame in lap 1, the later of the
     * punch-in and the loop start in a later lap (rulings E179 and E186).
     */
    private static long regionStartInLap(long punchIn, long loopStart, long loopEnd, long wraps) {
        long first = Math.max(0L, punchIn);
        if (wraps == 0 || !FrameSpans.looped(loopStart, loopEnd)) {
            return first;
        }
        return Math.max(first, loopStart);
    }

    /** Flags the track's route once in the manifest; {@code false} when it already was. */
    private boolean flagRoute(TrackCapture capture, int available) {
        if (!capture.flagRouting()) return false;
        InputRouting routing = capture.routing();
        manifest.addRoutingFlag(new TakeManifest.RoutingFlag(capture.trackId(), capture.inputDeviceName(),
                routing.firstChannel(), routing.channelCount(), available));
        manifestDirty = true;
        return true;
    }

    /**
     * {@code m:ss.s} of {@code frames} at {@code sampleRate}: a track's
     * position in the file of the lane it was recording — the take's file,
     * or in a loop-record take one lap's (story 338, rulings E51 and E172).
     */
    static String takePosition(long frames, double sampleRate) {
        long tenths = Math.round(Math.max(0L, frames) * 10.0 / sampleRate);
        long minutes = tenths / 600;
        long seconds = tenths % 600 / 10;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds + "." + tenths % 10;
    }

    /**
     * Per source, before the punch gate: a block the input delivered moves
     * that source's delivered end (moved into the loop range when it ran past
     * the loop end) and closes its run of input-starved blocks as
     * {@code input-silence} entries, counted into the take's dropouts (story
     * 338, rulings E34, E43 and E89). A run the input's end follows stays open
     * to the seal, where the tracks that recorded the end are flagged and so
     * get no entry: their stop covers it (ruling E49).
     *
     * <p>A zero-width block outside the punch region never reaches
     * {@link #recordToSessions}, so the stop it carries is judged here: an
     * input whose delivered end lies inside the region is flagged at once,
     * one that delivered up to or past punch-out leaves a complete file and
     * gets nothing, and one that ended at or before punch-in is flagged once
     * the take has passed the region and the file holds its silence (rulings
     * E77 and E88).</p>
     */
    private void noteInputDelivery(CaptureRing.Slot slot, long blockStart, long blockEnd) {
        int source = inputSource;
        if (slot.inputStarved()) {
            return;
        }
        if (slot.sourceChannels(0) > 0) {
            long loopStart = slot.loopStartFrame();
            long loopEnd = slot.loopEndFrame();
            sourceDeliveredEndFrame[source] = FrameSpans.intoLoop(blockEnd, loopStart, loopEnd);
            sourceWrapsAtDelivery[source] = sourceWraps[source]
                    + FrameSpans.wrapsBeforeTheEnd(blockEnd, loopStart, loopEnd);
            endStarvedRun(source, true);
        } else if (slot.punchEnabled()
                && !FrameSpans.overlaps(blockStart, blockEnd, slot.punchStartFrames(), slot.punchEndFrames())) {
            flagStopOutsideThePunchRegion(slot, blockStart);
        }
    }

    private void flagStopOutsideThePunchRegion(CaptureRing.Slot slot, long blockStart) {
        long delivered = sourceDeliveredEndFrame[inputSource];
        long punchIn = slot.punchStartFrames();
        long punchOut = slot.punchEndFrames();
        if (delivered >= punchOut) {
            return;
        }
        boolean deliveredInside = delivered > punchIn;
        boolean regionPassed = blockStart >= punchOut;
        for (TrackCapture capture : captures) {
            if (capture.inputSource() != inputSource || capture.isInstrument()) continue;
            RecordingSession session = capture.session();
            if (session == null) continue;
            if (deliveredInside || regionPassed && session.getTotalSamplesRecorded() > 0) {
                flagUnavailable(capture, 0, slot);
            }
        }
    }

    /**
     * After the block was written: a starved block adds the frames of it the
     * file received inside the punch region to its source's run (rulings E76
     * and E87).
     */
    private void noteStarvedBlockWritten(CaptureRing.Slot slot, long blockStart, long blockEnd) {
        if (!slot.inputStarved()) {
            return;
        }
        long from = regionStart(slot);
        long to = regionEnd(slot);
        if (!FrameSpans.overlaps(blockStart, blockEnd, from, to)) {
            return;
        }
        starvedRuns.addBlock(inputSource, FrameSpans.clipStart(blockStart, from), FrameSpans.clipEnd(blockEnd, to),
                slot.loopStartFrame(), slot.loopEndFrame());
    }

    /** The first frame of the slot's region the file can receive; see {@link FrameSpans#UNBOUNDED_START}. */
    private long regionStart(CaptureRing.Slot slot) {
        if (slot.punchEnabled()) return slot.punchStartFrames();
        return legacyRangeExcludes(slot) ? FrameSpans.UNBOUNDED_END : FrameSpans.UNBOUNDED_START;
    }

    /** The end of the slot's region; see {@link #regionStart}. */
    private long regionEnd(CaptureRing.Slot slot) {
        return slot.punchEnabled() ? slot.punchEndFrames() : FrameSpans.UNBOUNDED_END;
    }

    private boolean legacyRangeExcludes(CaptureRing.Slot slot) {
        return config.legacyPunchRange() != null && !config.legacyPunchRange().contains(slot.beatPosition());
    }

    /**
     * Writes {@code source}'s open run of input-starved blocks as
     * {@code input-silence} entries per track that records the input, one
     * per part of the run, cut at the loop end (ruling E75), and, when
     * {@code counted}, adds it to the take's starved counts. Only the tail at
     * a device-loss seal is uncounted: the loss reports those frames (rulings
     * E48, E79 and E90). A flagged track gets no entry: its routing flag, and
     * for an input that ended its {@code input-stopped} entry, already covers
     * the run (ruling E49).
     */
    private void endStarvedRun(int source, boolean counted) {
        if (!starvedRuns.isOpen(source)) return;
        StarvedRuns.Run run = starvedRuns.end(source);
        boolean written = false;
        for (TrackCapture capture : captures) {
            if (capture.inputSource() != source || capture.isInstrument() || capture.isRoutingFlagged()) continue;
            for (StarvedRuns.Line line : run.lines()) {
                manifest.addInputSilence(new TakeManifest.InputSilence(capture.trackId(),
                        Math.max(0L, line.startFrame()), line.frames()));
            }
            written = true;
        }
        if (!written) return;
        manifestDirty = true;
        if (counted) {
            starvedInputBlocks = starvedInputBlocks + run.blocks();
            starvedInputFrames = starvedInputFrames + run.frames();
        }
    }

    /** Moves the clock events handed in so far into the manifest. Flush thread. */
    private void drainClockNotes() {
        TakeManifest.ClockMark mark;
        while ((mark = clockNotes.poll()) != null) {
            manifest.addClockMark(mark);
            manifestDirty = true;
        }
    }

    private void failAndSeal(Throwable failure) {
        lineThePassBeforeTheEarlySeal();
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
     * loop-record), discards the pre-opened lanes no lap reached
     * ({@link #discardStandbyLanes()}), then writes the final manifest. A lane whose seal fails
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
            discardStandbyLanes();
            if (firstLaneFailure != null && !sealedEarly) {
                // The seal a stop requested: earlySeal() never completes for
                // it, so this is how its failure reaches the caller.
                boolean everySegmentSealed = captures.stream().noneMatch(TrackCapture::hasUnsealedSegment);
                stopSealFailure = new StopSealFailure(firstLaneFailure, everySegmentSealed);
            }
        }
        if (!finalManifestWritten) {
            // Before the trailing starved run: an input-stopped line it gives covers that run (ruling E49).
            noteBlocksLeftBehind();
            if (!sealMarksWritten) {
                sealMarksWritten = true;
                // The trailing starved run the final sweep reached, up to the
                // last block the file received. Only a device loss reports
                // its frames itself (story 338, rulings E48, E79 and E90).
                boolean tailCounted = sealReason != SealedBy.DEVICE_LOSS;
                for (int source = 0; source < sourceDeliveredEndFrame.length; source++) {
                    endStarvedRun(source, tailCounted);
                }
                drainClockNotes();
            }
            SealedBy first = sealReason;
            SealedBy manifestReason;
            if (first == SealedBy.STOP && !sealFailed) {
                manifestReason = SealedBy.STOP;
                manifest.sealed(manifestReason);
            } else {
                // A DEVICE_LOSS stop with every lane sealed lands here as
                // aborted/device-loss — the book §3.4 RESCUED row.
                manifestReason = sealFailed ? SealedBy.WRITE_FAILURE : first;
                manifest.aborted(manifestReason);
            }
            // Wall-clock, not the injected monotonic clock: the manifest
            // records an instant, and the service has no other clock for one.
            manifest.sealedAt(Instant.now());
            manifestDirty = true;
            writeFinalManifest(manifestReason);
        }
        if (firstLaneError != null) {
            throw firstLaneError;
        }
    }

    /**
     * Discards the pre-opened lane no lap reached, for every track that
     * holds one ({@link TrackCapture#discardStandby()}): its empty file is
     * deleted and its segment entry removed, so the final manifest and the
     * directory agree. A file that cannot be deleted is the one case where
     * they do not, and it is reported, not hidden: the entry is removed all
     * the same — the file holds no frame and is no segment of the take, so
     * the final manifest lists only the take's own segments and its seal
     * status stays what the lanes' seals made it — the warning sink is told
     * that a zero-frame {@code .part} is left in the track directory, and
     * the capture keeps the session so that a discard of the take
     * ({@link TrackCapture#discardAllFiles()}) tries the delete again. It
     * does not make the seal a failed one: every frame of the take is in a
     * sealed segment. Flush thread, inside the seal, once every lane has
     * had its seal attempt.
     */
    private void discardStandbyLanes() {
        for (TrackCapture capture : captures) {
            try {
                capture.discardStandby();
            } catch (RuntimeException e) {
                manifestDirty = true;
                LOG.log(Level.WARNING, "could not delete the pre-opened, empty loop lane of track "
                        + capture.trackId() + "; its file is left in " + capture.trackDirectory()
                        + ", no longer listed in the manifest", e);
                warn("The empty, pre-opened loop lane file of track " + capture.trackName() + " could not be"
                        + " deleted — " + describe(e) + "; a zero-frame .part file is left under "
                        + capture.trackDirectory() + " that the take manifest does not list. The take itself is"
                        + " complete.");
            }
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
     * A manifest write of the take while it streams, or a retry of a final
     * write that failed earlier ({@link #flushManifest()}): builds and
     * writes the manifest, trying up to {@code attempts} times with
     * {@link #MANIFEST_RETRY_PAUSE} in between ({@link #tryWriteManifest}),
     * and when every attempt failed logs it and — once per failure episode
     * — tells the sink that recording continues and the write is retried;
     * for a take sealed early, whose capture is over, that the take keeps
     * draining and the write is retried ({@link #earlySealRetryText}; PR
     * #982 review round 2). Flush thread.
     *
     * @return whether the manifest on disk now matches the builder; on
     *         {@code false} the manifest stays dirty and the failure is
     *         recorded
     */
    private boolean writeManifest(int attempts) {
        Optional<Exception> failure = tryWriteManifest(attempts);
        if (failure.isEmpty()) {
            return true;
        }
        LOG.log(Level.WARNING, "take manifest write failed under " + config.takeDirectory()
                + " after " + attempts + " attempt(s); " + (sealedEarly
                        ? "the take keeps draining and the write is retried" : "capture continues"), failure.get());
        if (!manifestWriteFailing) {
            manifestWriteFailing = true;
            warn(manifestNotWrittenText(failure.get()) + "; " + (sealedEarly
                    ? earlySealRetryText()
                    : "recording continues and the manifest is rewritten as soon as it can be"));
        }
        return false;
    }

    /** {@code The take manifest under <take directory> could not be written — <failure>}: the lead every manifest-write warning shares. */
    private String manifestNotWrittenText(Exception failure) {
        return "The take manifest under " + config.takeDirectory() + " could not be written — " + describe(failure);
    }

    /**
     * What a take sealed early does about a manifest write that failed: it
     * keeps draining until a stop, retrying the write on its idle passes
     * ({@link #flushManifest}), and the seal the stop then requests makes
     * the last attempt; once the stop has been requested, only that last
     * attempt is still to come. Flush thread.
     */
    private String earlySealRetryText() {
        return stopping
                ? "the take was sealed early in the final sweep of its stop; the manifest write is retried once"
                        + " more by the stop's seal"
                : "the take was sealed early and keeps draining until Stop; the manifest write is retried until it"
                        + " lands or Stop seals the take";
    }

    /**
     * The final manifest write of the seal ({@link #sealAll}): the manifest
     * that carries the seal status and {@code manifestReason}, written with
     * the full retry budget ({@link #tryWriteManifest}). When every attempt
     * failed, the manifest on disk is the last one written, which says
     * nothing of the seal; that is recorded for
     * {@link #finalManifestFailure()} — before the thread terminates, so a
     * caller that has observed the termination reads it — logged SEVERE
     * and told to the sink once per take, by seal kind (PR #982 review
     * round 2). An early seal's ({@link #sealEarly}, before the thread's
     * last seal) is retried: the thread keeps draining until a stop, its
     * idle passes retry the write ({@link #flushManifest}), and the seal the
     * stop then requests makes the last attempt — a retry that lands clears
     * the record in {@link #writeManifestOnce} — so the sink hears that the
     * take was sealed early and the write is retried
     * ({@link #earlySealRetryText}), and the failure episode
     * ({@code manifestWriteFailing}) is opened so that the retries do not
     * warn again. The thread's last seal's ({@link #lastSeal}: the seal a
     * stop requested, or the loop failure handler's) is retried by nothing,
     * so the sink hears, once, in words that promise no retry, that the
     * take has ended and its manifest on disk does not record the seal —
     * said even when an early seal's warning preceded it. Flush thread.
     *
     * @param manifestReason the {@code sealed-by} the manifest was to carry
     */
    private void writeFinalManifest(SealedBy manifestReason) {
        Optional<Exception> failure = tryWriteManifest(MANIFEST_WRITE_ATTEMPTS);
        if (failure.isEmpty()) {
            return;
        }
        finalManifestFailure = new FinalManifestFailure(failure.get(), manifestReason);
        manifestWriteFailing = true;
        String onDisk = "the segments are sealed, the manifest on disk is the last one written and does not carry"
                + " sealed-by=" + manifestReason.token();
        if (!lastSeal) {
            LOG.log(Level.SEVERE, "final manifest write of an early seal failed under " + config.takeDirectory()
                    + " after " + MANIFEST_WRITE_ATTEMPTS + " attempt(s); " + onDisk + "; the write is retried",
                    failure.get());
            if (!earlySealManifestWarned) {
                earlySealManifestWarned = true;
                warn(manifestNotWrittenText(failure.get()) + "; " + earlySealRetryText());
            }
            return;
        }
        LOG.log(Level.SEVERE, "final manifest write of the take's last seal failed under " + config.takeDirectory()
                + " after " + MANIFEST_WRITE_ATTEMPTS + " attempt(s); " + onDisk
                + "; the take has ended and nothing retries it", failure.get());
        if (!finalManifestWarned) {
            finalManifestWarned = true;
            warn(manifestNotWrittenText(failure.get()) + "; the take has ended and its manifest on disk does not"
                    + " record the seal: take recovery rebuilds it from the segments");
        }
    }

    /**
     * Builds and writes the manifest, trying up to {@code attempts} times
     * with {@link #MANIFEST_RETRY_PAUSE} in between; the bookkeeping the
     * streaming and the final write share. A write that lands closes a
     * failure episode that was open; one that fails on every attempt leaves
     * the manifest dirty, stamps the attempt for the retry interval and
     * records the failure ({@link #lastFailure()}). Says nothing to the log
     * or the sink: that is each caller's. Flush thread.
     *
     * @return the failure of the last attempt, or empty once the manifest
     *         on disk matches the builder
     */
    private Optional<Exception> tryWriteManifest(int attempts) {
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
                return Optional.empty();
            } catch (IOException | RuntimeException e) {
                failure = e;
            }
        }
        manifestDirty = true;
        lastManifestAttemptNanos = nanoClock.getAsLong();
        lastFailure = failure;
        return Optional.of(failure);
    }

    /**
     * One write attempt, no failure handling: the take's initialisation, and
     * each attempt of {@link #tryWriteManifest(int)}. A manifest that carries
     * the seal status, once on disk, is the final one: a failure of an
     * earlier final write ({@link #finalManifestFailure()}) is cleared.
     * Flush thread.
     */
    private void writeManifestOnce(TakeManifest built) throws IOException {
        throwInjectedManifestFault();
        manifestTouched = true;
        built.write(config.takeDirectory());
        lastManifest = built;
        manifestDirty = false;
        runLinesUnwritten = false;
        manifestWrites = manifestWrites + 1;
        if (built.sealStatus() != TakeManifest.SealStatus.STREAMING) {
            finalManifestWritten = true;
            finalManifestFailure = null;
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
     * — routed, written, counted, its manifest rewrite done, the cadence
     * tick after it run, the {@link BlockObserver}, when a test has set one,
     * returned, and the look that ends its pass done, which writes the
     * {@code gap=} line of a run of dropped blocks the block follows (ruling
     * E273) — and every ring overflow counted before this call has been
     * noticed at such a look, whether or not a block has followed it yet, or
     * the timeout elapses. A thread that has ended is done with every block
     * it released. The
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
     *                               still unapplied or drops still unnoticed
     */
    public void awaitFlushed(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        CaptureRing[] inputs = inputRings == null ? new CaptureRing[] { ring } : inputRings;
        long[] targets = new long[inputs.length];
        long[] overflowTargets = new long[inputs.length];
        long target = 0, targetOverflow = 0;
        for (int i = 0; i < inputs.length; i++) {
            targets[i] = inputs[i].publishedBlocks(); overflowTargets[i] = inputs[i].overflowCount();
            target += targets[i]; targetOverflow += overflowTargets[i];
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!allInputsFlushed(inputs, targets, overflowTargets)) {
            if (!running) {
                // The thread's last fence stores precede its store of running: read the fence once more.
                if (allInputsFlushed(inputs, targets, overflowTargets)) {
                    return;
                }
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
     * The thread ends its drain loop at the next pass boundary and waits, for
     * at most {@link #PRODUCER_QUIESCENCE_BOUND}, for a callback in flight to
     * leave the ring's producer gate — which the pipeline closed before this
     * request ({@link CaptureRing#closeProducer()}); its final sweep then
     * drains the ring, seals every active writer and writes the final
     * manifest (a final manifest that cannot be written is logged SEVERE,
     * leaves the last written one on disk — the segments are sealed
     * regardless — and is reported by {@link #finalManifestFailure()}), and
     * then it terminates — {@link #termination()} completes,
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
     * Hands a clock event of the take to the flush thread, which writes it
     * into the manifest on its next pass, or at the seal (story 338, rulings
     * E30, E40 and E62). Any thread; never blocks, no storage I/O. An event
     * handed in once the take is sealed, or after its thread terminated, is
     * ignored ({@link #ignoresClockEvents()}).
     *
     * @param event what the clock did
     * @param frame the transport frame at the take's rate the event is dated
     *              at; a negative frame is written as 0
     */
    public void noteClockEvent(TakeManifest.ClockEvent event, long frame) {
        Objects.requireNonNull(event, "event must not be null");
        if (ignoresClockEvents()) {
            return;
        }
        clockNotes.add(new TakeManifest.ClockMark(event, Math.max(0L, frame)));
        if (started) {
            LockSupport.unpark(thread);
        }
    }

    /**
     * Blocks of the take's input-starved runs written as {@code input-silence}
     * entries: rendered with no captured input while the input was live, and
     * with frames inside the punch region the file received (one block
     * however many of its frames, whatever the lap). A run that ended in the
     * input's stop is not in it, nor the tail of a device-loss seal; the tail
     * of any other seal is, from the seal's manifest write on (story 338,
     * rulings E48, E49, E76, E79, E87 and E90). Any thread.
     */
    public long starvedInputBlocks() {
        return starvedInputBlocks;
    }

    /** The frames inside the punch region of the blocks {@link #starvedInputBlocks()} counts. Any thread. */
    public long starvedInputFrames() {
        return starvedInputFrames;
    }

    /** Truncation episodes the take opened: each a {@code gap=…|0} entry. Any thread. */
    public long truncationEpisodes() {
        return truncationEpisodes;
    }

    /**
     * Ring-overflow blocks with a {@code gap=} line, counted when the frames their run would have held reach
     * into the punch region, every one with no punch region: blocks that carried audio a track records, and
     * input-starved blocks where an applied starved block in their place would count; a run is counted once the
     * block after it is applied, once the input's starved run it leads into has ended, or at the seal when no
     * applied block follows it — at the person's Stop with the blocks a ring received after the final sweep, and
     * at a device loss not sealed early in its final sweep, the drops that sweep's look found and the runs whose
     * line waited on the input's starved run (story 338, rulings E86, E213 and E273 to E287). The {@code gap=}
     * lines record these blocks, and {@code overflow-blocks} the dropped ones, whether or not they reach into the
     * punch region. Any thread.
     */
    public long fileOverflowBlocks() {
        return fileLosses.overflowBlocks();
    }

    /** Truncated frames that lie inside the punch region; all of them with no punch region. Any thread. */
    public long fileTruncatedFrames() {
        return fileLosses.truncatedFrames();
    }

    /** Truncation episodes with at least one frame inside the punch region. Any thread. */
    public long fileTruncationEpisodes() {
        return fileLosses.truncationEpisodes();
    }

    /**
     * Asks the flush thread to discard the take, and returns at once: it
     * sets the abort and stop flags and wakes the thread, and it never waits
     * for it. Meant for a start that is abandoned — cancelled before capture
     * began, or rolled back after beginning capture failed. The flush thread
     * deletes the segment and manifest files it created for the take, and
     * each track directory it created, if that leaves it empty — each
     * capture's segments, sealed and streaming, and the manifest if this
     * take wrote or tried to write it (when this take's first manifest write
     * failed before its rename, in a take directory that held an earlier take's
     * manifest, the manifest deleted is that earlier take's: story 350) — and
     * then terminates: {@link #termination()} completes after the deletions.
     * The take directory, even one the initialisation had to create, is left
     * for its caller. The abort seals nothing, and {@link #earlySeal()} and
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
     * in drain order, on the flush thread, once the block has been applied
     * and counted and before its slot is released. {@link #awaitFlushed(Duration)}
     * ends at the look that ends the block's pass, after the release, so it returns only once the observer has returned
     * for every block it waits for (story 336, studio-engineer ruling R992-22).
     * The slot is released even when the observer throws, and the throwable
     * then ends the drain loop; an observer that blocks keeps that one slot
     * from the producer until it returns.
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
     * Test seam: replaces the bound of the wait for the callback in flight
     * before the final sweep (default {@link #PRODUCER_QUIESCENCE_BOUND}).
     * Read by the flush thread when that wait begins. Any thread.
     *
     * @param bound the longest wait; not negative
     * @throws IllegalArgumentException if {@code bound} is negative
     */
    void setProducerQuiescenceBound(Duration bound) {
        Objects.requireNonNull(bound, "bound must not be null");
        if (bound.isNegative()) {
            throw new IllegalArgumentException("bound must not be negative: " + bound);
        }
        producerQuiescenceBoundNanos = bound.toNanos();
    }

    /**
     * Returns whether the flush thread has left its drain loop for a stop
     * and is waiting for the callback in flight to leave the ring's producer
     * gate — the wait that precedes the final sweep. Any thread.
     */
    boolean isAwaitingProducerExit() {
        return awaitingProducerExit;
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
    private boolean allInputsFlushed(CaptureRing[] inputs, long[] targets, long[] overflows) {
        for (int i = 0; i < inputs.length; i++) {
            if (sourceLookedBlocks.get(i) < targets[i]) return false;
            if ((sourceOverflowNoted == null ? overflowNoted : sourceOverflowNoted.get(i)) < overflows[i]) return false;
        }
        return true;
    }

    public long overflowCount() {
        return totalOverflow();
    }

    /** Returns whether the take has been sealed (normally or early). */
    public boolean isSealed() {
        return sealed;
    }

    /**
     * Returns whether the take takes no clock event any more: it is sealed,
     * normally or early, or its thread has terminated, so
     * {@link #noteClockEvent} ignores what it is handed from now on (story 338,
     * ruling E280). Two volatile reads and no allocation. Any thread.
     */
    public boolean ignoresClockEvents() {
        return sealed || terminated;
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
     * started (each {@code <trackId>/segment-000.wav.part} exists), in
     * loop-record each track's lane-1 standby started too
     * ({@code segment-001.wav.part}), and the
     * initial manifest written — and the thread is draining
     * ({@link #isRunning()}). It completes exceptionally instead when the
     * initialisation fails or sees an abort ({@link #requestAbort()}), and
     * then only once the flush thread has rolled back the take's files —
     * deleting the segment and manifest files the take created, and each
     * track directory it created, if that leaves it empty (best-effort: what
     * an I/O error keeps from being deleted is left and the error logged);
     * the take directory, even one the initialisation had to create, is left
     * for its caller — and has terminated ({@link #isTerminated()},
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
     * if that write fails on every attempt, the manifest on disk is the last
     * one written, and {@link #finalManifestFailure()} reports it.
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

    /**
     * Returns the failure of the final manifest write
     * ({@link FinalManifestFailure}) if the write of the manifest that
     * carries the take's seal failed on every attempt
     * ({@link #MANIFEST_WRITE_ATTEMPTS}, {@link #MANIFEST_RETRY_PAUSE} apart)
     * and no later attempt landed it: every lane had its seal first, so the
     * segments are what the seal left them, but the manifest on disk is the
     * last one written — it reads {@code seal-status=streaming} and says
     * nothing of the seal, so the recovery scan ({@link TakeRecovery#scan};
     * story 332 runs it on project open) treats the take as a crash
     * candidate and rebuilds it. The value carries the throwable
     * of the last attempt and the reason the manifest was to carry. Empty
     * when the final write landed — at once, or after retries — for an
     * abort ({@link #requestAbort()}) of a take no early seal had sealed
     * (the abort exit seals nothing and clears nothing, so an early seal's
     * failed final write before it stays recorded), for the
     * {@code stopAndAbandon} test seam of such a take, and for a take that
     * never became ready.
     *
     * <p>Independent of {@link #stopSealFailure()} and of
     * {@link #earlySeal()}: the final write is made whatever the lanes'
     * seals did, so a stop whose every lane sealed can have this and nothing
     * else, and a lane that threw can have both.</p>
     *
     * <p>Written on the flush thread only: a volatile write made once a
     * final write has failed on every attempt, which the flush thread's
     * later volatile write of {@link #isTerminated()} follows in program
     * order; a final write that lands clears it the same way — only a take
     * the flush thread sealed early retries its final write after a
     * failure, on its idle passes and in the seal the stop then requests,
     * the seal a stop requests being the thread's last manifest write. So a
     * thread that has read {@code isTerminated()} as {@code true} — as a
     * dependent of {@link #termination()} has — reads the final value here;
     * read before that, empty may only mean that the seal has not run yet.
     * Never written on the audio thread. Any thread.</p>
     */
    public Optional<FinalManifestFailure> finalManifestFailure() {
        return Optional.ofNullable(finalManifestFailure);
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
