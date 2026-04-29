package com.benesquivelmusic.daw.core.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Structured concurrency helper for fan-out / fan-in patterns where
 * a parent task forks several children that must all succeed
 * (otherwise the whole scope is cancelled).
 *
 * <p>This is a stable pre-{@link java.util.concurrent.Flow Flow}
 * implementation of the "shutdown-on-failure" semantics specified by
 * <a href="https://openjdk.org/jeps/505">JEP 505 — Structured
 * Concurrency</a>. JEP 505 is in <em>Sixth Preview</em> in Java 26;
 * to keep the build free of {@code --enable-preview} we provide the
 * same guarantees on top of {@link Thread#ofVirtual()}:</p>
 *
 * <ul>
 *   <li>Each {@link #fork(Callable)} starts a virtual thread (one per
 *       task — JEP 444).</li>
 *   <li>{@link #joinAll()} blocks until all forks complete, the first
 *       failure propagates, and remaining forks are
 *       {@linkplain Thread#interrupt() interrupted}.</li>
 *   <li>Closing the scope (try-with-resources) cancels any still-
 *       running forks deterministically — no thread leaks.</li>
 * </ul>
 *
 * <p>Once {@code StructuredTaskScope} ships as final, this class can
 * be reimplemented as a thin wrapper without changing call sites.</p>
 *
 * <h2>Example — bundle export (story 181)</h2>
 *
 * <pre>{@code
 * try (var scope = DawScope.openShutdownOnFailure("bundle-export")) {
 *     var wav  = scope.fork(() -> wavExporter.export(project));
 *     var mp3  = scope.fork(() -> mp3Exporter.export(project));
 *     var pdf  = scope.fork(() -> pdfWriter.write(project));
 *     scope.joinAll();   // throws if any sub-task fails
 *     return new Bundle(wav.resultNow(), mp3.resultNow(), pdf.resultNow());
 * }
 * }</pre>
 */
public final class DawScope implements AutoCloseable {

    /**
     * Handle to a forked sub-task. Mirrors the surface of
     * {@code StructuredTaskScope.Subtask} so migration to JEP 505 is
     * a simple rename.
     */
    public static final class Subtask<T> {
        private final String name;
        private final Future<T> future;

        Subtask(String name, Future<T> future) {
            this.name = name;
            this.future = future;
        }

        /** Task name (for debugging / monitoring). */
        public String name() {
            return name;
        }

        /**
         * Returns the result. Must only be called after a successful
         * {@link DawScope#joinAll()}.
         */
        public T resultNow() {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted reading subtask result", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(
                        "resultNow() called on failed subtask '" + name + "'", e.getCause());
            } catch (CancellationException e) {
                throw new IllegalStateException(
                        "resultNow() called on cancelled subtask '" + name + "'", e);
            }
        }

        /** {@code true} if the sub-task completed (successfully or not). */
        public boolean isDone() {
            return future.isDone();
        }

        /** {@code true} if the sub-task was cancelled. */
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }

    private final String name;
    private final List<Subtask<?>> subtasks = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch firstFailure = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean closed;

    private DawScope(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Opens a new shutdown-on-failure scope. The first child failure
     * causes {@link #joinAll()} to throw and all other children to be
     * interrupted.
     *
     * @param name short identifier for this scope, used as a thread
     *             name prefix so virtual threads show up nicely in
     *             JFR / debug snapshots.
     */
    public static DawScope openShutdownOnFailure(String name) {
        return new DawScope(name);
    }

    /**
     * Forks {@code work} on a new virtual thread.
     *
     * @return a handle whose {@link Subtask#resultNow()} is valid
     *         after a successful {@link #joinAll()}
     */
    public synchronized <T> Subtask<T> fork(Callable<T> work) {
        return fork("subtask-" + (subtasks.size() + 1), work);
    }

    /**
     * Forks a named sub-task on a new virtual thread.
     */
    public synchronized <T> Subtask<T> fork(String subtaskName, Callable<T> work) {
        if (closed) {
            throw new IllegalStateException("Scope is closed");
        }
        Objects.requireNonNull(subtaskName, "subtaskName");
        Objects.requireNonNull(work, "work");
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        String threadName = name + "-" + subtaskName;
        Thread vt = Thread.ofVirtual().name(threadName).unstarted(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
                if (failure.compareAndSet(null, t)) {
                    firstFailure.countDown();
                    interruptOthers();
                }
            }
        });
        Subtask<T> subtask = new Subtask<>(subtaskName, future);
        subtasks.add(subtask);
        threads.add(vt);
        vt.start();
        return subtask;
    }

    private synchronized void interruptOthers() {
        for (Thread t : threads) {
            if (t.isAlive()) {
                t.interrupt();
            }
        }
    }

    /**
     * Waits for all forked sub-tasks to complete. If any sub-task
     * threw, this method throws an {@link ExecutionException} wrapping
     * the first failure once every other thread has been joined.
     */
    public void joinAll() throws InterruptedException, ExecutionException {
        joinAll(0L, TimeUnit.MILLISECONDS);
    }

    /**
     * Same as {@link #joinAll()} with a timeout. A timeout of zero
     * means "wait indefinitely".
     */
    public void joinAll(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(unit, "unit");
        long deadlineNanos = timeout > 0 ? System.nanoTime() + unit.toNanos(timeout) : 0L;
        List<Thread> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(threads);
        }
        for (Thread t : snapshot) {
            if (deadlineNanos == 0L) {
                t.join();
            } else {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    interruptOthers();
                    throw new ExecutionException(new TimeoutException(
                            "DawScope '" + name + "' timed out"));
                }
                t.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1L);
                if (t.isAlive()) {
                    interruptOthers();
                    throw new ExecutionException(new TimeoutException(
                            "DawScope '" + name + "' timed out"));
                }
            }
        }
        Throwable f = failure.get();
        if (f != null) {
            throw new ExecutionException(
                    "Sub-task in scope '" + name + "' failed", f);
        }
    }

    /**
     * Cancels every still-running sub-task. Idempotent — safe to call
     * from {@code finally} blocks, and called automatically by
     * {@link #close()}.
     */
    public synchronized void cancel() {
        if (failure.compareAndSet(null, new CancellationException(
                "Scope '" + name + "' cancelled"))) {
            firstFailure.countDown();
        }
        interruptOthers();
    }

    /**
     * Number of forked sub-tasks (including completed ones).
     */
    public synchronized int forkedCount() {
        return subtasks.size();
    }

    /**
     * Closes the scope, cancelling and joining any remaining
     * sub-tasks. Always safe to call; running threads receive an
     * interrupt and are joined before this method returns.
     */
    @Override
    public void close() {
        List<Thread> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cancel();
            snapshot = List.copyOf(threads);
        }
        for (Thread t : snapshot) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
