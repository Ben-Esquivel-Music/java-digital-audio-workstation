package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.ManualOwner;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TestSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CheckpointManager#stop()} ends a generation (story 331, rulings F9,
 * G5): a capture not yet taken is dropped, a job not yet claimed never
 * writes and its future completes at once, off the caller's thread, a write
 * already inside the atomic writer lands in its own directory and tells no
 * listener — it prunes when it lands while {@code stop()} waits, and deletes
 * nothing when it lands after {@code stop()} gave up on it (ruling G5 (3)) —
 * a tick that meets the stop is no failure, and {@code stop()} waits a
 * bounded time for the writer, never for the owner.
 */
class CheckpointStopFenceTest {

    /** {@code stop()}'s bound in the tests whose write is held past it. */
    private static final Duration SHORT_BOUND = Duration.ofMillis(300);

    private static final String OLD_CHECKPOINT = "checkpoint-001-20250101T000000.daw";

    @TempDir
    Path projectDir;

    private final CountDownLatch moveEntered = new CountDownLatch(1);
    private final CountDownLatch moveReleased = new CountDownLatch(1);
    private final ExecutorService stopper = Executors.newVirtualThreadPerTaskExecutor();

    /** A writer whose every publish waits inside the atomic write until the test releases it. */
    private final AtomicFileWriter heldWriter = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
            (temp, target, options) -> {
                moveEntered.countDown();
                try {
                    if (!moveReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("the test never released the publish");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted inside the publish", interrupted);
                }
                AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
            });

    @AfterEach
    void releaseEverything() {
        moveReleased.countDown();
        stopper.shutdownNow();
    }

    @Test
    void aCaptureNotYetTakenWhenStopRunsIsDroppedOnTheOwner() throws Exception {
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        CheckpointManager manager = manager(new AtomicFileWriter(), CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.setCaptureSource(owner, source);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        owner.awaitPosted(1);

        manager.stop();
        owner.runPending();

        assertThat(source.captures).hasValue(0);
        assertThat(checkpointNames(projectDir)).isEmpty();
        assertThat(recorder.sdk("before")).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Written.class)).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
    }

    @Test
    void stopWaitsForAWriteAlreadyRunningSoTheFolderIsReleasedAfterIt() throws Exception {
        CheckpointManager manager = manager(heldWriter, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> running = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);
        awaitMoveEntered();

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(manager::stop, stopper);
        awaitGenerationEnded(manager);
        assertThat(stopped).as("stop() waits while the write is held").isNotDone();
        moveReleased.countDown();
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path written = running.getNow(null);
        assertThat(written).as("the write finished before stop() returned").isNotNull().exists();
        assertThat(written.getParent()).isEqualTo(projectDir.resolve("checkpoints"));
        assertThat(recorder.sdk("after")).as("a write that ends after stop() tells no listener").isEmpty();
        assertThat(recorder.events(CheckpointEvent.Written.class)).isEmpty();
    }

    @Test
    void aWriteThatLandsWhileStopWaitsWithinItsBoundPrunesBackToTheCap() throws Exception {
        Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(projectDir.resolve("checkpoints").resolve(OLD_CHECKPOINT), "an older session");
        CheckpointManager manager = CheckpointTestSupport.manager(
                new AutoSaveConfig(Duration.ofHours(1), 1, true), heldWriter, new CountingClock(), Files::delete,
                CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);
        awaitMoveEntered();

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(manager::stop, stopper);
        awaitGenerationEnded(manager);
        assertThat(stopped).as("stop() waits while the write is held").isNotDone();
        moveReleased.countDown();
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path written = held.getNow(null);
        assertThat(written).as("the write landed before stop() returned").isNotNull().exists();
        assertThat(checkpointNames(projectDir)).as("a cap of one: the seeded checkpoint was pruned")
                .containsExactly(written.getFileName().toString());
        assertThat(recorder.events(CheckpointEvent.Written.class)).as("its generation ended: no listener hears")
                .isEmpty();
    }

    @Test
    void aWriteHeldPastTheBoundFinishesIntoItsOwnDirectoryAndTellsNoListenerAndPrunesNothing()
            throws Exception {
        Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(projectDir.resolve("checkpoints").resolve(OLD_CHECKPOINT), "an older session");
        CheckpointManager manager = CheckpointTestSupport.manager(
                new AutoSaveConfig(Duration.ofHours(1), 1, true), heldWriter, new CountingClock(), Files::delete,
                SHORT_BOUND);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);
        awaitMoveEntered();

        CompletableFuture.runAsync(manager::stop, stopper).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(held).as("stop() returned at its bound with the write still held").isNotDone();
        moveReleased.countDown();
        Path written = held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(written).exists();
        assertThat(written.getParent()).isEqualTo(projectDir.resolve("checkpoints"));
        assertThat(projectDir.resolve("checkpoints").resolve(OLD_CHECKPOINT))
                .as("a cap of one, and still nothing pruned").exists();
        assertThat(recorder.sdk("after")).isEmpty();
        assertThat(recorder.sdk("failed")).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Written.class)).isEmpty();
        assertThat(recorder.events(CheckpointEvent.Rearmed.class))
                .as("only the start's re-arm: an ended generation arms nothing").hasSize(1);
    }

    @Test
    void aJobQueuedButNotClaimedWhenStopRunsNeverWritesAndItsFutureCompletes() throws Exception {
        CheckpointManager manager = manager(heldWriter, SHORT_BOUND);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);
        awaitMoveEntered();
        CompletableFuture<Path> queued = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER);

        CompletableFuture.runAsync(manager::stop, stopper).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        moveReleased.countDown();
        held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(queued).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(queued.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessageContaining("stopped before this checkpoint was written");
        assertThat(checkpointNames(projectDir)).as("only the write that was already running").hasSize(1);
        assertThat(recorder.sdk("before")).as("the queued job never began").hasSize(1);
    }

    @Test
    void aJobQueuedBehindAWriteHeldPastTheBoundCompletesOffTheStopCallerWhileTheWriteIsStillHeld()
            throws Exception {
        CheckpointManager manager = manager(heldWriter, SHORT_BOUND);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitMoveEntered();
        CompletableFuture<Path> queued = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.AFTER_SAVE);
        CountDownLatch dependantRan = new CountDownLatch(1);
        AtomicReference<Thread> dependantThread = new AtomicReference<>();
        queued.whenComplete((file, failure) -> {
            dependantThread.set(Thread.currentThread());
            dependantRan.countDown();
        });
        AtomicReference<Thread> stopCaller = new AtomicReference<>();

        CompletableFuture.runAsync(() -> {
            stopCaller.set(Thread.currentThread());
            manager.stop();
        }, stopper).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Within half the bound the held publish waits for the test, so a completion now cannot be the writer's.
        assertThat(dependantRan.await(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS))
                .as("the queued job's future completes while the write ahead of it is held").isTrue();
        assertThat(held).as("the write is still held").isNotDone();
        assertThat(queued.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessageContaining("stopped before this checkpoint was written");
        assertThat(dependantThread.get()).as("its dependant ran off the thread that called stop()")
                .isNotNull().isNotSameAs(stopCaller.get());
        moveReleased.countDown();
        held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(checkpointNames(projectDir)).as("only the write that was already running").hasSize(1);
        assertThat(recorder.sdk("before")).as("the queued job never began").hasSize(1);
        assertThat(recorder.sdk("failed")).isEmpty();
    }

    @Test
    void aTickThatMeetsAConcurrentStopIsNoFailure() throws Exception {
        AtomicBoolean holdTheNextArming = new AtomicBoolean();
        CountDownLatch tickArming = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicReference<Thread> scheduler = new AtomicReference<>();
        // Holds the tick that passed its check while stop() ends the generation and shuts the scheduler down.
        InstantSource clock = () -> {
            if (Thread.currentThread().getName().equals("daw-checkpoint-scheduler")
                    && holdTheNextArming.compareAndSet(true, false)) {
                scheduler.set(Thread.currentThread());
                tickArming.countDown();
                try {
                    stopReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return CountingClock.NOW;
        };
        CheckpointManager manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofMillis(40), 10, true),
                new AtomicFileWriter(), clock, Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        List<LogRecord> failures = new CopyOnWriteArrayList<>();
        Handler severe = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    failures.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger managerLog = Logger.getLogger(CheckpointManager.class.getName());
        managerLog.addHandler(severe);
        try {
            manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            recorder.awaitRearms(CheckpointEvent.RearmReason.STARTED, 1);
            holdTheNextArming.set(true);
            assertThat(tickArming.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the first tick is arming").isTrue();

            manager.stop();
            stopReturned.countDown();
            scheduler.get().join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            assertThat(scheduler.get().isAlive()).as("the tick finished and the scheduler is gone").isFalse();
            assertThat(failures).as("a normal project switch, not a failed tick").isEmpty();
        } finally {
            managerLog.removeHandler(severe);
        }
    }

    @Test
    void aCheckpointRequestedAfterStopCompletesAtOnce() throws Exception {
        CheckpointManager manager = manager(new AtomicFileWriter(), CheckpointManager.STOP_WAIT);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        CompletableFuture<Path> late = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);

        assertThat(late).isCompletedExceptionally();
        assertThat(late.exceptionNow()).isInstanceOf(IOException.class);
        assertThat(checkpointNames(projectDir)).isEmpty();
    }

    @Test
    void stopNeverWaitsOnAStalledOwner() throws Exception {
        ManualOwner stalled = new ManualOwner();
        CheckpointManager manager = manager(new AtomicFileWriter(), CheckpointManager.STOP_WAIT);
        manager.setCaptureSource(stalled, new TestSource());
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        stalled.awaitPosted(1);

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(manager::stop, stopper);

        stopped.get(CheckpointManager.STOP_WAIT.toMillis() / 2, TimeUnit.MILLISECONDS);
        assertThat(stalled.pendingCount()).as("the owner never ran the capture").isEqualTo(1);
        assertThat(manager.isRunning()).isFalse();
    }

    private CheckpointManager manager(AtomicFileWriter writer, Duration stopWait) {
        return CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofMillis(40), 10, true), writer,
                new CountingClock(), Files::delete, stopWait);
    }

    private void awaitMoveEntered() throws InterruptedException {
        assertThat(moveEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the write reached its publish").isTrue();
    }

    /** Waits until {@code stop()} has ended the generation: {@link CheckpointManager#isRunning()} turns false then. */
    private static void awaitGenerationEnded(CheckpointManager manager) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (manager.isRunning()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("stop() did not end the generation within the bound");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
