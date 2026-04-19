package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AudioWorkerPool}. Exercises the basic batch dispatch,
 * the single-worker degenerate path, task exceptions, and shutdown lifecycle.
 */
class AudioWorkerPoolTest {

    @Test
    void invokeAllRunsEveryTaskExactlyOnce() {
        try (AudioWorkerPool pool = new AudioWorkerPool(4)) {
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
        try (AudioWorkerPool pool = new AudioWorkerPool(3)) {
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
        try (AudioWorkerPool pool = new AudioWorkerPool(1)) {
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
        try (AudioWorkerPool pool = new AudioWorkerPool(4)) {
            pool.invokeAll(new Runnable[0], 0);
            // Should return immediately; no tasks, no errors.
        }
    }

    @Test
    void invokeAllValidatesCount() {
        try (AudioWorkerPool pool = new AudioWorkerPool(2)) {
            assertThatThrownBy(() -> pool.invokeAll(new Runnable[2], 3))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pool.invokeAll(new Runnable[2], -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void constructorValidatesSize() {
        assertThatThrownBy(() -> new AudioWorkerPool(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioWorkerPool(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerThreadsArePlatformDaemonMaxPriority() throws Exception {
        try (AudioWorkerPool pool = new AudioWorkerPool(3)) {
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
    void taskExceptionsAreSwallowedAndDoNotStarveFollowingBatches() {
        try (AudioWorkerPool pool = new AudioWorkerPool(3)) {
            AtomicInteger counter = new AtomicInteger();
            Runnable throwing = () -> { throw new RuntimeException("boom"); };
            Runnable counting = counter::incrementAndGet;
            Runnable[] tasks = { throwing, counting, throwing, counting, counting };

            pool.invokeAll(tasks, tasks.length);
            assertThat(counter.get()).isEqualTo(3);

            // Subsequent batches still work.
            counter.set(0);
            Runnable[] more = new Runnable[4];
            for (int i = 0; i < more.length; i++) {
                more[i] = counting;
            }
            pool.invokeAll(more, more.length);
            assertThat(counter.get()).isEqualTo(4);
        }
    }
}
