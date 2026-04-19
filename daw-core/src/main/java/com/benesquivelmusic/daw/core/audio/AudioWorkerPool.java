package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * A fixed-size pool of high-priority platform daemon worker threads used
 * by the real-time audio graph scheduler to dispatch independent branches
 * of the per-block mix graph in parallel.
 *
 * <p>Platform threads (not virtual threads) are used deliberately: real-time
 * audio processing requires predictable scheduling and must not be subject
 * to the carrier-thread multiplexing or pinning hazards of Project Loom's
 * virtual threads. Workers are pinned at {@link Thread#MAX_PRIORITY} so the
 * OS scheduler prefers them over non-realtime work.</p>
 *
 * <p>The pool exposes a single {@link #invokeAll(Runnable[], int)} entry
 * point. The caller (the audio callback thread) publishes a batch of
 * independent tasks, participates as one additional worker, and blocks
 * until every task has completed. Workers pick up tasks from a shared
 * array via an atomic index CAS — there are no locks and no allocations
 * on the hot path.</p>
 *
 * <p>Threads idle between batches by spin-waiting on {@link Thread#onSpinWait()}
 * for a short window and then {@link LockSupport#park()}-ing; the coordinator
 * calls {@link LockSupport#unpark(Thread)} when a new batch is published to
 * avoid unbounded busy-waiting.</p>
 */
public final class AudioWorkerPool implements AutoCloseable {

    /** Number of spin iterations before a worker parks when idle. */
    private static final int SPIN_LIMIT = 1_000;

    private final Thread[] workers;
    private final int size;

    // Batch publication state — updated by the coordinator, observed by workers.
    private volatile Runnable[] currentTasks;
    private volatile int currentTaskCount;
    private final AtomicInteger nextTaskIndex = new AtomicInteger(0);
    private final AtomicInteger remaining = new AtomicInteger(0);
    /** Monotonically incremented once per batch so workers can detect new work. */
    private volatile long batchVersion;

    private volatile boolean shutdown;

    /**
     * Creates a new worker pool with the given number of worker threads.
     *
     * @param size the number of worker threads to spawn (must be positive;
     *             when {@code size == 1} the pool degenerates to a direct
     *             execution model — the coordinator runs every task inline)
     * @throws IllegalArgumentException if {@code size <= 0}
     */
    public AudioWorkerPool(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        this.size = size;
        // One worker count == coordinator only; we still allocate a zero-length
        // worker array so invokeAll can detect the degenerate case.
        int spawn = size - 1;
        this.workers = new Thread[spawn];
        for (int i = 0; i < spawn; i++) {
            final int workerId = i;
            Thread t = Thread.ofPlatform()
                    .daemon(true)
                    .name("daw-audio-worker-" + workerId)
                    .priority(Thread.MAX_PRIORITY)
                    .unstarted(this::workerLoop);
            workers[i] = t;
            t.start();
        }
    }

    /**
     * Returns the configured worker count (including the coordinator).
     *
     * @return the pool size
     */
    public int size() {
        return size;
    }

    /**
     * Publishes {@code count} independent tasks to the pool and blocks until
     * all of them have completed. The coordinator thread participates as one
     * of the workers so the full {@link #size()} parallelism is exploited.
     *
     * <p>This method is real-time safe: no heap allocations occur on the hot
     * path and no locks are acquired.</p>
     *
     * @param tasks an array containing at least {@code count} non-null tasks
     * @param count the number of tasks at the head of {@code tasks} to run
     */
    @RealTimeSafe
    public void invokeAll(Runnable[] tasks, int count) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        if (count < 0 || count > tasks.length) {
            throw new IllegalArgumentException(
                    "count out of range: " + count + " tasks.length=" + tasks.length);
        }
        if (count == 0) {
            return;
        }
        if (size == 1 || workers.length == 0) {
            // Match worker-path semantics by skipping null tasks and isolating
            // per-task failures so one bad task does not abort the batch.
            for (int i = 0; i < count; i++) {
                Runnable task = tasks[i];
                if (task == null) {
                    continue;
                }
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // Intentionally ignored to preserve batch execution semantics.
                }
            }
            return;
        }

        // Publish the batch. Set remaining first so workers that wake see it.
        remaining.set(count);
        nextTaskIndex.set(0);
        currentTasks = tasks;
        currentTaskCount = count;
        batchVersion++;

        // Wake parked workers.
        for (Thread t : workers) {
            LockSupport.unpark(t);
        }

        // Participate as one worker.
        runTasks();

        // Wait for all tasks (including those still in-flight on workers) to complete.
        while (remaining.get() > 0) {
            Thread.onSpinWait();
        }

        // Release references for GC hygiene — the actual buffer array passed in
        // lives for the lifetime of the render pipeline so this is essentially
        // a null of a volatile field (no allocation).
        currentTasks = null;
    }

    /**
     * Initiates an orderly shutdown: worker threads stop after their next wake.
     * Pending batches are drained normally; no new tasks should be submitted
     * after {@link #close()} is called.
     */
    @Override
    public void close() {
        shutdown = true;
        for (Thread t : workers) {
            LockSupport.unpark(t);
        }
        for (Thread t : workers) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Worker internals ─────────────────────────────────────────────────

    private void workerLoop() {
        long lastBatch = 0L;
        while (!shutdown) {
            // Fast path: a new batch is available.
            long v = batchVersion;
            if (v != lastBatch) {
                lastBatch = v;
                runTasks();
                continue;
            }
            // Short spin to avoid park/unpark overhead between tight batches.
            int spin = 0;
            while (batchVersion == lastBatch && !shutdown && spin++ < SPIN_LIMIT) {
                Thread.onSpinWait();
            }
            if (batchVersion == lastBatch && !shutdown) {
                LockSupport.park(this);
            }
        }
    }

    /**
     * Pulls tasks from the shared array until exhausted. Each claimed task
     * decrements the {@link #remaining} counter on completion so the
     * coordinator can detect batch completion.
     */
    @RealTimeSafe
    private void runTasks() {
        final Runnable[] local = currentTasks;
        final int total = currentTaskCount;
        if (local == null || total <= 0) {
            return;
        }
        for (;;) {
            int idx = nextTaskIndex.getAndIncrement();
            if (idx >= total) {
                return;
            }
            Runnable r = local[idx];
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    // Swallow to protect the audio thread; individual tasks
                    // are expected to be failure-tolerant (e.g. DSP inserts
                    // catching their own plugin exceptions). A worker that
                    // dies would starve future batches.
                }
            }
            remaining.decrementAndGet();
        }
    }
}
