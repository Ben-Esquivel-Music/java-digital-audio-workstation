package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim is the only way a checkpoint job writes or deletes in its folder
 * (story 333; Persistence Integrity book §4.2): a write's tail — its index,
 * its atomic write, its prune — and a seeding's compaction of the ordering
 * fact and its deletes of stray temps each run inside one, taken only while
 * the generation runs. {@link CheckpointManager#stop()} returns at once, and
 * the stage it returns completes only once the claimed work has ended, so the
 * project lock released after it is never released under a write or a delete.
 * A queued or unclaimed job never claims after the end, and every claimed job
 * checks the lease of its own generation, never the next one's.
 */
class CheckpointClaimTest {

    private static final String OLD_CHECKPOINT = "checkpoint-001-20250101T000000.daw";
    private static final String STRAY_TEMP = ".checkpoint-007-20250101T000000.daw.0123456789abcdef.tmp";

    @TempDir
    Path projectDir;

    private final CountDownLatch heldEntered = new CountDownLatch(1);
    private final CountDownLatch heldReleased = new CountDownLatch(1);
    private final ExecutorService stopper = Executors.newVirtualThreadPerTaskExecutor();
    private final List<CheckpointManager> managers = new CopyOnWriteArrayList<>();

    @AfterEach
    void releaseEverything() throws Exception {
        heldReleased.countDown();
        try {
            // Bounded: a stop() that waited inside a writer's listener would hold every later stop() for good.
            CompletableFuture.runAsync(() -> managers.forEach(CheckpointManager::stop), stopper)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            stopper.shutdownNow();
        }
    }

    @Test
    void aPruneDeleteHeldInsideItsClaimHoldsTheStageThatStopReturnedAtOnce() throws Exception {
        Path old = oldCheckpoint();
        CheckpointManager manager = manager(1, holdingDeletesOf(old::equals), new AtomicFileWriter());
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> written = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitHeld();

        CompletableFuture<Void> stopped = stopAtOnce(manager);

        assertThat(stopped).as("the delete of the prune is held inside the write's claim").isNotDone();
        heldReleased.countDown();
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(old).as("the delete decided inside the claim was issued before the stage completed")
                .doesNotExist();
        assertThat(written.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).exists();
    }

    @Test
    void aSeedingsStrayTempDeleteHeldInsideItsClaimHoldsTheStage() throws Exception {
        Path stray = Files.writeString(Files.createDirectories(projectDir.resolve("checkpoints")).resolve(STRAY_TEMP),
                "torn");
        CheckpointManager manager = manager(10, holdingDeletesOf(stray::equals), new AtomicFileWriter());
        CompletableFuture<Void> seeded = manager.start(projectDir);
        awaitHeld();

        CompletableFuture<Void> stopped = stopAtOnce(manager);

        assertThat(stopped).as("the seeding's delete is held inside its claim").isNotDone();
        heldReleased.countDown();
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(stray).doesNotExist();
        seeded.handle((done, failure) -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void aSeedingsCompactionOfTheOrderingFactHeldInsideItsClaimHoldsTheStage() throws Exception {
        Path checkpoints = Files.createDirectories(projectDir.resolve("checkpoints"));
        // The fact names a checkpoint no longer on disk: seeding compacts it.
        CheckpointOrder.record(checkpoints, java.util.Set.of(OLD_CHECKPOINT));
        Path fact = checkpoints.resolve(CheckpointOrder.FILE_NAME);
        CheckpointManager manager = manager(10, Files::delete, holdingPublishesOf(fact::equals));
        CompletableFuture<Void> seeded = manager.start(projectDir);
        awaitHeld();

        CompletableFuture<Void> stopped = stopAtOnce(manager);

        assertThat(stopped).as("the compaction's write is held inside the seeding's claim").isNotDone();
        heldReleased.countDown();
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(CheckpointOrder.read(checkpoints)).as("compacted before the stage completed").isEmpty();
        seeded.handle((done, failure) -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void aJobQueuedBehindAClaimedWriteNeverClaimsAfterTheEndAndTheStageDoesNotWaitForIt() throws Exception {
        CheckpointManager manager = manager(10, Files::delete, holdingPublishesOf(CheckpointClaimTest::isACheckpoint));
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> claimed = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitHeld();
        CompletableFuture<Path> queued = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.CLOSING);

        CompletableFuture<Void> stopped = stopAtOnce(manager);
        heldReleased.countDown();
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(claimed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).exists();
        assertThat(queued).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(queued.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessageContaining("stopped before this checkpoint was written");
        assertThat(checkpointNames(projectDir)).as("only the claimed write").hasSize(1);
    }

    @Test
    void aClaimedTailOfTheEndedGenerationChecksItsOwnGuardNeverTheNextGenerations() throws Exception {
        Path old = oldCheckpoint();
        Path checkpoints = projectDir.resolve("checkpoints");
        CheckpointOrder.record(checkpoints, java.util.Set.of(OLD_CHECKPOINT));
        Path another = Files.createDirectories(projectDir.resolve("Another project"));
        CheckpointManager manager = manager(1, Files::delete, holdingPublishesOf(CheckpointClaimTest::isACheckpoint));
        AtomicBoolean ownLeaseLost = new AtomicBoolean();
        AtomicInteger ownChecks = new AtomicInteger();
        AtomicInteger nextChecks = new AtomicInteger();
        manager.start(projectDir, () -> {
            ownChecks.incrementAndGet();
            if (ownLeaseLost.get()) {
                throw new LockStolenException("another session took the project over", null);
            }
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> claimed = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitHeld();
        String factWhileHeld = Files.readString(checkpoints.resolve(CheckpointOrder.FILE_NAME));

        // The project is replaced while its write is claimed, and then loses its lock.
        manager.start(another, nextChecks::incrementAndGet).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ownLeaseLost.set(true);
        int checksBeforeTheTail = ownChecks.get();
        heldReleased.countDown();
        Path written = claimed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(written).exists();
        assertThat(old).as("fixture: the tail's prune ran, so its compaction asked the guard").doesNotExist();
        assertThat(ownChecks.get()).as("the tail asked its own generation's guard").isGreaterThan(checksBeforeTheTail);
        assertThat(nextChecks).as("never the next generation's").hasValue(0);
        assertThat(Files.readString(checkpoints.resolve(CheckpointOrder.FILE_NAME)))
                .as("its own guard refused the compaction").isEqualTo(factWhileHeld);
    }

    @Test
    void aStopFromAListenerOnTheWriterInsideAClaimReturnsAndItsStageCompletesAfterTheClaim() throws Exception {
        CheckpointManager manager = manager(10, Files::delete, new AtomicFileWriter());
        AtomicReference<CompletableFuture<Void>> stoppedInside = new AtomicReference<>();
        manager.addEventListener(event -> {
            if (event instanceof CheckpointEvent.Written) {
                stoppedInside.set(manager.stop());
            }
        });
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path written = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(written).exists();
        assertThat(stoppedInside.get()).as("the listener's stop() returned").isNotNull();
        stoppedInside.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void stopIsIdempotentAndReturnsTheEndedGenerationsStage() throws Exception {
        CheckpointManager manager = manager(10, Files::delete, holdingPublishesOf(CheckpointClaimTest::isACheckpoint));
        assertThat(manager.stop()).as("before any start").isCompleted();
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> claimed = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitHeld();

        CompletableFuture<Void> first = stopAtOnce(manager);
        CompletableFuture<Void> again = stopAtOnce(manager);

        assertThat(again).as("the same claimed work").isNotDone();
        heldReleased.countDown();
        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        again.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        claimed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Runs {@code stop()} on a thread of its own and returns its stage; it must return well within the bound. */
    private CompletableFuture<Void> stopAtOnce(CheckpointManager manager) throws Exception {
        // A held job waits up to the whole timeout: a stop() that waited for it would not return within half.
        return CompletableFuture.supplyAsync(manager::stop, stopper).get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);
    }

    private Path oldCheckpoint() throws IOException {
        Path checkpoints = Files.createDirectories(projectDir.resolve("checkpoints"));
        return Files.writeString(checkpoints.resolve(OLD_CHECKPOINT), "an older session");
    }

    private CheckpointManager manager(int cap, CheckpointManager.FileDeleter deleter, AtomicFileWriter writer) {
        CheckpointManager manager = CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), cap, true),
                writer, new CountingClock(), deleter, CheckpointManager.STOP_WAIT);
        managers.add(manager);
        return manager;
    }

    /** A deleter that holds the first delete of a file {@code which} accepts until released, then deletes it. */
    private CheckpointManager.FileDeleter holdingDeletesOf(Predicate<Path> which) {
        AtomicBoolean holding = new AtomicBoolean(true);
        return file -> {
            if (which.test(file) && holding.compareAndSet(true, false)) {
                hold();
            }
            Files.delete(file);
        };
    }

    /** A writer that holds the first publish of a target {@code which} accepts until released. */
    private AtomicFileWriter holdingPublishesOf(Predicate<Path> which) {
        AtomicBoolean holding = new AtomicBoolean(true);
        return new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
            if (which.test(target) && holding.compareAndSet(true, false)) {
                hold();
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        });
    }

    private void hold() throws IOException {
        heldEntered.countDown();
        try {
            if (!heldReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("the test never released the held step");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while held", interrupted);
        }
    }

    private void awaitHeld() throws InterruptedException {
        assertThat(heldEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the step reached its hold").isTrue();
    }

    private static boolean isACheckpoint(Path target) {
        return CheckpointFileName.parse(target.getFileName().toString()).isPresent();
    }
}
