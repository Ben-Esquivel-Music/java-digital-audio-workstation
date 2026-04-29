package com.benesquivelmusic.daw.core.concurrent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central abstraction for all non-realtime concurrency in the DAW.
 *
 * <p>Routes {@link DawTask}s either to a virtual-thread-per-task
 * executor (the default for I/O-bound work — JEP 444, final in Java 21)
 * or to a bounded platform-thread pool sized to the CPU count (for
 * short CPU-bound bursts that must not oversubscribe the cores).</p>
 *
 * <h2>Why virtual threads?</h2>
 * <pre>
 *  Old:  fixed pool of N platform threads + bounded queue
 *        + tuning N + worrying about queue overflow / starvation
 *  New:  one virtual thread per task, multiplexed onto a tiny pool
 *        of carrier threads.
 * </pre>
 *
 * <p>Virtual threads are cheap (kilobytes, not megabytes), so the
 * thread-per-task model is viable at scale. Importing 100 audio files
 * concurrently spawns 100 virtual threads — no extra configuration
 * needed.</p>
 *
 * <h2>What about the audio thread?</h2>
 *
 * <p>Realtime audio callbacks <strong>must not</strong> use this
 * runner. Virtual threads can be unmounted from their carrier at
 * arbitrary points which breaks the deadline guarantees the audio
 * engine relies on. Audio uses dedicated platform threads owned by
 * the host (PortAudio / CoreAudio / WASAPI / JACK).</p>
 *
 * <h2>Monitoring</h2>
 *
 * <p>{@link #snapshot()} returns a per-category count of currently
 * running tasks and their names — wired to a debug view to spot
 * thread leaks.</p>
 *
 * @see DawTask
 * @see TaskCategory
 * @see DawScope
 */
public final class DawTaskRunner implements AutoCloseable {

    /** Marker name for tasks running on the virtual-thread-per-task executor. */
    public static final String VIRTUAL_THREAD_NAME_PREFIX = "daw-vt-";
    /** Name prefix for the bounded platform CPU pool. */
    public static final String CPU_THREAD_NAME_PREFIX = "daw-cpu-";

    private final ExecutorService virtualExecutor;
    private final ExecutorService cpuExecutor;
    private final Map<Long, Active> active = new ConcurrentHashMap<>();
    private final AtomicLong taskIdSequence = new AtomicLong();

    /**
     * Constructs a runner whose CPU pool size equals
     * {@code Runtime.getRuntime().availableProcessors()}.
     */
    public DawTaskRunner() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Constructs a runner with the given CPU pool size.
     *
     * @param cpuPoolSize bounded platform-thread pool size for
     *                    {@link TaskCategory#COMPUTE} tasks. Must be
     *                    {@code >= 1}.
     */
    public DawTaskRunner(int cpuPoolSize) {
        if (cpuPoolSize < 1) {
            throw new IllegalArgumentException(
                    "cpuPoolSize must be >= 1: " + cpuPoolSize);
        }
        // JEP 444: virtual threads — one per task. No pool sizing.
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong cpuSeq = new AtomicLong();
        ThreadFactory cpuFactory = r -> {
            Thread t = new Thread(r, CPU_THREAD_NAME_PREFIX + cpuSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.cpuExecutor = Executors.newFixedThreadPool(cpuPoolSize, cpuFactory);
    }

    /**
     * Submits a task and returns a future that completes with its
     * result (or completes exceptionally on failure).
     *
     * <p>The category determines the executor:</p>
     * <ul>
     *   <li>{@link TaskCategory#COMPUTE} → bounded CPU pool</li>
     *   <li>everything else → virtual-thread-per-task executor</li>
     * </ul>
     *
     * @param task the task to run
     * @param <T>  the result type
     * @return a completion stage for the task's result
     */
    public <T> CompletableFuture<T> submit(DawTask<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        ExecutorService exec = executorFor(task.category());
        long id = taskIdSequence.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                String previousName = Thread.currentThread().getName();
                String label = (Thread.currentThread().isVirtual()
                        ? VIRTUAL_THREAD_NAME_PREFIX
                        : CPU_THREAD_NAME_PREFIX) + task.category() + ":" + task.name();
                Active record = new Active(id, task.name(), task.category());
                active.put(id, record);
                try {
                    // Name the thread for JFR / debug snapshot visibility.
                    Thread.currentThread().setName(label);
                    future.complete(task.work().call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    active.remove(id);
                    Thread.currentThread().setName(previousName);
                }
            });
        } catch (RuntimeException e) {
            // Executor rejected the task (e.g., runner already closed).
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Returns the underlying executor for the given category. Useful
     * when interoperating with APIs that already accept an
     * {@link ExecutorService} (e.g.
     * {@link CompletableFuture#supplyAsync(java.util.function.Supplier,
     * java.util.concurrent.Executor)}).
     *
     * <p>Callers should prefer {@link #submit(DawTask)} so the task is
     * registered for monitoring; use this method only when a third
     * party requires a raw executor.</p>
     */
    public ExecutorService executorFor(TaskCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return category == TaskCategory.COMPUTE ? cpuExecutor : virtualExecutor;
    }

    /**
     * Snapshot of currently-running tasks bucketed by category. The
     * returned map is a defensive copy — modifying it does not affect
     * the runner.
     */
    public Snapshot snapshot() {
        Map<TaskCategory, List<String>> bucketed = new EnumMap<>(TaskCategory.class);
        for (TaskCategory c : TaskCategory.values()) {
            bucketed.put(c, new java.util.ArrayList<>());
        }
        for (Active a : active.values()) {
            bucketed.get(a.category).add(a.name);
        }
        Map<TaskCategory, List<String>> immutable = new EnumMap<>(TaskCategory.class);
        for (var e : bucketed.entrySet()) {
            immutable.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return new Snapshot(Collections.unmodifiableMap(immutable));
    }

    /**
     * Total number of tasks currently executing across both executors.
     */
    public int activeCount() {
        return active.size();
    }

    @Override
    public void close() {
        virtualExecutor.shutdown();
        cpuExecutor.shutdown();
        try {
            if (!virtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualExecutor.shutdownNow();
            }
            if (!cpuExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cpuExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualExecutor.shutdownNow();
            cpuExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record Active(long id, String name, TaskCategory category) {}

    /**
     * Read-only view of currently running tasks; used by the debug
     * UI to spot leaks (e.g., a task category with hundreds of
     * never-completing entries).
     */
    public record Snapshot(Map<TaskCategory, List<String>> activeByCategory) {

        public Snapshot {
            Objects.requireNonNull(activeByCategory, "activeByCategory");
            activeByCategory = Map.copyOf(activeByCategory);
        }

        /** Total number of active tasks across all categories. */
        public int total() {
            return activeByCategory.values().stream()
                    .mapToInt(List::size)
                    .sum();
        }

        /** Number of active tasks for a given category. */
        public int countOf(TaskCategory category) {
            List<String> names = activeByCategory.get(category);
            return names == null ? 0 : names.size();
        }
    }
}
