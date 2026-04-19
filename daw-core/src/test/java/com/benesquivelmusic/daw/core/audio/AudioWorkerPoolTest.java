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
        try (AudioWorkerPool pool = new AudioWorkerPool(2)) {
            Thread[] observed = new Thread[1];
            Runnable[] tasks = { () -> observed[0] = Thread.currentThread(),
                                 () -> { /* keeps coordinator busy so worker definitely runs the first */ } };
            // Run many batches so we are statistically certain a worker thread
            // (not the coordinator) picks up the first task at least once.
            Thread caller = Thread.currentThread();
            boolean sawWorker = false;
            for (int i = 0; i < 200 && !sawWorker; i++) {
                pool.invokeAll(tasks, 2);
                if (observed[0] != null && observed[0] != caller) {
                    sawWorker = true;
                    assertThat(observed[0].isDaemon()).isTrue();
                    assertThat(observed[0].getPriority()).isEqualTo(Thread.MAX_PRIORITY);
                    assertThat(observed[0].getName()).startsWith("daw-audio-worker-");
                    assertThat(observed[0].isVirtual()).isFalse();
                }
            }
            assertThat(sawWorker).as("at least one batch ran on a worker thread").isTrue();
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
