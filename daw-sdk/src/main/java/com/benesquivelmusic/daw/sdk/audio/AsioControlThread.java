package com.benesquivelmusic.daw.sdk.audio;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <p>The apartment affinity that makes this class necessary also means the
 * exemption cannot be isolated behind its own executor: a second thread would
 * be a second COM apartment, and the driver object lives in this one. So a
 * bounded operation submitted while the panel is open QUEUES behind it and
 * will exhaust its budget. That is the honest outcome — the driver really is
 * unavailable while its own modal panel owns it — and {@code controlPanelOpen}
 * exists so the failure says exactly that instead of blaming a wedge.</p>
 */
final class AsioControlThread {

    /**
     * How long a bounded operation may occupy the control thread before the
     * caller gives up. See the class javadoc for why it is this generous.
     */
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(15);

    private static volatile Thread controlThread;

    /**
     * True while {@link #callUnbounded(Operation)} is executing — i.e. while
     * the driver's modal control panel owns the control thread. Read only to
     * make a timeout message truthful about WHY the thread was busy.
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
     * @throws AudioBackendException if the budget elapsed first
     * @throws Throwable             whatever the operation itself threw
     */
    static <T> T call(Operation<T> operation) throws Throwable {
        return call(operation, DEFAULT_BUDGET);
    }

    /**
     * Runs {@code operation} on the control thread and waits for it
     * INDEFINITELY. Reserved for the modal control panel, whose duration is
     * the user's to choose; every other caller must use
     * {@link #call(Operation)}.
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
     * @param budget the wait budget, or {@code null} to wait indefinitely
     */
    static <T> T call(Operation<T> operation, Duration budget) throws Throwable {
        if (Thread.currentThread() == controlThread) {
            return operation.run();
        }
        Future<T> pending = EXECUTOR.submit(() -> {
            try {
                return operation.run();
            } catch (Throwable failure) {
                throw new OperationFailure(failure);
            }
        });
        try {
            return budget == null
                    ? pending.get()
                    : pending.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timedOut) {
            throw budgetExhausted(pending, budget, timedOut);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof OperationFailure wrapped) {
                throw wrapped.getCause();
            }
            throw cause;
        }
    }

    /**
     * Builds the timeout failure, first trying to WITHDRAW the operation.
     *
     * <p>{@code cancel(false)} is the whole reason this is not a bare throw:
     * an operation still queued is removed and provably never runs, so the
     * driver state is exactly what it was — the common case when the control
     * thread is busy with the modal panel or an earlier slow call. An
     * operation already executing cannot be withdrawn (interrupting a thread
     * parked inside a native downcall does nothing), so the driver may still
     * complete it after this failure returns. Those are genuinely different
     * situations for whoever reads the log, so the message says which one
     * happened rather than guessing.</p>
     */
    private static AudioBackendException budgetExhausted(Future<?> pending, Duration budget,
                                                        TimeoutException timedOut) {
        boolean withdrawn = pending.cancel(false);
        String disposition = withdrawn
                ? "it was still queued and has been withdrawn, so the driver never saw it"
                : "it is already executing inside the driver and may still complete after"
                        + " this failure — the driver, not the host, is unresponsive";
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
