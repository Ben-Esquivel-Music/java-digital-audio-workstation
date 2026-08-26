package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Flow;
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
 * <h2>Fault tolerance</h2>
 * <ul>
 *   <li>{@code processBlock} throwing {@link IllegalStateException} <em>while
 *       the engine (or this pump) is no longer running</em> means the engine
 *       stopped racing the pump — the loop exits quietly. The condition is
 *       the STATE, not the exception type (story 316 review): every other
 *       {@code IllegalStateException} — a mixer, effects chain, insert
 *       plugin, MIDI renderer or graph scheduler asserting an invariant while
 *       the engine IS running — is treated as an ordinary render fault, not
 *       as a reason to end audio.</li>
 *   <li>Any other {@link RuntimeException} from {@code processBlock} (for
 *       example a plugin NPE) is logged once at {@link Level#SEVERE},
 *       counted ({@link #renderFaults()}), and survived: the faulted block
 *       is replaced with silence and the loop keeps running — a misbehaving
 *       plugin must not kill audio. (Surfacing engine health to the user is
 *       story 338.)</li>
 *   <li>{@code sink} throwing (for example a frame-size mismatch after a
 *       driver reset) is logged once at {@link Level#WARNING}, counted, and
 *       the loop keeps running: the reopen path fixes the shape; a transient
 *       fault must not kill the render drive. That iteration is then held to
 *       a <em>floor</em> of one block period ({@link #parkUntil(long)}) — a
 *       floor rather than an extra wait, because what the following
 *       {@code awaitSinkCapacity} costs after a failed {@code sink} depends
 *       entirely on the backend (see below).</li>
 *   <li>{@code awaitSinkCapacity} throwing is logged once at
 *       {@link Level#SEVERE} and the same floor applies. This is the harsher
 *       fault: a seam that threw cannot be credited with any of the wait it
 *       was asked for, so the loop re-arms the deadline at the throw and the
 *       floor supplies the whole period rather than the loop dying silently
 *       or spinning un-paced.</li>
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
 *   <li>An {@link Error} still kills the pump thread; the installed
 *       uncaught-exception handler logs it at {@link Level#SEVERE} so the
 *       death is at least visible.</li>
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

    private final AudioBackend backend;
    private final AudioEngine engine;
    private final int channels;
    private final int bufferFrames;
    private final long blockPeriodNanos;

    // Pre-allocated render state — pump thread only.
    private final float[][] input;
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
    private final AtomicLong droppedInputBlocks = new AtomicLong();
    private final AtomicLong sinkFailures = new AtomicLong();
    private final AtomicLong renderFaults = new AtomicLong();
    private volatile boolean sinkFailureLogged;
    private volatile boolean renderFaultLogged;
    private volatile boolean pacingFaultLogged;

    private final Thread thread;
    private volatile boolean running;
    private volatile Flow.Subscription inputSubscription;
    /**
     * True once the input planes were written from a captured block and so
     * need re-zeroing when no capture arrives.
     */
    private boolean inputPlanesDirty;

    /**
     * Pre-allocates every buffer the render loop needs. Runs on the
     * lifecycle (non-RT) thread.
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
        this.input = new float[channels][bufferFrames];
        this.output = new float[channels][bufferFrames];
        this.interleaved = new float[channels * bufferFrames];
        this.reusableBlock = new AudioBlock(
                openedFormat.sampleRate(), channels, bufferFrames, interleaved);
        this.thread = Thread.ofPlatform()
                .name(THREAD_NAME)
                .daemon(true)
                .priority(Thread.MAX_PRIORITY)
                .unstarted(this::renderLoop);
        // RuntimeExceptions are survived inside the loop; only an Error can
        // still kill the thread. Make that death loud — a silently dead pump
        // leaves the engine RUNNING with the RT clock claimed and nothing
        // driving it (engine-health surfacing is story 338).
        this.thread.setUncaughtExceptionHandler((deadThread, failure) ->
                LOG.log(Level.SEVERE,
                        "engine-render-pump thread died; audio output is dead until"
                                + " the stream is stopped and reopened",
                        failure));
    }

    /**
     * Subscribes to the backend's capture stream and starts the render
     * thread. Any {@link RuntimeException} the subscription raises propagates
     * to the caller — {@link AudioEngine} treats it as a pump-start failure,
     * unwinds what its start had claimed and rethrows. That unwind is not a
     * fallback: unlike a rung whose OPEN is refused, a failed pump start does
     * not move on to the next ladder rung.
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
            throw startFailure;
        }
    }

    /**
     * Stops the render loop: clears the running flag, interrupts and unparks
     * the pump thread (it may be parked in
     * {@link AudioBackend#awaitSinkCapacity(long)} or blocked inside
     * {@code sink}), cancels the capture subscription, and joins the thread
     * with a bounded wait. Idempotent; never throws.
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
        return true;
    }

    /** Returns {@code true} while the render loop is meant to be running. */
    boolean isRunning() {
        return running && thread.isAlive();
    }

    /** Number of captured input blocks dropped because the queue was full. */
    long droppedInputBlocks() {
        return droppedInputBlocks.get();
    }

    /** Number of {@code sink} calls that threw and were survived. */
    long sinkFailures() {
        return sinkFailures.get();
    }

    /**
     * Number of {@code processBlock} calls that threw a non-lifecycle
     * {@link RuntimeException} (for example a plugin NPE) and were survived
     * by rendering silence for the faulted block.
     */
    long renderFaults() {
        return renderFaults.get();
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

    private void renderLoop() {
        while (running) {
            fillInputPlanes();
            try {
                engine.processBlock(input, output, bufferFrames);
            } catch (RuntimeException renderFault) {
                // Test the STATE, not the exception type (story 316 review):
                // any collaborator reachable from processBlock — the mixer, an
                // effects chain, an insert plugin asserting "not prepared"
                // after a mid-session swap, the MIDI renderer, the graph
                // scheduler — can throw IllegalStateException, and treating
                // every one of them as "the engine stopped" silently ended
                // audio. Only a stopped engine (or a stopped pump) is a
                // lifecycle race.
                if (renderFault instanceof IllegalStateException
                        && (!running || !engine.isRunning())) {
                    // The engine stopped racing the pump — exit quietly; the
                    // lifecycle thread owns the stream teardown.
                    return;
                }
                // A plugin NPE (or any other render-path fault) must not
                // kill audio: log once, count, and sink silence for this
                // block so the device keeps its cadence.
                renderFaults.incrementAndGet();
                if (!renderFaultLogged) {
                    renderFaultLogged = true;
                    LOG.log(Level.SEVERE,
                            "processBlock threw; the faulted block is rendered as"
                                    + " silence and the pump keeps running",
                            renderFault);
                }
                zeroOutputPlanes();
            }
            interleaveOutput();
            // A fault below owes this iteration a FLOOR of one block period,
            // measured from the moment the fault happened. It is deliberately
            // not an extra park: the awaitSinkCapacity that follows may pace
            // the iteration in full by itself (see parkUntil).
            boolean pacingOwed = false;
            long paceDeadlineNanos = 0L;
            try {
                backend.sink(reusableBlock);
            } catch (RuntimeException sinkFailure) {
                sinkFailures.incrementAndGet();
                if (!sinkFailureLogged) {
                    sinkFailureLogged = true;
                    LOG.log(Level.WARNING,
                            "Audio sink rejected a rendered block; the pump keeps running"
                                    + " (a driver reset/reopen fixes the shape)",
                            sinkFailure);
                }
                pacingOwed = true;
                paceDeadlineNanos = System.nanoTime() + blockPeriodNanos;
            }
            if (!running) {
                return;
            }
            try {
                backend.awaitSinkCapacity(blockPeriodNanos);
            } catch (RuntimeException pacingFault) {
                // A throwing pacing seam must not kill the render drive
                // either — log once and stand in for the wait that threw.
                // The loop cannot know how much of that wait the seam had
                // actually performed before it threw, so it credits none of
                // it: the deadline is re-armed here and the floor below pays
                // a whole period. Deliberately conservative — a seam that
                // parked most of a period first is then paced slightly long,
                // which is the safe direction.
                if (!pacingFaultLogged) {
                    pacingFaultLogged = true;
                    LOG.log(Level.SEVERE,
                            "awaitSinkCapacity threw; the pump holds each iteration to"
                                    + " a floor of one block period until the seam"
                                    + " recovers",
                            pacingFault);
                }
                pacingOwed = true;
                paceDeadlineNanos = System.nanoTime() + blockPeriodNanos;
            }
            if (pacingOwed) {
                parkUntil(paceDeadlineNanos);
            }
        }
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
     * De-interleaves the oldest queued captured block into the input planes,
     * or zeroes them when no capture arrived this period.
     */
    private void fillInputPlanes() {
        AudioBlock block = inputQueue.poll();
        if (block == null) {
            if (inputPlanesDirty) {
                for (int ch = 0; ch < channels; ch++) {
                    java.util.Arrays.fill(input[ch], 0f);
                }
                inputPlanesDirty = false;
            }
            return;
        }
        int blockChannels = block.channels();
        int usableChannels = Math.min(blockChannels, channels);
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
        for (int ch = usableChannels; ch < channels; ch++) {
            java.util.Arrays.fill(input[ch], 0f);
        }
        inputPlanesDirty = true;
    }

    private void interleaveOutput() {
        for (int ch = 0; ch < channels; ch++) {
            float[] plane = output[ch];
            for (int frame = 0; frame < bufferFrames; frame++) {
                interleaved[frame * channels + ch] = plane[frame];
            }
        }
    }

    /**
     * Non-RT subscriber feeding the bounded input queue. The backend delivers
     * blocks on its own (non-RT) marshalling thread — the ASIO backend's
     * {@code asio-input-drain}, the adapter's {@code native-input-drain} —
     * so offering into a lock-based queue is fine here.
     */
    private final class InputSubscriber implements Flow.Subscriber<AudioBlock> {

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
            if (!inputQueue.offer(item)) {
                droppedInputBlocks.incrementAndGet();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            LOG.log(Level.WARNING, "Capture stream failed; input planes fall silent",
                    throwable);
        }

        @Override
        public void onComplete() {
            // Stream closed — nothing to do; the loop keeps rendering silence
            // into the input planes until the lifecycle thread stops the pump.
        }
    }
}
