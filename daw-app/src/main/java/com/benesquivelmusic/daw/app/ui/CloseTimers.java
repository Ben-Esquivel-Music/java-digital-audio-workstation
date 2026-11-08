package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The clock seam of the close protocol and of the doors' waits (story 333;
 * ruling R8 "Put a clock seam on the 10 s threshold"): it runs a task on the
 * JavaFX application thread once a delay has passed, unless the task was
 * cancelled first. The doors' 10 s "Stop waiting" offer and Save As's 10 s
 * {@code Continue} use it, and so do the exit's 500 ms and 10 s surfaces, the
 * detach clock, the engine-shutdown bound and the application-stop
 * fallback's deadlines (story 333). Tests install a manual implementation
 * that fires a task only when the test chooses, so no test sleeps for a
 * threshold.
 */
@FunctionalInterface
interface CloseTimers {

    /**
     * Runs {@code onFx} on the FX thread once {@code delay} has passed,
     * unless the returned handle was cancelled first. Never blocks the
     * caller.
     *
     * @param delay how long to wait
     * @param onFx  what runs on the FX thread then
     * @return the handle that cancels it
     */
    Cancellable after(Duration delay, Runnable onFx);

    /** A pending timer task. */
    @FunctionalInterface
    interface Cancellable {

        /**
         * Keeps the task from running, if it has not run yet. Callable on any
         * thread; on the FX thread, where the task runs, a task cancelled in
         * an FX turn never runs after it. Idempotent.
         */
        void cancel();
    }

    /**
     * The production timers: a delayed executor that posts the task to the
     * FX thread through {@code dispatcher} — the application's one
     * marshalling seam (Control Synchronization book §2.6), unkeyed, because
     * a timer task may show a notification or a dialog — where it runs only
     * if it was not cancelled meanwhile.
     *
     * @param dispatcher the FX dispatcher, or {@code null} for the app-scoped
     *                   default ({@link FxDispatcher#runOnFx(FxDispatcher, Runnable)})
     * @return the timers
     */
    static CloseTimers onFx(FxDispatcher dispatcher) {
        return (delay, task) -> {
            Objects.requireNonNull(delay, "delay must not be null");
            Objects.requireNonNull(task, "task must not be null");
            AtomicBoolean cancelled = new AtomicBoolean();
            CompletableFuture.delayedExecutor(delay.toNanos(), TimeUnit.NANOSECONDS)
                    .execute(() -> FxDispatcher.runOnFx(dispatcher, () -> {
                        if (!cancelled.get()) {
                            task.run();
                        }
                    }));
            return () -> cancelled.set(true);
        };
    }
}
