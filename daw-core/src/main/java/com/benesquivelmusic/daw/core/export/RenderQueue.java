package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.JobControl;
import com.benesquivelmusic.daw.sdk.export.JobProgress;
import com.benesquivelmusic.daw.sdk.export.RenderJob;
import com.benesquivelmusic.daw.sdk.export.RenderJobRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Offline render queue that batches one or more {@link RenderJob}s and
 * runs them on a bounded-parallelism executor without blocking the UI.
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Construct with a {@link RenderJobRunner} and an optional worker
 *       count (default 1, configurable).</li>
 *   <li>Subscribe to {@link #progressPublisher()} for {@link JobProgress}
 *       events.</li>
 *   <li>Optionally register a per-job completion notification via
 *       {@link #setCompletionNotifier(Consumer)} (e.g., adapter to
 *       {@code NotificationManager}).</li>
 *   <li>{@link #enqueue(RenderJob)} jobs; the workers will dequeue and
 *       run them in FIFO order with support for pause / resume / cancel
 *       per job and reorder across the queue.</li>
 *   <li>{@link #shutdown()} when finished.</li>
 * </ol>
 *
 * <p>This class is thread-safe; mutating operations may be invoked from
 * any thread (typically the JavaFX application thread).</p>
 */
public final class RenderQueue implements AutoCloseable {

    /** Default location of the persisted queue file. */
    public static final Path DEFAULT_PERSISTENCE_PATH =
            Path.of(System.getProperty("user.home"), ".daw", "render-queue.json");

    private final RenderJobRunner runner;
    private final ExecutorService workerPool;
    private final SubmissionPublisher<JobProgress> publisher;
    private final BlockingDeque<RenderJob> pending = new LinkedBlockingDeque<>();
    private final ConcurrentHashMap<String, JobState> states = new ConcurrentHashMap<>();
    /** Submission order — used to keep the UI list in deterministic order. */
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock queueLock = new ReentrantLock();

    private volatile Path persistencePath = DEFAULT_PERSISTENCE_PATH;
    private volatile Consumer<JobOutcome> completionNotifier = outcome -> { };
    private volatile boolean closed;

    /** Construct a queue with a single worker (default). */
    public RenderQueue(RenderJobRunner runner) {
        this(runner, 1);
    }

    /**
     * Construct a queue with the given worker count.
     *
     * @param runner       the runner that performs the actual render
     * @param workerCount  number of concurrent workers (must be &gt;= 1).
     *                     Default is 1 to prevent I/O contention.
     */
    public RenderQueue(RenderJobRunner runner, int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1: " + workerCount);
        }
        this.runner = Objects.requireNonNull(runner, "runner");
        this.publisher = new SubmissionPublisher<>();
        ThreadFactory tf = new RenderQueueThreadFactory();
        this.workerPool = Executors.newFixedThreadPool(workerCount, tf);
        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(this::workerLoop);
        }
    }

    // ---- public API -------------------------------------------------------

    /** @return the publisher of {@link JobProgress} events. */
    public Flow.Publisher<JobProgress> progressPublisher() {
        return publisher;
    }

    /** Override the persistence path (defaults to {@code ~/.daw/render-queue.json}). */
    public void setPersistencePath(Path path) {
        this.persistencePath = Objects.requireNonNull(path, "path");
    }

    /**
     * Register a callback invoked when a job reaches a terminal state
     * (completed / failed / cancelled). Typically wired to the application's
     * notification manager.
     */
    public void setCompletionNotifier(Consumer<JobOutcome> notifier) {
        this.completionNotifier = Objects.requireNonNull(notifier, "notifier");
    }

    /** Enqueue a job for offline rendering. */
    public void enqueue(RenderJob job) {
        Objects.requireNonNull(job, "job");
        if (closed) {
            throw new IllegalStateException("RenderQueue is closed");
        }
        JobState state = new JobState(job, sequence.incrementAndGet());
        if (states.putIfAbsent(job.jobId(), state) != null) {
            throw new IllegalArgumentException("Duplicate jobId: " + job.jobId());
        }
        pending.addLast(job);
        emit(new JobProgress(job.jobId(), JobProgress.Phase.QUEUED, "Queued", 0.0));
        persistQuietly();
    }

    /** Request cancellation of a job (queued or running). */
    public boolean cancel(String jobId) {
        JobState state = states.get(jobId);
        if (state == null) return false;
        state.cancelRequested = true;
        // Wake any paused job
        state.lock.lock();
        try {
            state.resumeCondition.signalAll();
        } finally {
            state.lock.unlock();
        }
        // If still queued, remove it now and emit terminal event
        if (pending.remove(state.job)) {
            finishJob(state, JobProgress.Phase.CANCELLED, "Cancelled", null);
        }
        return true;
    }

    /** Pause a running or queued job. */
    public boolean pause(String jobId) {
        JobState state = states.get(jobId);
        if (state == null) return false;
        state.pauseRequested = true;
        return true;
    }

    /** Resume a previously paused job. */
    public boolean resume(String jobId) {
        JobState state = states.get(jobId);
        if (state == null) return false;
        state.pauseRequested = false;
        state.lock.lock();
        try {
            state.resumeCondition.signalAll();
        } finally {
            state.lock.unlock();
        }
        return true;
    }

    /**
     * Reorder the queued jobs so that {@code jobId} runs immediately
     * before {@code beforeJobId}. If {@code beforeJobId} is {@code null},
     * {@code jobId} is moved to the head of the queue. Only affects jobs
     * that have not yet started running.
     *
     * @return {@code true} if the move succeeded
     */
    public boolean moveBefore(String jobId, String beforeJobId) {
        queueLock.lock();
        try {
            RenderJob target = findInPending(jobId);
            if (target == null) return false;
            pending.remove(target);
            if (beforeJobId == null) {
                pending.addFirst(target);
            } else {
                List<RenderJob> snapshot = new ArrayList<>(pending);
                pending.clear();
                boolean inserted = false;
                for (RenderJob j : snapshot) {
                    if (!inserted && j.jobId().equals(beforeJobId)) {
                        pending.addLast(target);
                        inserted = true;
                    }
                    pending.addLast(j);
                }
                if (!inserted) {
                    pending.addLast(target);
                }
            }
            persistQuietly();
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    /** @return immutable snapshot of all known jobs in submission order. */
    public List<JobSnapshot> snapshot() {
        return states.values().stream()
                .sorted(Comparator.comparingLong(s -> s.sequenceNumber))
                .map(JobState::toSnapshot)
                .toList();
    }

    /**
     * Block until every enqueued job has reached a terminal phase, or
     * the timeout expires.
     */
    public boolean awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            boolean allDone = states.values().stream().allMatch(s -> s.phase.isTerminal());
            if (allDone) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Thread.sleep(Math.min(50, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
        }
    }

    /** Persist the current queue state to disk. */
    public void persist() throws IOException {
        Path path = persistencePath;
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<JobSnapshot> snapshots = snapshot();
        String json = RenderQueuePersistence.toJson(snapshots);
        Files.writeString(path, json);
    }

    /** Load the persisted queue state for resume / retry / clear prompts. */
    public List<JobSnapshot> loadPersisted() throws IOException {
        Path path = persistencePath;
        if (!Files.isRegularFile(path)) return List.of();
        return RenderQueuePersistence.fromJson(Files.readString(path));
    }

    /** Clear the persisted queue file (used for "clear" on restart). */
    public void clearPersisted() throws IOException {
        Files.deleteIfExists(persistencePath);
    }

    @Override
    public void close() {
        shutdown();
    }

    /** Stop accepting new jobs and shut the worker pool down. */
    public void shutdown() {
        closed = true;
        workerPool.shutdownNow();
        try {
            workerPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        publisher.close();
    }

    // ---- worker loop ------------------------------------------------------

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted() && !closed) {
            RenderJob job;
            try {
                job = pending.takeFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            JobState state = states.get(job.jobId());
            if (state == null) continue;
            if (state.cancelRequested) {
                finishJob(state, JobProgress.Phase.CANCELLED, "Cancelled before start", null);
                continue;
            }
            runJob(state);
        }
    }

    private void runJob(JobState state) {
        RenderJob job = state.job;
        state.phase = JobProgress.Phase.RUNNING;
        emit(new JobProgress(job.jobId(), JobProgress.Phase.RUNNING, "Starting", 0.0));
        ControlImpl control = new ControlImpl(state);
        try {
            runner.run(job, control);
            if (state.cancelRequested) {
                cleanup(state);
                finishJob(state, JobProgress.Phase.CANCELLED, "Cancelled", null);
            } else {
                finishJob(state, JobProgress.Phase.COMPLETED, "Completed", null);
            }
        } catch (InterruptedException e) {
            // Cooperative cancellation
            cleanup(state);
            finishJob(state, JobProgress.Phase.CANCELLED, "Cancelled", null);
            Thread.interrupted(); // clear interrupt
        } catch (Exception e) {
            cleanup(state);
            finishJob(state, JobProgress.Phase.FAILED, "Failed: " + e.getMessage(), e);
        }
    }

    private void finishJob(JobState state, JobProgress.Phase phase, String stage, Throwable error) {
        state.phase = phase;
        state.error = error;
        emit(new JobProgress(state.job.jobId(), phase, stage, 1.0));
        try {
            completionNotifier.accept(new JobOutcome(state.job, phase, error));
        } catch (RuntimeException ignored) {
            // Notifier failures must not break the queue.
        }
        persistQuietly();
    }

    private void cleanup(JobState state) {
        for (Path p : state.cleanupPaths) {
            try {
                if (Files.isDirectory(p)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            } catch (IOException ignored) {
                // Best-effort cleanup
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    private void emit(JobProgress progress) {
        JobState state = states.get(progress.jobId());
        if (state != null) {
            state.phase = progress.phase();
            state.lastStage = progress.stage();
            state.lastPercent = progress.percent();
        }
        publisher.submit(progress);
    }

    private void persistQuietly() {
        try {
            persist();
        } catch (IOException ignored) {
            // Persistence is best-effort; failures must not break the queue.
        }
    }

    private RenderJob findInPending(String jobId) {
        for (RenderJob j : pending) {
            if (j.jobId().equals(jobId)) return j;
        }
        return null;
    }

    // ---- inner types ------------------------------------------------------

    /** Outcome delivered to the completion notifier. */
    public record JobOutcome(RenderJob job, JobProgress.Phase phase, Throwable error) { }

    /** Immutable snapshot of one job's state for UI / persistence. */
    public record JobSnapshot(
            String jobId,
            String displayName,
            String jobType,
            Path primaryOutput,
            JobProgress.Phase phase,
            String lastStage,
            double lastPercent,
            long sequenceNumber
    ) { }

    private static final class JobState {
        final RenderJob job;
        final long sequenceNumber;
        final ReentrantLock lock = new ReentrantLock();
        final Condition resumeCondition = lock.newCondition();
        final List<Path> cleanupPaths = new ArrayList<>();
        volatile boolean cancelRequested;
        volatile boolean pauseRequested;
        volatile JobProgress.Phase phase = JobProgress.Phase.QUEUED;
        volatile String lastStage = "Queued";
        volatile double lastPercent = 0.0;
        volatile Throwable error;

        JobState(RenderJob job, long seq) {
            this.job = job;
            this.sequenceNumber = seq;
        }

        JobSnapshot toSnapshot() {
            return new JobSnapshot(
                    job.jobId(),
                    job.displayName(),
                    job.getClass().getSimpleName(),
                    job.primaryOutput(),
                    phase,
                    lastStage,
                    lastPercent,
                    sequenceNumber);
        }
    }

    private final class ControlImpl implements JobControl {
        final JobState state;

        ControlImpl(JobState state) {
            this.state = state;
        }

        @Override
        public boolean isCancelled() {
            return state.cancelRequested;
        }

        @Override
        public void checkpoint() throws InterruptedException {
            if (state.cancelRequested) {
                throw new InterruptedException("Job cancelled");
            }
            if (state.pauseRequested) {
                emit(new JobProgress(state.job.jobId(), JobProgress.Phase.PAUSED,
                        "Paused", state.lastPercent));
                state.lock.lock();
                try {
                    while (state.pauseRequested && !state.cancelRequested) {
                        state.resumeCondition.await();
                    }
                } finally {
                    state.lock.unlock();
                }
                if (state.cancelRequested) {
                    throw new InterruptedException("Job cancelled");
                }
                emit(new JobProgress(state.job.jobId(), JobProgress.Phase.RUNNING,
                        "Resumed", state.lastPercent));
            }
        }

        @Override
        public void publishProgress(String stage, double percent) throws InterruptedException {
            checkpoint();
            emit(new JobProgress(state.job.jobId(), JobProgress.Phase.RUNNING, stage, percent));
        }

        @Override
        public void registerCleanupPath(Path path) {
            Objects.requireNonNull(path, "path");
            synchronized (state.cleanupPaths) {
                state.cleanupPaths.add(path);
            }
        }
    }

    private static final class RenderQueueThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "render-queue-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Convenience: subscribe a simple {@link Consumer} to the progress
     * publisher. Each delivered {@link JobProgress} is passed to the
     * consumer until the queue is shut down.
     */
    public void subscribe(Consumer<JobProgress> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        publisher.subscribe(new Flow.Subscriber<JobProgress>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(JobProgress item) { consumer.accept(item); }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });
    }
}
