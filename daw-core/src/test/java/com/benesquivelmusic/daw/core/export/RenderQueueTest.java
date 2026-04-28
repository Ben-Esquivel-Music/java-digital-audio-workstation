package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.JobProgress;
import com.benesquivelmusic.daw.sdk.export.RenderJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral tests for {@link RenderQueue}: 3-job batch completion,
 * cancel mid-job leaves a clean state, and pause/resume preserves output.
 */
class RenderQueueTest {

    private static AudioExportConfig defaultConfig() {
        return new AudioExportConfig(
                AudioExportFormat.WAV, 44100, 24, DitherType.NONE);
    }

    private static RenderJob makeMaster(Path output, String name) {
        return RenderJob.StereoMasterJob.of(name, output, defaultConfig());
    }

    @Test
    void threeJobQueueCompletesAllJobs(@TempDir Path tmp) throws Exception {
        // Runner: write a tiny file in 5 progress steps.
        var runner = new SteppedFileRunner(5, /*pauseAtStep*/ -1, /*cancelAtStep*/ -1);
        try (var queue = new RenderQueue(runner)) {
            queue.setPersistencePath(tmp.resolve("queue.json"));
            List<RenderJob> jobs = List.of(
                    makeMaster(tmp.resolve("a.wav"), "Song A"),
                    makeMaster(tmp.resolve("b.wav"), "Song B"),
                    makeMaster(tmp.resolve("c.wav"), "Song C"));
            jobs.forEach(queue::enqueue);

            assertThat(queue.awaitQuiescence(10, TimeUnit.SECONDS)).isTrue();

            for (RenderJob j : jobs) {
                assertThat(Files.exists(j.primaryOutput())).as(j.displayName()).isTrue();
            }
            assertThat(queue.snapshot())
                    .extracting(RenderQueue.JobSnapshot::phase)
                    .containsOnly(JobProgress.Phase.COMPLETED);
        }
    }

    @Test
    void cancelMidJobLeavesCleanState(@TempDir Path tmp) throws Exception {
        // Job will be cancelled at step 2 of 10 — the runner registers its
        // partial output for cleanup, so the file must not exist after cancel.
        var runner = new SteppedFileRunner(10, /*pauseAtStep*/ -1, /*cancelAtStep*/ 2);
        try (var queue = new RenderQueue(runner)) {
            queue.setPersistencePath(tmp.resolve("queue.json"));
            // Capture the running job id, then cancel it.
            CountDownLatch running = new CountDownLatch(1);
            AtomicReference<String> runningId = new AtomicReference<>();
            queue.subscribe(p -> {
                if (p.phase() == JobProgress.Phase.RUNNING && runningId.get() == null) {
                    runningId.set(p.jobId());
                    running.countDown();
                }
            });

            RenderJob job = makeMaster(tmp.resolve("doomed.wav"), "Doomed");
            queue.enqueue(job);
            runner.cancelHook = id -> queue.cancel(id);

            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.awaitQuiescence(5, TimeUnit.SECONDS)).isTrue();

            assertThat(Files.exists(job.primaryOutput()))
                    .as("Partial output must be cleaned up after cancel")
                    .isFalse();
            assertThat(queue.snapshot().get(0).phase()).isEqualTo(JobProgress.Phase.CANCELLED);
        }
    }

    @Test
    void pauseResumeDoesNotCorruptOutput(@TempDir Path tmp) throws Exception {
        // Pause at step 3, then resume — final output should match what we
        // would have produced without pausing.
        var runner = new SteppedFileRunner(8, /*pauseAtStep*/ 3, /*cancelAtStep*/ -1);
        try (var queue = new RenderQueue(runner)) {
            queue.setPersistencePath(tmp.resolve("queue.json"));

            CountDownLatch paused = new CountDownLatch(1);
            AtomicReference<String> pausedId = new AtomicReference<>();
            queue.subscribe(p -> {
                if (p.phase() == JobProgress.Phase.PAUSED && pausedId.get() == null) {
                    pausedId.set(p.jobId());
                    paused.countDown();
                }
            });

            RenderJob job = makeMaster(tmp.resolve("paused.wav"), "Paused");
            queue.enqueue(job);
            // Runner will request a pause via the queue when it hits step 3.
            runner.pauseHook = id -> queue.pause(id);

            assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
            // While paused, the file must not yet be complete.
            // Now resume.
            queue.resume(pausedId.get());

            assertThat(queue.awaitQuiescence(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.snapshot().get(0).phase()).isEqualTo(JobProgress.Phase.COMPLETED);

            // Output must match the canonical "no pause" content.
            byte[] expected = SteppedFileRunner.expectedContent(8);
            assertThat(Files.readAllBytes(job.primaryOutput())).isEqualTo(expected);
        }
    }

    @Test
    void persistenceRoundTrip(@TempDir Path tmp) throws Exception {
        var runner = new SteppedFileRunner(2, -1, -1);
        Path persistFile = tmp.resolve("queue.json");
        try (var queue = new RenderQueue(runner)) {
            queue.setPersistencePath(persistFile);
            queue.enqueue(makeMaster(tmp.resolve("a.wav"), "A"));
            queue.enqueue(makeMaster(tmp.resolve("b.wav"), "B"));
            queue.awaitQuiescence(5, TimeUnit.SECONDS);
            queue.persist();
        }
        assertThat(Files.exists(persistFile)).isTrue();
        // Load via a fresh queue
        try (var queue = new RenderQueue(new SteppedFileRunner(1, -1, -1))) {
            queue.setPersistencePath(persistFile);
            List<RenderQueue.JobSnapshot> persisted = queue.loadPersisted();
            assertThat(persisted).hasSize(2);
            assertThat(persisted)
                    .extracting(RenderQueue.JobSnapshot::displayName)
                    .containsExactly("A", "B");
            queue.clearPersisted();
            assertThat(Files.exists(persistFile)).isFalse();
        }
    }

    @Test
    void reorderMoveBefore(@TempDir Path tmp) throws Exception {
        // Use a runner that blocks indefinitely so jobs stay queued.
        var blocker = new BlockingRunner();
        try (var queue = new RenderQueue(blocker)) {
            queue.setPersistencePath(tmp.resolve("queue.json"));
            RenderJob a = makeMaster(tmp.resolve("a.wav"), "A");
            RenderJob b = makeMaster(tmp.resolve("b.wav"), "B");
            RenderJob c = makeMaster(tmp.resolve("c.wav"), "C");
            queue.enqueue(a);
            queue.enqueue(b);
            queue.enqueue(c);

            // Worker has picked up A and is blocked. Reorder C before B.
            // Wait for A to be running.
            blocker.runningStarted.await(3, TimeUnit.SECONDS);
            boolean moved = queue.moveBefore(c.jobId(), b.jobId());
            assertThat(moved).isTrue();

            // Cancel A so the worker can move to next job — verify next is C, not B.
            queue.cancel(a.jobId());
            blocker.releaseAll();
            // Wait until B and C complete.
            queue.awaitQuiescence(5, TimeUnit.SECONDS);
            // Order of execution captured by blocker
            assertThat(blocker.executionOrder).containsExactly(a.jobId(), c.jobId(), b.jobId());
        }
    }

    // --- helper runners ----------------------------------------------------

    /**
     * Test runner that writes one byte per step into the job's primary
     * output. Cooperates with cancel and pause via the {@link com.benesquivelmusic.daw.sdk.export.JobControl}.
     */
    static final class SteppedFileRunner implements com.benesquivelmusic.daw.sdk.export.RenderJobRunner {
        final int steps;
        final int pauseAtStep;
        final int cancelAtStep;
        volatile java.util.function.Consumer<String> pauseHook = id -> { };
        volatile java.util.function.Consumer<String> cancelHook = id -> { };

        SteppedFileRunner(int steps, int pauseAtStep, int cancelAtStep) {
            this.steps = steps;
            this.pauseAtStep = pauseAtStep;
            this.cancelAtStep = cancelAtStep;
        }

        static byte[] expectedContent(int steps) {
            byte[] out = new byte[steps];
            for (int i = 0; i < steps; i++) out[i] = (byte) i;
            return out;
        }

        @Override
        public void run(RenderJob job, com.benesquivelmusic.daw.sdk.export.JobControl control) throws Exception {
            Path out = job.primaryOutput();
            // Register the file BEFORE creating it so cancel cleans it up.
            control.registerCleanupPath(out);
            Files.createDirectories(out.getParent());
            try (var os = Files.newOutputStream(out)) {
                for (int i = 0; i < steps; i++) {
                    if (i == pauseAtStep) {
                        pauseHook.accept(job.jobId());
                    }
                    if (i == cancelAtStep) {
                        cancelHook.accept(job.jobId());
                    }
                    control.publishProgress("step " + i, (i + 1.0) / steps);
                    os.write(i);
                    os.flush();
                    // Tiny yield so cancel/pause requests have a chance to land.
                    Thread.sleep(5);
                }
            }
        }
    }

    /** Runner that records execution order and blocks until released. */
    static final class BlockingRunner implements com.benesquivelmusic.daw.sdk.export.RenderJobRunner {
        final List<String> executionOrder = new CopyOnWriteArrayList<>();
        final CountDownLatch runningStarted = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
        private final Object releaseGate = new Object();
        private volatile boolean released;

        void releaseAll() {
            synchronized (releaseGate) {
                released = true;
                releaseGate.notifyAll();
            }
        }

        @Override
        public void run(RenderJob job, com.benesquivelmusic.daw.sdk.export.JobControl control) throws Exception {
            executionOrder.add(job.jobId());
            if (started.incrementAndGet() == 1) runningStarted.countDown();
            control.publishProgress("blocking", 0.1);
            // Block until released, but cooperate with cancel.
            while (!released) {
                if (control.isCancelled()) throw new InterruptedException("cancelled");
                synchronized (releaseGate) {
                    if (!released) releaseGate.wait(50);
                }
            }
        }
    }

    @Test
    void persistenceJsonShape(@TempDir Path tmp) throws IOException {
        var snap = new RenderQueue.JobSnapshot(
                "id-1", "My Song", "StereoMasterJob",
                tmp.resolve("song.wav"),
                JobProgress.Phase.QUEUED, "Queued", 0.0, 1L);
        String json = RenderQueuePersistence.toJson(List.of(snap));
        assertThat(json).contains("\"jobId\":\"id-1\"");
        assertThat(json).contains("\"jobType\":\"StereoMasterJob\"");
        var roundTrip = RenderQueuePersistence.fromJson(json);
        assertThat(roundTrip).hasSize(1);
        assertThat(roundTrip.get(0).jobId()).isEqualTo("id-1");
        assertThat(roundTrip.get(0).displayName()).isEqualTo("My Song");
        assertThat(roundTrip.get(0).phase()).isEqualTo(JobProgress.Phase.QUEUED);
    }
}
