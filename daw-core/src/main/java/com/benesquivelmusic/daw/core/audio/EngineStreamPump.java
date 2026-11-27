package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCodes;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthLane;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.InputBlockLossListener;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine-owned render drive thread (story 316): calls
 * {@link AudioEngine#processBlock(float[][], float[][], int)} once per buffer
 * period and pushes the rendered block into {@link AudioBackend#sink(AudioBlock)},
 * paced by the backend's {@link AudioBackend#awaitSinkCapacity(long)}
 * backpressure signal so production follows the <em>device clock</em>, not the
 * host clock. Capture flows the other way: blocks emitted by
 * {@link AudioBackend#inputBlocks()} are queued and de-interleaved into
 * {@code processBlock}'s input planes.
 *
 * <p>One pump exists per open stream; {@link AudioEngine} constructs a fresh
 * pump on every open/resume and stops it on stop/pause. The pump thread is one
 * hop off the device's real-time thread, so it is deliberately <em>not</em>
 * annotated {@code @RealTimeSafe} — {@code processBlock} itself keeps its
 * annotation and its allocation-free discipline; the pump pre-allocates its
 * planes, its interleave buffer and its one reusable {@link AudioBlock} (the
 * {@code sink} contract permits reuse: implementations consume the block
 * synchronously) so the steady-state loop allocates nothing either.</p>
 *
 * <h2>Fault tolerance (failure-surfacing book §4.3; story 337)</h2>
 * <p>The render drive never logs and never surfaces a failure itself, and
 * takes no lock of its own (the bounded input queue's {@code poll}, one hop
 * off the device thread, is the one pre-existing exception): it RECORDS each
 * one into its own {@link AudioHealthLane} —
 * a category constant and the thrown reference, no formatting — and the
 * application's drain decides what the user sees (book §2.3; story 337,
 * ruling E14). The lane is claimed from the engine's
 * {@link AudioHealthRecord} when the pump is built, and the thread is a
 * {@link RenderThread} that carries it, so render code deep inside
 * {@code processBlock} records into the same lane (story 337, ruling E20).</p>
 * <ul>
 *   <li><strong>A stopped engine</strong> answers {@code processBlock} with
 *       {@code false} after writing silence, and the loop exits at once with
 *       no record (story 337, ruling E6). A {@link Throwable} from
 *       {@code processBlock} while the engine (or this pump) is no longer
 *       running is the same teardown — for example one caused by
 *       {@link #stop()}'s interrupt — and also exits quietly with no record.
 *       The condition is the STATE, not the exception type (story 337,
 *       ruling E7).</li>
 *   <li><strong>A render fault</strong> — any other {@link Throwable} from
 *       {@code processBlock} while the engine runs, including a
 *       {@link LinkageError}, an {@link AssertionError} or a smuggled checked
 *       throwable — is recorded as {@code audio.render} with the current
 *       fault streak, the faulted block is replaced with silence, and the
 *       loop keeps running: a single fault never ends the render loop (story
 *       337, ruling E9). One rendered block resets the streak.</li>
 *   <li><strong>The honest exit</strong> (story 337, rulings E8 and E10).
 *       {@link #haltThreshold(AudioFormat)} consecutive faulted blocks — one
 *       second of continuously faulted audio counted in frames, never fewer
 *       than 8 blocks — end the drive: the pump clears its own
 *       {@code running}, writes a terminal {@code audio.render.halted} record
 *       (the last throwable, the streak length and the stream generation) and
 *       returns. It never touches the engine's lifecycle lock, its stream
 *       state, the transport or the backend; the engine reports the dead drive
 *       through {@code AudioEngine.isRenderDriveAlive()}, tells the drive that
 *       halted itself apart through {@code AudioEngine.isRenderDriveHalted()}
 *       (story 337, ruling E69), and the next open reaps it. A
 *       {@link VirtualMachineError} from anywhere in the iteration
 *       halts at once, and an outer catch-all turns anything the specific
 *       catches miss into the same terminal exit, so the thread never dies by
 *       unwinding (story 337, ruling E9).</li>
 *   <li><strong>{@code sink} throwing</strong> (for example a frame-size
 *       mismatch after a driver reset) is recorded as {@code audio.sink} and
 *       the loop keeps running: the reopen path fixes the shape, and a dead
 *       output device is the watchdog's verdict through the render-drive
 *       heartbeat — which a {@code sink} that threw does not beat (story 337,
 *       rulings E5 and E8). That iteration is then held to a <em>floor</em>
 *       of one block period ({@link #parkUntil(long)}) — a floor rather than
 *       an extra wait, because what the following {@code awaitSinkCapacity}
 *       costs after a failed {@code sink} depends entirely on the backend
 *       (see below).</li>
 *   <li>{@code awaitSinkCapacity} throwing is recorded as
 *       {@code audio.pacing} and the same floor applies. This is the harsher
 *       fault: a seam that threw cannot be credited with any of the wait it
 *       was asked for, so the loop re-arms the deadline at the throw and the
 *       floor supplies the whole period rather than the loop spinning
 *       un-paced. A {@code sink} or pacing throw after this pump stopped is
 *       teardown and is not recorded (story 337, ruling E7).</li>
 *   <li><strong>Why a floor and not a park.</strong> The loop's pacing comes
 *       from {@link AudioBackend#awaitSinkCapacity(long)}, and its behaviour
 *       after a failed {@code sink} is BACKEND-SPECIFIC (story 316 review,
 *       round 4). The interface's default implementation parks the full
 *       timeout unconditionally, and {@code WasapiBackend},
 *       {@code CoreAudioBackend}, {@code JackBackend} and
 *       {@code MockAudioBackend} all inherit it while overriding a
 *       {@code sink} that can throw — those iterations are already paced, and
 *       a second park would run the loop at HALF real time. Others supply
 *       nothing: {@code JavaxSoundBackend}'s override is empty (its blocking
 *       {@code sink} IS its pacing, and that {@code sink} is what just
 *       failed), and {@code AsioBackend}'s ring poll returns the moment its
 *       output ring reads drained — and a thrown {@code sink} put nothing
 *       into that ring to wait on. Parking only the REMAINDER of the period
 *       covers both: it adds nothing where the backend already paced the
 *       iteration and supplies the shortfall where it did not.</li>
 *   <li>That floor exists for the PERSISTENT case (story 316 review, round
 *       3): an un-paced loop would burn a core and — worse — advance
 *       transport one block per iteration with no hardware clock behind it,
 *       running the timeline far faster than real time for the duration of
 *       the fault.</li>
 *   <li>The installed uncaught-exception handler stays as a last-resort
 *       backstop: only a throwable the outer catch-all itself cannot handle
 *       (an error raised while handling one) can still kill the thread. It
 *       logs that death, then records it through the honest exit's halt, so
 *       the drive reads halted rather than silently dead.</li>
 *   <li>Input blocks arriving faster than the loop consumes them are dropped
 *       (bounded queue) and counted; both sides of that queue are non-RT
 *       threads, so the queue's internal lock is fine here.</li>
 * </ul>
 */
final class EngineStreamPump {

    private static final Logger LOG = Logger.getLogger(EngineStreamPump.class.getName());

    /** Name of the render drive thread. */
    static final String THREAD_NAME = "engine-render-pump";

    /** Bound on queued-but-unconsumed captured input blocks. */
    private static final int INPUT_QUEUE_CAPACITY = 32;

    /** How long {@link #stop()} waits for the pump thread to exit. */
    private static final long STOP_JOIN_MILLIS = 1_000L;

    /** The fewest consecutive faulted blocks that can halt the drive (story 337, ruling E8). */
    private static final int MIN_HALT_THRESHOLD = 8;

    private final AudioBackend backend;
    private final AudioEngine engine;
    private final int channels;
    private final int bufferFrames;
    private final long blockPeriodNanos;

    // Pre-allocated render state — pump thread only.
    private final float[][] input;
    private final float[][][] inputViews;
    private int availableInputChannels;
    private final float[][] output;
    private final float[] interleaved;
    /**
     * The one reusable block handed to {@link AudioBackend#sink(AudioBlock)}.
     * Its backing {@code samples} array is {@link #interleaved}; the loop
     * refreshes the array in place before every sink call, which the sink
     * contract explicitly permits (implementations copy/encode synchronously).
     */
    private final AudioBlock reusableBlock;

    private final ArrayBlockingQueue<AudioBlock> inputQueue =
            new ArrayBlockingQueue<>(INPUT_QUEUE_CAPACITY);
    /**
     * Written by the backend's input marshalling thread, never by this pump's
     * render thread, so it stays a shared atomic rather than a lane counter.
     */
    private final AtomicLong droppedInputBlocks = new AtomicLong();
    /** Captured blocks the backend handed this pump, counted on arrival: the input's heartbeat. */
    private final AtomicLong deliveredInputBlocks = new AtomicLong();
    /** Set once by {@link #declareInputStalled()}; the declaration acts at most once. */
    private final AtomicBoolean inputStallDeclared = new AtomicBoolean();
    /**
     * Whether the stream has an input of its own whose missing blocks are
     * stamped as starved: only an opened input can starve.
     */
    private final boolean ownInputOpened;

    /**
     * The engine's detector when this pump was built, or {@code null}: every
     * rendered block's processing time goes to its {@code recordTick}. This
     * pump's render thread is its only tick writer, because the engine starts
     * a new pump only after the previous one's stop was confirmed.
     */
    private final XrunDetector xrunDetector;

    /** This pump's render-drive lane: its only writer is {@link #thread}. */
    private final AudioHealthLane lane;
    /** The stream this pump renders for — the subject of a halt record. */
    private final Object streamGeneration;
    /** Consecutive faulted blocks that end the drive (story 337, ruling E8). */
    private final int haltThreshold;
    /** Consecutive faulted {@code processBlock} calls — pump thread only. */
    private int renderFaultStreak;
    /** Retires {@link #lane} exactly once, whichever exit path gets there first. */
    private final AtomicBoolean laneRetired = new AtomicBoolean();

    private final RenderThread thread;
    private volatile boolean running;
    private volatile boolean inputStreamTerminated;
    private volatile Flow.Subscription inputSubscription;
    /**
     * True once the input planes were written from a captured block and so
     * need re-zeroing when no capture arrives.
     */
    private boolean inputPlanesDirty;
    /** Pump thread only: the render passed the ended input's first zero-width block (ruling E276). */
    private boolean inputEnded;

    /**
     * Pre-allocates every buffer the render loop needs and claims the render
     * drive's lane from the engine's health record. Runs on the lifecycle
     * (non-RT) thread.
     *
     * @param backend      the open backend to stream through; must not be null
     * @param engine       the engine whose {@code processBlock} drives rendering;
     *                     must not be null
     * @param format       the engine's core format (channels / buffer size /
     *                     sample rate); must not be null
     * @param openedFormat the negotiated SDK format the backend was actually
     *                     opened with — its sample rate stamps the sunk blocks;
     *                     must not be null, and its channel count and sample
     *                     rate must equal {@code format}'s (only the BIT DEPTH
     *                     may be renegotiated — see the constructor's
     *                     invariant check)
     * @throws IllegalArgumentException if {@code openedFormat} disagrees with
     *                                  {@code format} on channels or sample rate
     */
    EngineStreamPump(AudioBackend backend, AudioEngine engine,
                     AudioFormat format,
                     com.benesquivelmusic.daw.sdk.audio.AudioFormat openedFormat) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(openedFormat, "openedFormat must not be null");
        // Defence in depth at the exact seam that would otherwise MISLABEL
        // (story 316 review): the planes, the interleave buffer and the
        // reusable block below are built from the ENGINE format, while the
        // block is stamped with the OPENED sample rate. A wider/narrower
        // negotiated channel count would make every sink reject the block,
        // and a different negotiated rate would relabel un-resampled audio —
        // an inaudible-in-code, very audible-in-speakers pitch shift. The
        // ladder already enforces this invariant when it picks a rung
        // (AudioEngine.requireRenderableNegotiation); asserting it here means
        // no future caller can construct a mislabelling pump silently.
        if (openedFormat.channels() != format.channels()
                || Double.compare(openedFormat.sampleRate(), format.sampleRate()) != 0) {
            throw new IllegalArgumentException(
                    "openedFormat must match the engine format on channels and sample rate"
                            + " (only the bit depth may be renegotiated): engine "
                            + format.channels() + " ch @ " + format.sampleRate()
                            + " Hz, opened " + openedFormat.channels() + " ch @ "
                            + openedFormat.sampleRate() + " Hz");
        }
        this.channels = format.channels();
        this.bufferFrames = format.bufferSize();
        this.blockPeriodNanos =
                (long) (bufferFrames * 1_000_000_000L / format.sampleRate());
        this.haltThreshold = haltThreshold(format);
        this.input = new float[Math.max(1, backend.openedInputChannels())][bufferFrames];
        this.inputViews = new float[input.length + 1][][];
        for (int width = 0; width <= input.length; width++) inputViews[width] = java.util.Arrays.copyOf(input, width);
        availableInputChannels = backend.openedInputChannels();
        this.ownInputOpened = availableInputChannels > 0;
        this.output = new float[channels][bufferFrames];
        this.interleaved = new float[channels * bufferFrames];
        this.reusableBlock = new AudioBlock(
                openedFormat.sampleRate(), channels, bufferFrames, interleaved);
        this.streamGeneration = engine.getStreamGeneration();
        this.xrunDetector = engine.getXrunDetector();
        // Claimed last, once nothing above can throw, so a failed construction
        // never leaves a lane registered (story 337, ruling E18). A RenderThread
        // is a daemon platform thread at MAX_PRIORITY that carries the lane.
        this.lane = engine.audioHealth().claimLane(LaneRole.RENDER_DRIVE);
        this.thread = new RenderThread(lane, this::renderLoop, THREAD_NAME);
        // The loop's outer catch-all turns every throwable into a counted
        // fault or the recorded honest exit (story 337, ruling E9), so only a
        // throwable the catch-all itself cannot handle can still kill the
        // thread. A silently dead pump leaves the engine RUNNING with the RT
        // clock claimed and nothing driving it, so the death is logged, then
        // recorded as the drive's halt, which the drain surfaces once. The
        // handler runs on the dying thread, which is the lane's one writer.
        this.thread.setUncaughtExceptionHandler((deadThread, failure) -> {
            LOG.log(Level.SEVERE,
                    "engine-render-pump thread died; audio output is dead until"
                            + " the stream is stopped and reopened",
                    failure);
            try {
                halt(failure);
            } catch (Throwable ignored) {
                // Nothing is left to report it with: the SEVERE line above is the record.
            }
        });
    }

    /**
     * Returns how many consecutive faulted blocks end the render drive: one
     * second of continuously faulted audio counted in frames,
     * {@code max(8, ceil(sampleRate / bufferFrames))} (story 337, ruling E8)
     * — 22 blocks at 2048 frames and 44.1&nbsp;kHz, 6000 at 32 frames and
     * 192&nbsp;kHz. Counting frames instead of reading a clock is the same at
     * every block size and deterministic in tests; the floor of 8 binds only
     * for degenerate formats.
     *
     * @param format the engine format
     * @return the threshold, at least 8
     */
    static int haltThreshold(AudioFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        return Math.max(MIN_HALT_THRESHOLD,
                (int) Math.ceil(format.sampleRate() / format.bufferSize()));
    }

    /**
     * Subscribes to the backend's capture stream and starts the render
     * thread. Any {@link RuntimeException} the subscription raises propagates
     * to the caller — {@link AudioEngine} treats it as a pump-start failure,
     * unwinds what its start had claimed and rethrows. That unwind is not a
     * fallback: unlike a rung whose OPEN is refused, a failed pump start does
     * not move on to the next ladder rung. A failed start retires the lane
     * the constructor claimed: its thread never ran.
     *
     * @throws IllegalStateException if the pump was already started
     */
    void start() {
        if (running || thread.isAlive()) {
            throw new IllegalStateException("Pump already started");
        }
        running = true;
        try {
            backend.inputBlocks().subscribe(new InputSubscriber());
            thread.start();
        } catch (RuntimeException | Error startFailure) {
            running = false;
            cancelInputSubscription();
            if (!thread.isAlive()) {
                retireLane();
            }
            throw startFailure;
        }
    }

    /**
     * Stops the render loop: clears the running flag, interrupts and unparks
     * the pump thread (it may be parked in
     * {@link AudioBackend#awaitSinkCapacity(long)} or blocked inside
     * {@code sink}), cancels the capture subscription, and joins the thread
     * with a bounded wait. Idempotent; never throws. A confirmed exit retires
     * the pump's lane into the engine's health record, folding its counts in
     * (story 337, ruling E18); its undrained descriptors stay readable until
     * the drain takes them (story 337, ruling E81). A timed-out join keeps
     * the lane live.
     *
     * @return {@code true} when the pump thread is confirmed exited (or was
     *         never started) — only then can the caller know nothing will
     *         call {@code processBlock} any more; {@code false} when the
     *         bounded join timed out with the thread still alive (possibly
     *         mid-{@code processBlock}), in which case a later {@code stop()}
     *         retries the join — the loop exits on its cleared running flag
     */
    boolean stop() {
        running = false;
        cancelInputSubscription();
        if (!thread.isAlive() && thread.getState() == Thread.State.NEW) {
            retireLane();
            return true;
        }
        thread.interrupt();
        LockSupport.unpark(thread);
        try {
            thread.join(STOP_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOG.warning("engine-render-pump did not exit within " + STOP_JOIN_MILLIS
                    + " ms; the stop must be retried once the loop unblocks");
            return false;
        }
        retireLane();
        return true;
    }

    /**
     * Returns {@code true} while the render loop is meant to be running —
     * {@code false} once it stopped, including after the honest exit of
     * story 337, ruling E10.
     */
    boolean isRunning() {
        return running && thread.isAlive();
    }

    /** Number of captured input blocks dropped because the queue was full. */
    long droppedInputBlocks() {
        return droppedInputBlocks.get();
    }

    /**
     * Number of captured blocks the backend has handed this pump, whatever
     * their content and whether or not they could be queued (story 338,
     * ruling E53). Any thread; lock-free.
     */
    long deliveredInputBlocks() {
        return deliveredInputBlocks.get();
    }

    /**
     * Whether the stream's own input still feeds this pump: it is running
     * and the input neither ended nor was declared stalled.
     */
    boolean inputLive() {
        return running && !inputStreamTerminated;
    }

    /** Whether the stream was opened with an input of its own. */
    boolean hasOwnInput() {
        return ownInputOpened;
    }

    /** Whether {@code candidate} is the engine this pump renders for. */
    boolean belongsTo(AudioEngine candidate) {
        return engine == candidate;
    }

    /**
     * Ends this pump's input as a stall (story 338): from the next block that polls no captured block, the
     * render passes zero-width input to the end of the stream, so the take flags the input's tracks; blocks
     * already queued are still rendered first (ruling E47), and a block a delivery already past its gate adds
     * after that block is discarded unrendered (ruling E276). Acts on this pump only, so a declaration that
     * arrives after the stream was reopened cannot end the new stream's input. Any non-real-time thread;
     * lock-free.
     *
     * @return {@code true} only for the call that ended a live input
     */
    boolean declareInputStalled() {
        if (!inputLive() || !inputStallDeclared.compareAndSet(false, true)) {
            return false;
        }
        inputStreamTerminated = true;
        return true;
    }

    /**
     * Returns {@code true} once this pump ended itself through the honest
     * exit — its terminal {@code audio.render.halted} record is counted in
     * its own lane (story 337, rulings E10 and E69); never after a plain
     * {@link #stop()}, which records nothing. Monotonic: once {@code true},
     * it stays {@code true} for this pump. Any non-real-time reader.
     */
    boolean hasHalted() {
        return lane.count(AudioFailureCategory.RENDER_HALTED) > 0;
    }

    /** Number of {@code sink} calls that threw and were survived — this pump's lane. */
    long sinkFailures() {
        return lane.count(AudioFailureCategory.SINK);
    }

    /**
     * Number of {@code processBlock} calls that threw while the engine ran
     * and were survived by rendering silence for the faulted block — this
     * pump's lane.
     */
    long renderFaults() {
        return lane.count(AudioFailureCategory.RENDER);
    }

    /** Number of {@code awaitSinkCapacity} calls that threw and were survived — this pump's lane. */
    long pacingFaults() {
        return lane.count(AudioFailureCategory.PACING);
    }

    /** The render-drive lane this pump records into. */
    AudioHealthLane lane() {
        return lane;
    }

    /** Retires {@link #lane} once; only after the thread exited or never started. */
    private void retireLane() {
        if (laneRetired.compareAndSet(false, true)) {
            engine.audioHealth().retireLane(lane);
        }
    }

    /**
     * Cancels and forgets the capture subscription, if one was ever stored.
     * Null-tolerant and idempotent: the stop path and a late
     * {@link InputSubscriber#onSubscribe(Flow.Subscription)} may both reach
     * it for the same subscription. The read-clear-cancel is deliberately
     * not atomic — the stop path must stay non-blocking — so a caller that
     * arrives after the field is cleared cancels nothing, while two that
     * read the same non-null reference first both cancel it; see
     * {@code onSubscribe}'s note on why that is benign.
     */
    private void cancelInputSubscription() {
        Flow.Subscription subscription = this.inputSubscription;
        if (subscription != null) {
            this.inputSubscription = null;
            cancelQuietly(subscription);
        }
    }

    /**
     * Cancels a subscription, logging rather than propagating a refusal: a
     * cancel is best-effort teardown of a stream that is going away, and
     * this runs on stop paths that must never throw.
     *
     * @param subscription the subscription to cancel; must not be null
     */
    private static void cancelQuietly(Flow.Subscription subscription) {
        try {
            subscription.cancel();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Input subscription cancel failed", e);
        }
    }

    // ── The render loop (pump thread only) ───────────────────────────────

    /**
     * The render drive. Records into {@link #lane}; never logs or surfaces,
     * and takes no lock but the input queue's {@code poll} (story 337,
     * rulings E9, E14 and E46). The outer catch-all is the
     * terminal exit for anything the specific catches below do not handle —
     * {@link #fillInputPlanes()} included — so the thread never dies by
     * unwinding.
     */
    private void renderLoop() {
        try {
            while (running) {
                boolean polled = fillInputPlanes();
                if (!renderOneBlock(polled)) {
                    return;
                }
                // A fault below owes this iteration a FLOOR of one block
                // period, measured from the moment the fault happened. It is
                // deliberately not an extra park: the awaitSinkCapacity that
                // follows may pace the iteration in full by itself (see
                // parkUntil).
                boolean pacingOwed = false;
                long paceDeadlineNanos = 0L;
                try {
                    backend.sink(reusableBlock);
                    // The render-drive heartbeat: a block the backend accepted
                    // (story 337, ruling E5). A sink that threw does not beat.
                    lane.beat();
                } catch (VirtualMachineError fatal) {
                    halt(fatal);
                    return;
                } catch (Throwable sinkFailure) {
                    if (!running) {
                        return;
                    }
                    lane.record(AudioFailureCodes.SINK, sinkFailure, null, 0);
                    pacingOwed = true;
                    paceDeadlineNanos = System.nanoTime() + blockPeriodNanos;
                }
                if (!running) {
                    return;
                }
                try {
                    backend.awaitSinkCapacity(blockPeriodNanos);
                } catch (VirtualMachineError fatal) {
                    halt(fatal);
                    return;
                } catch (Throwable pacingFault) {
                    // A throwing pacing seam must not kill the render drive
                    // either — record it and stand in for the wait that threw.
                    // The loop cannot know how much of that wait the seam had
                    // actually performed before it threw, so it credits none
                    // of it: the deadline is re-armed here and the floor below
                    // pays a whole period. Deliberately conservative — a seam
                    // that parked most of a period first is then paced
                    // slightly long, which is the safe direction.
                    if (!running) {
                        return;
                    }
                    lane.record(AudioFailureCodes.PACING, pacingFault, null, 0);
                    pacingOwed = true;
                    paceDeadlineNanos = System.nanoTime() + blockPeriodNanos;
                }
                if (pacingOwed) {
                    parkUntil(paceDeadlineNanos);
                }
            }
        } catch (Throwable escaped) {
            halt(escaped);
        }
    }

    /**
     * Renders one block into the output planes and the interleave buffer.
     *
     * <p>Input of an ended stream (its publisher closed, or the engine declared it stalled) is passed at zero
     * width, so the take flags it — but only once the blocks it delivered before it ended are rendered (story
     * 338, ruling E47) — and from its first zero-width block to the end of the stream (ruling E276). A block
     * rendered with no captured block while the input is live is stamped as input-starved for the duration of
     * {@code processBlock}, so the take's capture callback, which runs on this thread inside it, marks its
     * slot (amendment A6).</p>
     *
     * @param polled whether {@link #fillInputPlanes()} filled the input planes
     *               from a captured block this iteration
     * @return {@code false} when the loop must end — the engine stopped (story
     *         337, rulings E6 and E7), or the drive halted (rulings E8 and E9)
     */
    private boolean renderOneBlock(boolean polled) {
        try {
            long renderStartNanos = System.nanoTime();
            boolean terminated = inputStreamTerminated;
            int inputWidth = terminated && !polled ? 0 : availableInputChannels;
            inputEnded |= terminated && !polled;
            boolean starved = ownInputOpened && !polled && !terminated;
            if (starved) {
                engine.setCapturedInputStarved(true);
            }
            boolean rendered;
            try {
                rendered = engine.processBlock(inputViews[inputWidth], output, bufferFrames, interleaved);
            } finally {
                if (starved) {
                    engine.setCapturedInputStarved(false);
                }
            }
            if (!rendered) {
                // Story 337, ruling E6: a stopped engine wrote silence and
                // recorded nothing; the lifecycle thread owns the teardown.
                return false;
            }
            // Only a rendered block ticks: a faulted one is already counted
            // as a render fault, and a tick would count it twice.
            XrunDetector detector = this.xrunDetector;
            if (detector != null) {
                detector.recordTick(System.nanoTime() - renderStartNanos);
            }
            renderFaultStreak = 0;
            return true;
        } catch (VirtualMachineError fatal) {
            halt(fatal);
            return false;
        } catch (Throwable renderFault) {
            // Test the STATE, not the exception type (story 316 review; story
            // 337, ruling E7): a throw while the engine or this pump is no
            // longer running is teardown — for example stop()'s interrupt
            // surfacing from a blocking call — and exits with no record.
            if (!running || !engine.isRunning()) {
                return false;
            }
            renderFaultStreak++;
            lane.record(AudioFailureCodes.RENDER, renderFault, null, renderFaultStreak);
            if (renderFaultStreak >= haltThreshold) {
                halt(renderFault);
                return false;
            }
            // A plugin NPE (or any other render-path fault) must not kill
            // audio: sink silence for this block so the device keeps its
            // cadence; the partial write must not leak out.
            zeroOutputPlanes();
            RenderPipeline.writeInterleavedOutput(output, interleaved, channels, bufferFrames, null);
            return true;
        }
    }

    /**
     * The honest exit, pump side (story 337, ruling E10): this pump stops
     * running, then writes one terminal {@code audio.render.halted} record —
     * the cause, the streak length and the stream generation — so a reader
     * that sees the record already sees a dead drive. It never touches
     * the engine's lifecycle lock, stream state, transport or backend.
     */
    private void halt(Throwable cause) {
        // running FIRST: a thread that has seen this record and then asks
        // AudioEngine.isRenderDriveAlive() — Play's reap, story 338's watchdog —
        // must find the drive already dead (story 337, ruling E60);
        // isRenderDriveHalted() reads this record itself.
        running = false;
        lane.record(AudioFailureCodes.RENDER_HALTED, cause, streamGeneration, renderFaultStreak);
    }

    /**
     * Holds a faulted iteration to a FLOOR of one buffer period: parks for
     * whatever is LEFT of {@code deadlineNanos}, and not at all once that
     * deadline has passed.
     *
     * <p><strong>Why a floor rather than a park</strong> (story 316 review,
     * round 4). The loop's cadence comes from
     * {@link AudioBackend#awaitSinkCapacity(long)}, and how much time that
     * seam costs an iteration whose {@code sink} just threw depends on the
     * backend, so an unconditional park here would be right for some and
     * exactly wrong for others:</p>
     * <ul>
     *   <li>The interface's own default implementation is
     *       {@code LockSupport.parkNanos(timeoutNanos)} — a full-period park
     *       taken whether or not anything was enqueued.
     *       {@code WasapiBackend}, {@code CoreAudioBackend},
     *       {@code JackBackend} and {@code MockAudioBackend} all inherit it
     *       while overriding a {@code sink} that can throw, as does
     *       {@code AsioBackend} whenever its buffer-switch bridge is absent.
     *       Those iterations are already paced in full; adding a park would
     *       run the loop at half real time for the duration of the fault.</li>
     *   <li>{@code AsioBackend} with a live bridge polls its output ring and
     *       returns the moment that ring reads drained — and a {@code sink}
     *       that threw put nothing into the ring for it to wait on, having
     *       thrown before {@code bridge.write(...)}.</li>
     *   <li>{@code JavaxSoundBackend}'s override is empty: its blocking
     *       {@code sink} <em>is</em> its pacing, and that {@code sink} is
     *       precisely what failed.</li>
     *   <li>An {@code awaitSinkCapacity} that itself throws cannot be credited
     *       with any of the wait it was asked for — the loop has no way to
     *       know how far it got — which is why the deadline is re-armed at
     *       that throw and the floor pays the whole period.</li>
     * </ul>
     *
     * <p>Parking only the remainder covers every one of them: time the backend
     * spent counts towards the period, and this supplies only the shortfall.
     * Without it, a PERSISTENT fault on one of the no-wait backends turns the
     * loop into a hot spin that burns a core and — the real damage — advances
     * transport one block per iteration with no hardware clock behind it,
     * running the timeline far faster than real time until the fault clears.</p>
     *
     * <p>The remaining time is a {@link System#nanoTime()} DIFFERENCE tested
     * against zero, never an absolute {@code <} comparison of two readings, so
     * it stays correct across that counter's wraparound.</p>
     *
     * <p>The single {@code parkNanos} may return early — a spurious wakeup, or
     * an unpark from anywhere — and this deliberately does NOT loop to re-park.
     * A floor on the fault path is a pacing measure, not a hard guarantee, and
     * a re-parking loop would fight {@link #stop()}'s unpark.</p>
     *
     * <p><strong>This cannot delay {@link #stop()}.</strong> That method clears
     * {@code running}, then interrupts AND unparks this thread.
     * {@link LockSupport#parkNanos(Object, long)} returns immediately for an
     * already-interrupted thread and leaves the interrupt flag set — it never
     * throws {@link InterruptedException} and never clears the flag — and an
     * unpark permit issued before the park begins is consumed by that park
     * rather than lost. Either signal therefore ends the park at once, and the
     * {@code while (running)} test ends the loop on the next pass.</p>
     *
     * <p>The blocker overload is used deliberately: a thread dump then reports
     * this pump as the park's blocker instead of a bare {@code LockSupport},
     * which is the difference between "the render pump is pacing itself" and
     * an unattributed park when someone is diagnosing silent output.</p>
     *
     * @param deadlineNanos the {@link System#nanoTime()} reading at which this
     *                      iteration's floor expires — a deadline already in
     *                      the past parks not at all
     */
    private void parkUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0L) {
            LockSupport.parkNanos(this, remaining);
        }
    }

    /** Silences a faulted block: a partial plugin write must not leak out. */
    private void zeroOutputPlanes() {
        for (int ch = 0; ch < channels; ch++) {
            java.util.Arrays.fill(output[ch], 0f);
        }
    }

    /**
     * De-interleaves the oldest queued captured block into the input planes, or zeroes them when no capture
     * arrived this period or the input ended; a block polled after that is discarded unrendered (ruling E276).
     *
     * @return whether the planes hold a captured block
     */
    private boolean fillInputPlanes() {
        AudioBlock block = inputQueue.poll();
        if (block == null || inputEnded) {
            if (inputPlanesDirty) {
                for (int ch = 0; ch < input.length; ch++) {
                    java.util.Arrays.fill(input[ch], 0f);
                }
                inputPlanesDirty = false;
            }
            return false;
        }
        int blockChannels = block.channels();
        int usableChannels = Math.min(blockChannels, input.length);
        availableInputChannels = usableChannels;
        int usableFrames = Math.min(block.frames(), bufferFrames);
        float[] samples = block.samples();
        for (int ch = 0; ch < usableChannels; ch++) {
            float[] plane = input[ch];
            for (int frame = 0; frame < usableFrames; frame++) {
                plane[frame] = samples[frame * blockChannels + ch];
            }
            if (usableFrames < bufferFrames) {
                java.util.Arrays.fill(plane, usableFrames, bufferFrames, 0f);
            }
        }
        for (int ch = usableChannels; ch < input.length; ch++) {
            java.util.Arrays.fill(input[ch], 0f);
        }
        inputPlanesDirty = true;
        return true;
    }

    /**
     * Non-RT subscriber feeding the bounded input queue. A publisher delivers
     * on its own thread, never a driver's real-time one, so offering into a
     * lock-based queue is fine here; a block the publisher drops for this
     * subscriber is reported to {@link #inputBlockDropped} on the publishing
     * thread, under the publisher's lock.
     */
    private final class InputSubscriber implements Flow.Subscriber<AudioBlock>, InputBlockLossListener {

        /**
         * Stores the capture subscription and opens the flow — unless the
         * pump has already stopped (story 316 re-review).
         *
         * <p>{@link java.util.concurrent.SubmissionPublisher} may deliver
         * this callback ASYNCHRONOUSLY, so a pause or a stop can run between
         * {@link EngineStreamPump#start()}'s {@code subscribe} call and this
         * method. {@link EngineStreamPump#cancelInputSubscription()} then
         * finds a {@code null} field and cancels nothing, and this callback
         * would go on to store the subscription and request from it on a
         * dead pump: a stale capture subscriber left attached, filling a
         * bounded queue nobody drains and surviving the very pause that was
         * meant to detach it — until the next resume replaced it, silently
         * doubling the subscribers.</p>
         *
         * <p>BOTH checks are needed. The first is the cheap common case —
         * the pump had already stopped when this callback arrived, so the
         * subscription is cancelled without ever being published to the
         * field. The second closes the window in which the stop ran BETWEEN
         * that check and the store: it saw the field still {@code null}, so
         * only this thread can clean up.</p>
         *
         * <p>A volatile cannot make check-and-store atomic, and this is
         * deliberately not locked — the stop path must stay non-blocking.
         * The residual window is a stop landing between the store and the
         * second check: both sides then reach
         * {@link EngineStreamPump#cancelInputSubscription()} and, because
         * its read-clear-cancel is not atomic either, may both cancel the
         * same subscription. That is benign —
         * {@link Flow.Subscription#cancel()} must tolerate repeated calls
         * (Reactive Streams rule 3.7, which {@code Flow} mirrors), and
         * {@link EngineStreamPump#cancelQuietly(Flow.Subscription)}
         * swallows a refusal either way. What can no longer happen once
         * this method returns is a stopped pump holding a requested,
         * uncancelled subscription.</p>
         *
         * @param subscription the capture subscription the backend granted
         */
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (!running) {
                cancelQuietly(subscription);
                return;
            }
            inputSubscription = subscription;
            if (!running) {
                cancelInputSubscription();
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AudioBlock item) {
            deliveredInputBlocks.incrementAndGet();
            if (!running || inputStreamTerminated) {
                return;
            }
            if (!inputQueue.offer(item)) {
                droppedInputBlocks.incrementAndGet();
                engine.noteCapturedInputBlockLost();
            }
        }

        /** A block the publisher dropped for this subscriber, counted under {@link #onNext}'s own gate. */
        @Override
        public void inputBlockDropped(AudioBlock block) {
            if (running && !inputStreamTerminated) {
                engine.noteCapturedInputBlockLost();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            inputStreamTerminated = true;
            LOG.log(Level.WARNING, "Capture stream failed; input planes fall silent",
                    throwable);
        }

        @Override
        public void onComplete() {
            // Keep the output clock running while zero-width input flags the lost source.
            inputStreamTerminated = true;
        }
    }
}
