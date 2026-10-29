package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * A wait on the calling thread for a completion stage, bounded by a deadline
 * (story 333, design §1.7 step 9): the application-stop fallback's defensive
 * branch, which runs where no nested event loop can, waits this way on its own
 * non-daemon {@code daw-exit-fallback} thread — the JVM waits for that thread,
 * at most this long, before it ends (ruling R4: "a non-daemon thread with an
 * explicit bound"). The close protocol itself never blocks: every one of its
 * waits on the FX thread is a completion stage or a nested event loop
 * ({@code ExitCoordinator}). Never on the JavaFX application thread.
 */
final class BoundedWait {

    private BoundedWait() {
    }

    /**
     * Waits until {@code stage} completed — normally or not — or the deadline
     * passed, whichever is first. An interrupt ends the wait, the thread's
     * interrupt status kept. Never on the FX thread.
     *
     * @param stage         what to wait for
     * @param deadlineNanos the deadline, on {@code nanoClock}
     * @param nanoClock     the monotonic clock the deadline was taken on
     * @return whether {@code stage} completed before the deadline
     */
    static boolean until(CompletionStage<?> stage, long deadlineNanos, LongSupplier nanoClock) {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        long remaining = Math.max(0L, deadlineNanos - nanoClock.getAsLong());
        try {
            stage.toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException | CancellationException failed) {
            return true;
        } catch (TimeoutException late) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
