package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AudioWorkerPool}. Exercises the basic batch dispatch,
 * the single-worker degenerate path, task exceptions, and shutdown lifecycle.
 *
 * <p>Story 337, ruling E27: a task failure is counted into the running
 * thread's lane of the injected health record. The batch is invoked from a
 * {@link RenderThread} holding a render-drive lane, as the engine's render
 * drive always is in production.</p>
 */
class AudioWorkerPoolTest {

    /** Guard budget for the coordinator thread's join — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    @Test
    void invokeAllRunsEveryTaskExactlyOnce() {
        try (AudioWorkerPool pool = new AudioWorkerPool(4, new AudioHealthRecord())) {
            AtomicInteger counter = new AtomicInteger();
            Runnable[] tasks = new Runnable[16];
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = counter::incrementAndGet;
            }

            pool.invokeAll(tasks, tasks.length);

            assertThat(counter.get()).isEqualTo(16);
        }
    }

    @Test
    void invokeAllIsReusableAcrossMultipleBatches() {
        try (AudioWorkerPool pool = new AudioWorkerPool(3, new AudioHealthRecord())) {
            AtomicInteger counter = new AtomicInteger();
            Runnable[] tasks = new Runnable[8];
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = counter::incrementAndGet;
            }

            for (int batch = 0; batch < 10; batch++) {
                pool.invokeAll(tasks, tasks.length);
            }

            assertThat(counter.get()).isEqualTo(80);
        }
    }

    @Test
    void singleWorkerPoolRunsTasksInlineOnCaller() {
        try (AudioWorkerPool pool = new AudioWorkerPool(1, new AudioHealthRecord())) {
            assertThat(pool.size()).isEqualTo(1);

            Thread caller = Thread.currentThread();
            Thread[] ran = new Thread[3];
            Runnable[] tasks = new Runnable[3];
            for (int i = 0; i < tasks.length; i++) {
                final int idx = i;
                tasks[i] = () -> ran[idx] = Thread.currentThread();
            }

            pool.invokeAll(tasks, 3);

            for (Thread t : ran) {
                assertThat(t).isSameAs(caller);
            }
        }
    }

    @Test
    void invokeAllWithZeroTasksIsANoop() {
        try (AudioWorkerPool pool = new AudioWorkerPool(4, new AudioHealthRecord())) {
            pool.invokeAll(new Runnable[0], 0);
            // Should return immediately; no tasks, no errors.
        }
    }

    @Test
    void invokeAllValidatesCount() {
        try (AudioWorkerPool pool = new AudioWorkerPool(2, new AudioHealthRecord())) {
            assertThatThrownBy(() -> pool.invokeAll(new Runnable[2], 3))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pool.invokeAll(new Runnable[2], -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void constructorValidatesSize() {
        assertThatThrownBy(() -> new AudioWorkerPool(0, new AudioHealthRecord()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioWorkerPool(-1, new AudioHealthRecord()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerThreadsArePlatformDaemonMaxPriority() throws Exception {
        try (AudioWorkerPool pool = new AudioWorkerPool(3, new AudioHealthRecord())) {
            Thread caller = Thread.currentThread();
            // Use a large batch of tasks that each take measurable time so the
            // worker threads are guaranteed to claim some of them — the
            // coordinator cannot race ahead and drain the queue before workers
            // wake up.
            final java.util.concurrent.ConcurrentHashMap<Thread, Boolean> seen =
                    new java.util.concurrent.ConcurrentHashMap<>();
            final int taskCount = 64;
            Runnable[] tasks = new Runnable[taskCount];
            for (int i = 0; i < taskCount; i++) {
                tasks[i] = () -> {
                    seen.putIfAbsent(Thread.currentThread(), Boolean.TRUE);
                    // Busy-wait briefly so multiple workers get a chance to
                    // claim tasks from the shared array.
                    long end = System.nanoTime() + 100_000L; // 100µs
                    while (System.nanoTime() < end) {
                        Thread.onSpinWait();
                    }
                };
            }

            Thread workerThread = null;
            // Retry a few batches to tolerate the rare case where the
            // coordinator itself manages to drain every task.
            for (int attempt = 0; attempt < 20 && workerThread == null; attempt++) {
                seen.clear();
                pool.invokeAll(tasks, taskCount);
                for (Thread t : seen.keySet()) {
                    if (t != caller) {
                        workerThread = t;
                        break;
                    }
                }
            }

            assertThat(workerThread)
                    .as("at least one task must execute on a worker thread")
                    .isNotNull();
            assertThat(workerThread.isDaemon()).isTrue();
            assertThat(workerThread.getPriority()).isEqualTo(Thread.MAX_PRIORITY);
            assertThat(workerThread.getName()).startsWith("daw-audio-worker-");
            assertThat(workerThread.isVirtual()).isFalse();
        }
    }

    @Test
    void taskExceptionsAreSwallowedAndDoNotStarveFollowingBatches() throws Exception {
        AudioHealthRecord record = new AudioHealthRecord();
        try (AudioWorkerPool pool = new AudioWorkerPool(3, record)) {
            AtomicInteger counter = new AtomicInteger();
            Runnable throwing = () -> { throw new RuntimeException("boom"); };
            Runnable counting = counter::incrementAndGet;
            Runnable[] tasks = { throwing, counting, throwing, counting, counting };

            onCoordinator(record, () -> pool.invokeAll(tasks, tasks.length));
            assertThat(counter.get()).isEqualTo(3);
            assertThat(record.total(AudioFailureCategory.WORKER))
                    .as("each swallowed task failure is counted (story 337, ruling E27)")
                    .isEqualTo(2);

            // Subsequent batches still work.
            counter.set(0);
            Runnable[] more = new Runnable[4];
            for (int i = 0; i < more.length; i++) {
                more[i] = counting;
            }
            onCoordinator(record, () -> pool.invokeAll(more, more.length));
            assertThat(counter.get()).isEqualTo(4);
        }
    }

    @Test
    void aTaskThatThrowsEveryBatchBumpsTheWorkerCounterEveryBatch() throws Exception {
        AudioHealthRecord record = new AudioHealthRecord();
        try (AudioWorkerPool pool = new AudioWorkerPool(3, record)) {
            Runnable quiet = () -> { };
            Runnable throwing = () -> { throw new IllegalStateException("insert failed"); };
            Runnable[] tasks = { quiet, quiet, throwing, quiet };
            List<AudioFailureSample> samples = new ArrayList<>();

            onCoordinator(record, () -> {
                for (int batch = 0; batch < 10; batch++) {
                    pool.invokeAll(tasks, tasks.length);
                }
            });
            record.drainSamples(samples::add);

            assertThat(record.total(AudioFailureCategory.WORKER)).isEqualTo(10);
            assertThat(samples)
                    .as("every failure sampled (10 of 16 slots per lane at most)")
                    .hasSize(10)
                    .allSatisfy(sample -> {
                        assertThat(sample.category()).isEqualTo(AudioFailureCategory.WORKER);
                        assertThat(sample.detail()).as("the task index").isEqualTo(2);
                        assertThat(sample.failure()).get().isInstanceOf(IllegalStateException.class);
                    });
        }
    }

    @Test
    void theInlinePoolCountsOnTheCoordinatorsLane() throws Exception {
        AudioHealthRecord record = new AudioHealthRecord();
        try (AudioWorkerPool pool = new AudioWorkerPool(1, record)) {
            Runnable throwing = () -> { throw new IllegalArgumentException("inline failure"); };
            Runnable[] tasks = { () -> { }, throwing };
            List<AudioFailureSample> samples = new ArrayList<>();

            onCoordinator(record, () -> pool.invokeAll(tasks, tasks.length));
            record.drainSamples(samples::add);

            assertThat(record.total(AudioFailureCategory.WORKER)).isEqualTo(1);
            assertThat(samples).singleElement().satisfies(sample -> {
                assertThat(sample.lane()).isEqualTo(LaneRole.RENDER_DRIVE);
                assertThat(sample.detail()).isEqualTo(1);
            });
        }
    }

    @Test
    void aTaskFailureOffARenderThreadIsStillIsolatedButNotRecorded() {
        AudioHealthRecord record = new AudioHealthRecord();
        try (AudioWorkerPool pool = new AudioWorkerPool(1, record)) {
            AtomicInteger counter = new AtomicInteger();
            Runnable[] tasks = { () -> { throw new IllegalStateException("off a render thread"); },
                    counter::incrementAndGet };

            pool.invokeAll(tasks, tasks.length);

            assertThat(counter.get()).isEqualTo(1);
            assertThat(record.total(AudioFailureCategory.WORKER))
                    .as("a thread with no lane records nothing (story 337, ruling E20)")
                    .isZero();
        }
    }

    @Test
    void aFailingTaskStillReleasesTheBatch() throws Exception {
        AudioHealthRecord record = new AudioHealthRecord();
        try (AudioWorkerPool pool = new AudioWorkerPool(3, record)) {
            Runnable[] tasks = new Runnable[24];
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = () -> { throw new StackOverflowError("task error"); };
            }

            onCoordinator(record, () -> pool.invokeAll(tasks, tasks.length));

            assertThat(record.total(AudioFailureCategory.WORKER))
                    .as("invokeAll returned, so every claimed task released the batch")
                    .isEqualTo(24);
        }
    }

    @Test
    void closingThePoolRetiresWorkerLanesAndKeepsTheirCounts() throws Exception {
        AudioHealthRecord record = new AudioHealthRecord();
        Set<Thread> failedOn = ConcurrentHashMap.newKeySet();
        AtomicReference<Thread> coordinator = new AtomicReference<>();
        AtomicInteger batches = new AtomicInteger();
        long beforeClose;
        AudioWorkerPool pool = new AudioWorkerPool(3, record);
        try {
            Runnable[] tasks = new Runnable[64];
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = () -> {
                    failedOn.add(Thread.currentThread());
                    long end = System.nanoTime() + 50_000L;
                    while (System.nanoTime() < end) {
                        Thread.onSpinWait();
                    }
                    throw new IllegalStateException("worker failure");
                };
            }
            onCoordinator(record, () -> {
                coordinator.set(Thread.currentThread());
                for (int attempt = 0; attempt < 20; attempt++) {
                    pool.invokeAll(tasks, tasks.length);
                    batches.incrementAndGet();
                    if (failedOn.stream().anyMatch(t -> t != Thread.currentThread())) {
                        return;
                    }
                }
            });
            assertThat(failedOn).as("a worker thread ran a failing task")
                    .anyMatch(thread -> thread != coordinator.get());
            beforeClose = record.total(AudioFailureCategory.WORKER);
            assertThat(beforeClose).isEqualTo(64L * batches.get());
        } finally {
            pool.close();
        }
        List<AudioFailureSample> samples = new ArrayList<>();
        record.drainSamples(samples::add);

        assertThat(record.total(AudioFailureCategory.WORKER))
                .as("the retired worker lanes' counts are folded into the record")
                .isEqualTo(beforeClose);
        assertThat(samples)
                .as("retired worker lanes are no longer drained; their slots were cleared")
                .noneMatch(sample -> sample.lane() == LaneRole.WORKER);
    }

    /**
     * Runs {@code body} on a {@link RenderThread} holding a fresh render-drive
     * lane of {@code record} — the coordinator's thread in production — and
     * rethrows whatever it threw.
     */
    private static void onCoordinator(AudioHealthRecord record, Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RenderThread thread = new RenderThread(record.claimLane(LaneRole.RENDER_DRIVE), () -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-coordinator");
        thread.start();
        thread.join(GUARD_BUDGET_MILLIS);
        assertThat(thread.isAlive()).as("the coordinator finished within the guard budget").isFalse();
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        if (failure.get() instanceof Error error) {
            throw error;
        }
    }
}
