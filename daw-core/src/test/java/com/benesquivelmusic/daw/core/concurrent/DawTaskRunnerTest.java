package com.benesquivelmusic.daw.core.concurrent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the contract of {@link DawTaskRunner}.
 *
 * <ul>
 *   <li>I/O-bound categories run on virtual threads (JEP 444).</li>
 *   <li>{@link TaskCategory#COMPUTE} runs on a bounded platform pool.</li>
 *   <li>100 concurrent {@code IMPORT}-class tasks all complete — the
 *       acceptance criterion called out in the issue.</li>
 *   <li>{@link DawTaskRunner#snapshot()} surfaces active tasks by
 *       category for the debug view.</li>
 * </ul>
 */
class DawTaskRunnerTest {

    @Test
    void importTaskRunsOnVirtualThread() throws Exception {
        try (DawTaskRunner runner = new DawTaskRunner()) {
            CompletableFuture<Boolean> future = runner.submit(new DawTask<>(
                    "import:test.wav", TaskCategory.IMPORT,
                    () -> Thread.currentThread().isVirtual()));
            assertThat(future.get()).isTrue();
        }
    }

    @Test
    void exportTaskRunsOnVirtualThread() throws Exception {
        try (DawTaskRunner runner = new DawTaskRunner()) {
            CompletableFuture<Boolean> future = runner.submit(new DawTask<>(
                    "export:test.wav", TaskCategory.EXPORT,
                    () -> Thread.currentThread().isVirtual()));
            assertThat(future.get()).isTrue();
        }
    }

    @Test
    void computeTaskRunsOnPlatformThread() throws Exception {
        try (DawTaskRunner runner = new DawTaskRunner(2)) {
            CompletableFuture<Boolean> future = runner.submit(new DawTask<>(
                    "fft", TaskCategory.COMPUTE,
                    () -> Thread.currentThread().isVirtual()));
            assertThat(future.get()).isFalse();
        }
    }

    @Test
    void hundredConcurrentImportsAllComplete(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        try (DawTaskRunner runner = new DawTaskRunner()) {
            List<CompletableFuture<Path>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                final int idx = i;
                futures.add(runner.submit(new DawTask<>(
                        "import:file-" + idx, TaskCategory.IMPORT,
                        () -> {
                            Path file = tempDir.resolve("import-" + idx + ".bin");
                            Files.writeString(file, "data-" + idx);
                            return file;
                        })));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get();
            for (int i = 0; i < 100; i++) {
                assertThat(futures.get(i).get()).exists();
            }
        }
    }

    @Test
    void snapshotExposesActiveTasksByCategory() throws Exception {
        try (DawTaskRunner runner = new DawTaskRunner()) {
            java.util.concurrent.CountDownLatch started =
                    new java.util.concurrent.CountDownLatch(3);
            java.util.concurrent.CountDownLatch finish =
                    new java.util.concurrent.CountDownLatch(1);
            CompletableFuture<Void> imp = runner.submit(DawTask.ofRunnable(
                    "import:a", TaskCategory.IMPORT, () -> {
                        started.countDown();
                        try { finish.await(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            CompletableFuture<Void> exp = runner.submit(DawTask.ofRunnable(
                    "export:b", TaskCategory.EXPORT, () -> {
                        started.countDown();
                        try { finish.await(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            CompletableFuture<Void> scan = runner.submit(DawTask.ofRunnable(
                    "scan:c", TaskCategory.SCAN, () -> {
                        started.countDown();
                        try { finish.await(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            started.await();

            DawTaskRunner.Snapshot snap = runner.snapshot();
            assertThat(snap.total()).isEqualTo(3);
            assertThat(snap.countOf(TaskCategory.IMPORT)).isEqualTo(1);
            assertThat(snap.countOf(TaskCategory.EXPORT)).isEqualTo(1);
            assertThat(snap.countOf(TaskCategory.SCAN)).isEqualTo(1);
            assertThat(snap.activeByCategory().get(TaskCategory.IMPORT))
                    .containsExactly("import:a");

            finish.countDown();
            CompletableFuture.allOf(imp, exp, scan).get();
            assertThat(runner.snapshot().total()).isZero();
        }
    }

    @Test
    void exceptionFromTaskCompletesFutureExceptionally() {
        try (DawTaskRunner runner = new DawTaskRunner()) {
            CompletableFuture<String> future = runner.submit(new DawTask<>(
                    "boom", TaskCategory.IMPORT,
                    () -> { throw new IOException("disk full"); }));
            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(runner.activeCount()).isZero();
        }
    }

    @Test
    void taskRequiresNonNullArguments() {
        assertThatThrownBy(() -> new DawTask<>(null, TaskCategory.IMPORT, () -> 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DawTask<>("ok", null, () -> 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DawTask<>("ok", TaskCategory.IMPORT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DawTask<>("  ", TaskCategory.IMPORT, () -> 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executorForReturnsAppropriateExecutor() {
        try (DawTaskRunner runner = new DawTaskRunner()) {
            assertThat(runner.executorFor(TaskCategory.IMPORT))
                    .isSameAs(runner.executorFor(TaskCategory.EXPORT))
                    .isSameAs(runner.executorFor(TaskCategory.AUTOSAVE))
                    .isSameAs(runner.executorFor(TaskCategory.SCAN))
                    .isSameAs(runner.executorFor(TaskCategory.ANALYSIS));
            assertThat(runner.executorFor(TaskCategory.COMPUTE))
                    .isNotSameAs(runner.executorFor(TaskCategory.IMPORT));
        }
    }

    @Test
    void cpuPoolSizeMustBePositive() {
        assertThatThrownBy(() -> new DawTaskRunner(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runnableTaskFactoryProducesRunningTask() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        try (DawTaskRunner runner = new DawTaskRunner()) {
            runner.submit(DawTask.ofRunnable(
                    "tick", TaskCategory.AUTOSAVE, counter::incrementAndGet)).get();
        }
        assertThat(counter.get()).isEqualTo(1);
    }
}
