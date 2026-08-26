package com.benesquivelmusic.daw.sdk.audio;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Single platform thread used for every ASIO host downcall.
 *
 * <p>Steinberg's Windows host glue initializes COM and creates an in-process
 * driver object. Keeping enumeration, init, capability calls, control-panel
 * dispatch, exit, and COM release on one long-lived platform thread preserves
 * that apartment affinity and makes it impossible to run these operations on
 * the real-time render thread.</p>
 *
 * <h2>Why the wait is BOUNDED (story 316 re-review)</h2>
 * <p>{@link #call(Operation)} used to wait on {@code Future.get()} with no
 * timeout. Every ASIO operation the engine performs — {@code ASIOInit},
 * {@code createBuffers}, {@code ASIOStart}, {@code ASIOStop},
 * {@code ASIOExit} — runs through here from inside {@code AudioEngine}'s
 * lifecycle lock, so ONE driver wedged in a native call turned a
 * single-caller stall into an app-wide lifecycle stall: Play, Stop, the
 * device-event reopen worker and the audio controller's shutdown all block
 * behind that same lock, forever. A bounded wait turns it into an ordinary
 * failed hop the fallback ladder can walk past.</p>
 *
 * <p>{@link #DEFAULT_BUDGET} is deliberately generous rather than snappy.
 * {@code ASIOInit} legitimately takes seconds on a class-compliant USB
 * interface that has to power up, re-clock and hand out its buffers, and a
 * budget that clipped a HEALTHY slow driver would be worse than the bug it
 * fixes. Fifteen seconds is far beyond any honest init and far short of
 * "the user thinks the application has crashed".</p>
 *
 * <h2>The modal control panel is EXEMPT — deliberately</h2>
 * <p>{@code ASIOControlPanel()} blocks this thread for as long as the user
 * leaves the driver's dialog open, which may be minutes. A user reading a
 * driver dialog is not a wedge, so that ONE operation goes through
 * {@link #callUnbounded(Operation)} and waits indefinitely by design.</p>
 *
 * <p>The exemption covers BOTH the budget and INTERRUPTION (story 316
 * re-review). Interrupting the caller cannot end the downcall for exactly the
 * reason a budget cannot: a thread parked in {@code ASIOControlPanel()} is
 * parked inside the driver, and nothing the host does reaches it. So honouring
 * the interrupt would not stop the panel — it would only make
 * {@link #callUnbounded(Operation)} RETURN while the driver is still inside
 * the call, which is a lie the callers act on. {@code controlPanelOpen} would
 * be cleared and nothing would be recorded in the abandoned count, so
 * {@link #isQuiesced()} would answer {@code true} about a live downcall;
 * {@code AsioCapabilityShim.openControlPanel()} normalises every throw to a
 * generic failure, which the application layer reads as "the panel is closed"
 * and uses to release the guards that keep the backend alive for the dialog's
 * lifetime — tearing down native state the modal dialog is running on. The
 * unbounded wait is therefore UNINTERRUPTIBLE, and returns only once the
 * driver really has returned.</p>
 *
 * <p>The interrupt is DEFERRED, never lost: the wait absorbs it, keeps
 * waiting, and re-asserts the calling thread's interrupt status before it
 * hands the result back, so a shutdown that arrives while the panel is open is
 * honoured at the first interruptible point AFTER the driver returns rather
 * than being discarded.</p>
 *
 * <p>The apartment affinity that makes this class necessary also means the
 * exemption cannot be isolated behind its own executor: a second thread would
 * be a second COM apartment, and the driver object lives in this one. So a
 * bounded operation submitted while the panel is open QUEUES behind it and
 * will exhaust its budget. That is the honest outcome — the driver really is
 * unavailable while its own modal panel owns it — and {@code controlPanelOpen}
 * exists so the failure says exactly that instead of blaming a wedge.</p>
 *
 * <h2>A call its caller stopped waiting for is not a finished call</h2>
 * <p>A budget — or an interrupt — stops the CALLER waiting; neither can stop
 * a downcall that is already inside the driver, because interrupting a thread
 * parked in {@code ASIOInit} does nothing. An operation that is RUNNING when
 * its caller stops waiting, whether because the budget expired or because the
 * caller was interrupted, is therefore tracked as <em>abandoned</em> until it
 * finally returns, and while one is outstanding every new BOUNDED
 * {@link #call(Operation, Duration)} fails IMMEDIATELY instead of queueing
 * behind it.</p>
 *
 * <p>Without that gate a failed open issues roughly six teardown downcalls that
 * each queue behind the wedged call and burn their own full budget — about a
 * minute and a half of app-wide lifecycle stall at {@link #DEFAULT_BUDGET}
 * each — after which the fallback ladder opens PortAudio or Java Sound over a
 * device the ASIO driver may still hold. Failing the six instantly and
 * honestly is strictly better: every teardown call site already degrades to
 * "best effort". {@link #isQuiesced()} and {@link #awaitQuiescence(Duration)}
 * are what let {@code AsioBackend} refuse to reconfigure, and defer the one
 * teardown it can never retry, until the driver lets go.</p>
 *
 * <p>Whether an operation was withdrawn is decided by a compare-and-set on
 * that operation's own {@code Phase}, never by {@code Future.cancel(false)};
 * {@code settleAbandonedWait} records why the latter cannot answer the
 * question. It is also the ONE place both ways a bounded caller stops waiting
 * — the budget expiring, the caller being interrupted — settle that phase, so
 * the two cannot drift apart about whether the driver still has the call.</p>
 */
final class AsioControlThread {

    /**
     * How long a bounded operation may occupy the control thread before the
     * caller gives up. See the class javadoc for why it is this generous.
     */
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(15);

    /**
     * How long {@link #awaitQuiescence(Duration)} sleeps between polls. Short
     * enough that a driver letting go is noticed promptly, long enough that
     * waiting out a wedged {@code ASIOInit} costs nothing measurable.
     */
    private static final long QUIESCENCE_POLL_NANOS = Duration.ofMillis(5).toNanos();

    /**
     * Lifecycle of one submitted operation.
     *
     * <p>The transitions are a handshake exactly one side can win: the task
     * moves {@code QUEUED -> RUNNING} as its first action, and a caller that
     * stopped waiting (its budget expired or it was interrupted) moves it
     * {@code QUEUED -> WITHDRAWN} or {@code RUNNING -> ABANDONED}. Neither
     * side has to guess what the other did, which is what makes "the driver
     * never saw it" a fact rather than an inference from
     * {@code Future.cancel(false)}.</p>
     */
    private enum Phase {
        /** Submitted; the control thread has not picked it up yet. */
        QUEUED,
        /** The control thread is inside {@code operation.run()}. */
        RUNNING,
        /**
         * A caller stopped waiting (its budget expired or it was interrupted)
         * before it started; it never runs.
         */
        WITHDRAWN,
        /**
         * A caller stopped waiting (its budget expired or it was interrupted)
         * while the driver call was executing.
         */
        ABANDONED,
        /** The operation returned or threw; the driver is done with it. */
        FINISHED
    }

    /**
     * How {@link #settleAbandonedWait(Future, AtomicReference)} left an
     * operation whose caller stopped waiting for it — by budget or by
     * interrupt. One outcome per side of the {@link Phase} handshake the
     * caller can win, plus the photo finish where it wins neither.
     */
    private enum Settlement {
        /**
         * Won {@code QUEUED -> WITHDRAWN}; the future is cancelled and the
         * driver never saw the operation.
         */
        WITHDRAWN,
        /**
         * Won {@code RUNNING -> ABANDONED}; counted in
         * {@link #ABANDONED_IN_FLIGHT} until the driver returns.
         */
        ABANDONED,
        /**
         * Lost both compare-and-sets — a photo finish. The driver did see the
         * operation, and it is done.
         */
        FINISHED
    }

    /**
     * How many operations a caller that stopped waiting — its budget expired
     * or it was interrupted — abandoned while they were executing and that
     * have not returned yet. Non-zero means the driver is provably still
     * inside a downcall nobody is waiting for any more — see
     * {@link #isQuiesced()}.
     */
    private static final AtomicInteger ABANDONED_IN_FLIGHT = new AtomicInteger();

    private static volatile Thread controlThread;

    /**
     * True while {@link #callUnbounded(Operation)} is executing — i.e. while
     * the driver's modal control panel owns the control thread. Read only to
     * make a timeout message truthful about WHY the thread was busy.
     *
     * <p>It is cleared only once the native call has REALLY returned (story
     * 316 re-review). That is a property of the uninterruptible wait behind
     * {@link #callUnbounded(Operation)}, not of the assignment: while the wait
     * was interruptible this flag went false — and the caller returned — with
     * {@code ASIOControlPanel()} still executing on this thread, which is the
     * state every reader of it treats as "the panel is closed".</p>
     */
    private static volatile boolean controlPanelOpen;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform()
                    .name("asio-control")
                    .daemon(true)
                    .unstarted(() -> {
                        controlThread = Thread.currentThread();
                        task.run();
                    }));

    private AsioControlThread() {
    }

    /**
     * Runs {@code operation} on the control thread, waiting at most
     * {@link #DEFAULT_BUDGET}.
     *
     * @param operation the downcall to serialize onto the control thread
     * @param <T>       the operation's result type
     * @return the operation's result
     * @throws AudioBackendException if the budget elapsed first, or if an
     *                               earlier operation whose caller stopped
     *                               waiting for it is still executing in the
     *                               driver
     * @throws InterruptedException  if the calling thread is interrupted while
     *                               waiting. The operation's phase is settled
     *                               exactly as on budget expiry, then the
     *                               interrupt status is restored and the
     *                               exception rethrown
     * @throws Throwable             whatever the operation itself threw
     */
    static <T> T call(Operation<T> operation) throws Throwable {
        return call(operation, DEFAULT_BUDGET);
    }

    /**
     * Runs {@code operation} on the control thread and waits for it
     * INDEFINITELY and UNINTERRUPTIBLY. Reserved for the modal control panel,
     * whose duration is the user's to choose; every other caller must use
     * {@link #call(Operation)}.
     *
     * <p>Neither a budget nor an interrupt can end a downcall the driver is
     * already inside, so this waits out both — see the class javadoc for why
     * honouring an interrupt here would not close the panel, only make this
     * method report a finished driver call that is still running, and hand the
     * application layer the "the panel closed" answer it uses to release the
     * guards keeping this backend alive.</p>
     *
     * <p>An interrupt that arrives during the wait is DEFERRED rather than
     * swallowed: it is re-asserted on the calling thread before this returns,
     * so a shutdown is honoured at the first interruptible point after the
     * driver lets go. The caller may therefore observe
     * {@link Thread#isInterrupted()} on a NORMAL return, which is the only
     * shape in which both facts — the driver returned, and someone asked this
     * thread to stop — can be reported truthfully at once.</p>
     *
     * <p>{@code controlPanelOpen} consequently goes false only after the
     * native call has really returned.</p>
     *
     * @param operation the modal downcall to serialize onto the control thread
     * @param <T>       the operation's result type
     * @return the operation's result
     * @throws Throwable whatever the operation itself threw
     */
    static <T> T callUnbounded(Operation<T> operation) throws Throwable {
        controlPanelOpen = true;
        try {
            return call(operation, null);
        } finally {
            controlPanelOpen = false;
        }
    }

    /**
     * Runs {@code operation} on the control thread.
     *
     * <p>A call already ON the control thread runs inline and is never
     * bounded: the budget exists to stop a caller waiting on the thread, and
     * this caller IS the thread.</p>
     *
     * <p>Package-private rather than private so a budget other than
     * {@link #DEFAULT_BUDGET} can be supplied — which is also what lets
     * {@code AsioControlThreadTest} exercise the timeout in milliseconds
     * instead of holding the build up for fifteen seconds.</p>
     *
     * <p>A BOUNDED call is refused outright while an earlier operation whose
     * caller stopped waiting for it is still executing (see
     * {@link #isQuiesced()}):
     * submitting it could only queue behind a call the host has already given
     * up on and burn its own full budget too. The unbounded path is exempt for
     * the same reason it is exempt from the budget — the modal panel is not a
     * wedge — and so is a re-entrant call from the control thread itself,
     * which would otherwise be refused on account of its own operation.</p>
     *
     * <p>The two waits differ in one further respect (story 316 re-review).
     * The BOUNDED wait stays interruptible, because an interrupt is how a
     * shutdown breaks a caller out of a wait it is entitled to abandon — the
     * budget already says this caller may stop waiting. The UNBOUNDED wait is
     * uninterruptible, because that caller is entitled to no such thing: the
     * only honest moment for it to return is when the driver returns, and
     * returning any earlier is the guard-release bug the class javadoc
     * describes. {@link #awaitUninterruptibly(Future)} is where the difference
     * lives; the {@link ExecutionException} unwrap is shared by both so a
     * driver's own failure propagates identically either way.</p>
     *
     * <p>Stopping the bounded wait — by budget or by interrupt — settles the
     * operation's phase through the one shared
     * {@link #settleAbandonedWait(Future, AtomicReference)}, so an interrupted
     * caller can no more leave a live downcall uncounted than a timed-out one
     * can. The story 316 review round that found this had the interrupt path
     * skipping the handshake entirely: the caller re-asserted its interrupt
     * and threw with the operation still QUEUED or RUNNING and nothing
     * recorded, so {@link #isQuiesced()} answered {@code true} about a driver
     * call the host had walked away from — the answer {@code AsioBackend}
     * reads as permission to load a second driver or free the shim — and a
     * queued operation ran later with nobody waiting for it. The
     * {@link InterruptedException} itself still propagates, interrupt status
     * restored; only the settlement is shared with the budget path.</p>
     *
     * @param budget the wait budget, or {@code null} to wait indefinitely and
     *               uninterruptibly — reserved for
     *               {@link #callUnbounded(Operation)}
     */
    static <T> T call(Operation<T> operation, Duration budget) throws Throwable {
        if (Thread.currentThread() == controlThread) {
            return operation.run();
        }
        if (budget != null && !isQuiesced()) {
            throw driverStillExecuting();
        }
        AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
        Future<T> pending = EXECUTOR.submit(() -> {
            if (!phase.compareAndSet(Phase.QUEUED, Phase.RUNNING)) {
                // A caller that stopped waiting — its budget expired or it was
                // interrupted — won the handshake first and walked away on the
                // fact that the driver never saw this operation (the budget
                // path says so in its message). Running it now would make that
                // fact a lie, so it is dropped instead. Nothing waits on this
                // future — the caller is long gone — but the failure is wrapped
                // like any other so the unwrap path stays honest.
                throw new OperationFailure(new AudioBackendException(
                        "ASIO driver operation was withdrawn before it started and"
                                + " was never sent to the driver."));
            }
            try {
                return operation.run();
            } catch (Throwable failure) {
                throw new OperationFailure(failure);
            } finally {
                // In a finally so an abandoned operation cannot leak its
                // outstanding count however it ends.
                if (phase.getAndSet(Phase.FINISHED) == Phase.ABANDONED) {
                    ABANDONED_IN_FLIGHT.decrementAndGet();
                }
            }
        });
        if (budget == null) {
            return awaitUninterruptibly(pending);
        }
        try {
            return pending.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Same handshake as the budget path, through the same method: an
            // interrupted caller has stopped waiting exactly as a timed-out one
            // has, and a live downcall must be counted before this caller is
            // gone. The interrupt itself is restored and rethrown unchanged.
            settleAbandonedWait(pending, phase);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timedOut) {
            throw budgetExhausted(settleAbandonedWait(pending, phase), budget, timedOut);
        } catch (ExecutionException execution) {
            throw unwrapOperationFailure(execution);
        }
    }

    /**
     * Waits for the modal control panel's operation without letting an
     * interrupt cut the wait short (story 316 re-review).
     *
     * <p>{@code Future.get()} is interruptible, and on the unbounded path that
     * was a correctness bug rather than a nicety. Interrupting a thread parked
     * in {@code ASIOControlPanel()} does nothing to the driver, so an
     * interrupted {@code get()} could only make the CALLER report a finished
     * downcall while the driver was still inside it: {@code controlPanelOpen}
     * cleared, nothing counted in {@code ABANDONED_IN_FLIGHT} so
     * {@link #isQuiesced()} answered {@code true}, and
     * {@code AsioCapabilityShim.openControlPanel()} — which normalises every
     * throw to a generic failure — handing the application layer the "the
     * panel is closed or failed" answer it uses to drop the guards that keep
     * the backend alive for the dialog's lifetime. Backend teardown would then
     * run underneath a live driver call.</p>
     *
     * <p>So the interrupt is recorded and the wait resumes. Re-asserting it in
     * the {@code finally} is what keeps this a DEFERRAL rather than a
     * swallowing: whoever asked this thread to stop still gets their answer,
     * at the first interruptible point after the driver returns. The
     * {@link ExecutionException} unwrap is the shared
     * {@link #unwrapOperationFailure(ExecutionException)}, so a driver failure
     * surfaces here exactly as it does on the bounded path.</p>
     */
    private static <T> T awaitUninterruptibly(Future<T> pending) throws Throwable {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return pending.get();
                } catch (InterruptedException deferred) {
                    interrupted = true;
                } catch (ExecutionException execution) {
                    throw unwrapOperationFailure(execution);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Strips the {@link ExecutionException} the executor wraps every failure
     * in, plus the {@link OperationFailure} the task wraps the operation's own
     * throwable in, so a caller sees exactly what the driver call threw.
     *
     * <p>Factored out rather than duplicated so the bounded and the
     * uninterruptible waits cannot drift into unwrapping a driver failure
     * differently — the two waits are allowed to differ about WAITING, never
     * about what the operation threw.</p>
     *
     * @return the throwable the caller must throw; never the wrapper
     */
    private static Throwable unwrapOperationFailure(ExecutionException execution) {
        Throwable cause = execution.getCause();
        if (cause instanceof OperationFailure wrapped) {
            return wrapped.getCause();
        }
        return cause;
    }

    /**
     * Settles, provably, whether the driver ever saw an operation whose caller
     * has stopped waiting for it. Both ways of stopping — the budget expiring
     * and the caller being interrupted — come through here and nowhere else,
     * so they cannot drift apart about what the driver still has: the two are
     * allowed to differ about what the caller is then thrown, never about
     * whether a live downcall is counted.
     *
     * <p>{@code Future.cancel(false)} cannot settle that.
     * {@link java.util.concurrent.FutureTask#run()} leaves the task in state
     * {@code NEW} for the whole duration of the callable — the state only
     * advances when the result or exception is set — so {@code cancel(false)}
     * still wins its {@code NEW -> CANCELLED} compare-and-set and returns
     * {@code true} while the callable is executing. It marks the future
     * cancelled; it does not stop the running native call. Reading that
     * {@code true} as proof of withdrawal reported "the driver never saw it"
     * in precisely the wedge case the timeout message exists to describe.</p>
     *
     * <p>The {@code Phase} handshake decides instead. {@code QUEUED ->
     * WITHDRAWN} can only succeed while the task has not yet run its own
     * {@code QUEUED -> RUNNING} compare-and-set, which is its first action, so
     * winning it is what makes the withdrawal a fact — and only then is the
     * future cancelled, because dropping a task that will never run is both
     * safe and useful. {@code RUNNING -> ABANDONED} means the driver has the
     * call and the host cannot take it back, so the operation is counted as
     * outstanding until it returns and later bounded calls fail fast rather
     * than queue behind it; see {@link #isQuiesced()}. Losing both
     * compare-and-sets means the operation FINISHED as its caller stopped
     * waiting — a photo finish, not a wedge — so nothing is counted, and the
     * timeout message says so instead of guessing.</p>
     *
     * @return how the operation was left; {@link Settlement#ABANDONED} is the
     *         only outcome that touches {@link #ABANDONED_IN_FLIGHT}, i.e. the
     *         only one that can turn {@link #isQuiesced()} {@code false}
     */
    private static Settlement settleAbandonedWait(Future<?> pending,
                                                  AtomicReference<Phase> phase) {
        if (phase.compareAndSet(Phase.QUEUED, Phase.WITHDRAWN)) {
            pending.cancel(false);
            return Settlement.WITHDRAWN;
        }
        if (abandonWhileRunning(phase)) {
            // Deliberately NOT cancelled: cancel(false) would answer true for a
            // callable that is inside the driver without stopping the native
            // call, and that misleading true is the defect this branch fixes.
            return Settlement.ABANDONED;
        }
        return Settlement.FINISHED;
    }

    /**
     * Builds the timeout failure. The disposition it reports is the
     * {@link Settlement} the shared handshake already decided — this method
     * only puts it into words — and the message names the modal control panel
     * when that is what owns the thread, so the failure blames the right
     * thing.
     */
    private static AudioBackendException budgetExhausted(Settlement settlement,
                                                        Duration budget,
                                                        TimeoutException timedOut) {
        String disposition = switch (settlement) {
            case WITHDRAWN -> "it was still queued and has been withdrawn, so the driver"
                    + " never saw it";
            case ABANDONED -> "it is already executing inside the driver and may still"
                    + " complete after this failure — the driver, not the host, is"
                    + " unresponsive. Bounded operations fail fast until it returns";
            case FINISHED -> "it finished on the control thread just as the budget"
                    + " expired, so the driver did see it";
        };
        String panel = controlPanelOpen
                ? " The driver's modal control panel currently owns the asio-control"
                        + " thread, which is the expected cause: the driver is unavailable"
                        + " until the user closes it."
                : "";
        return new AudioBackendException(
                "ASIO driver operation did not complete within " + budget.toMillis()
                        + " ms on the asio-control thread; " + disposition + "." + panel,
                timedOut);
    }

    /**
     * Marks a still-executing operation abandoned.
     *
     * <p>The count is taken BEFORE the transition is published so that no
     * window exists in which the driver is inside an abandoned call and
     * {@link #isQuiesced()} answers {@code true} — the one direction in which
     * being wrong lets {@code AsioBackend} load a second driver over a live
     * one. A lost compare-and-set means the operation finished first, and the
     * provisional count is handed straight back.</p>
     *
     * @return true when this call moved the operation RUNNING to ABANDONED,
     *         i.e. when the driver really is still executing it
     */
    private static boolean abandonWhileRunning(AtomicReference<Phase> phase) {
        ABANDONED_IN_FLIGHT.incrementAndGet();
        if (phase.compareAndSet(Phase.RUNNING, Phase.ABANDONED)) {
            return true;
        }
        ABANDONED_IN_FLIGHT.decrementAndGet();
        return false;
    }

    /**
     * Refuses a bounded operation while an abandoned downcall is executing.
     * Submitting it could only queue behind a call the host has already given
     * up on and burn its own full budget: a failed open issues roughly six
     * teardown downcalls, which at {@link #DEFAULT_BUDGET} each is a minute and
     * a half of lifecycle stall bought for nothing.
     */
    private static AudioBackendException driverStillExecuting() {
        return new AudioBackendException(
                "ASIO operation refused: the asio-control thread is still executing a"
                        + " driver call its caller stopped waiting for. The driver, not"
                        + " the host, is unresponsive, and it may still hold the device."
                        + " Operations resume as soon as that call returns.");
    }

    /**
     * Whether the control thread is free of operations the host has given up
     * on — i.e. whether no ASIO downcall whose caller stopped waiting for it,
     * by budget or by interrupt, is still executing inside the driver.
     *
     * <p>{@code true} does not mean idle. A healthy call within its budget
     * leaves this {@code true}, and so does the modal control panel: a user
     * reading a driver dialog is not a wedge. It means nothing is running that
     * the host has already reported as failed, which is the question
     * {@code AsioBackend} must answer before it loads a driver over the current
     * one, or frees a driver shim it could never re-close.</p>
     */
    static boolean isQuiesced() {
        return ABANDONED_IN_FLIGHT.get() == 0;
    }

    /**
     * Waits up to {@code budget} for {@link #isQuiesced()} to become true.
     *
     * <p>Polls in short slices rather than parking on a condition: what is
     * being waited for is a native call returning, which cannot be signalled
     * any earlier than the operation's own {@code finally} runs, and polling
     * keeps this wait free of every lock the wedged operation might hold.</p>
     *
     * <p>Returns {@code true} immediately on the control thread itself, where
     * a wait could only be a wait for the caller's own operation.</p>
     *
     * <p>An interrupt ends the wait early and returns {@code false} with the
     * interrupt status left set, so a shutdown is never swallowed.</p>
     *
     * @param budget how long to wait. A caller on a background thread may pass
     *               far more than any {@link #DEFAULT_BUDGET}-sized figure:
     *               nothing is waiting behind such a caller, and giving up
     *               early on a driver that would have let go leaks whatever it
     *               was waiting to release.
     * @return true when nothing abandoned is executing any more
     */
    static boolean awaitQuiescence(Duration budget) {
        if (Thread.currentThread() == controlThread) {
            return true;
        }
        long deadlineNanos = System.nanoTime() + budget.toNanos();
        while (!isQuiesced()) {
            if (System.nanoTime() - deadlineNanos >= 0
                    || Thread.currentThread().isInterrupted()) {
                return false;
            }
            LockSupport.parkNanos(QUIESCENCE_POLL_NANOS);
        }
        return true;
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws Throwable;
    }

    private static final class OperationFailure extends Exception {
        OperationFailure(Throwable cause) {
            super(cause);
        }
    }
}
