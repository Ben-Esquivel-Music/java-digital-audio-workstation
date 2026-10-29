package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project lock is never released under a claimed checkpoint write
 * (story 333; Persistence Integrity book §4.2; FEAS-N7): when the project
 * manager closes or replaces a project — Close, an Open of another project, a
 * create of a new one — while the checkpoint writer is inside a claimed write
 * in its folder, the call returns at once, the folder's {@code .project.lock}
 * stays until that write has ended, and it is released then, on a thread of
 * its own; the manager carries on with a fresh lease. With nothing owed the
 * release still runs on that thread, never the caller's.
 */
class LockReleaseWaitsForClaimedCheckpointWorkTest {

    /** The project-manager calls that end the open project's checkpoints and release its lease. */
    enum Ending { CLOSE, OPEN_ANOTHER, CREATE_ANOTHER }

    private static final InstantSource CLOCK = InstantSource.fixed(Instant.parse("2026-10-28T12:00:00Z"));

    @TempDir
    Path workspace;

    private final AtomicBoolean holdTheNext = new AtomicBoolean();
    private final CountDownLatch heldEntered = new CountDownLatch(1);
    private final CountDownLatch heldReleased = new CountDownLatch(1);
    private ProjectManager manager;

    /** Holds the next checkpoint publish inside its atomic write until released. */
    private final AtomicFileWriter holding = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
            (temp, target, options) -> {
                if (CheckpointFileName.parse(target.getFileName().toString()).isPresent()
                        && holdTheNext.compareAndSet(true, false)) {
                    heldEntered.countDown();
                    try {
                        if (!heldReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IOException("the test never released the held write");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted inside the held write", interrupted);
                    }
                }
                AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
            });

    @AfterEach
    void releaseEverything() throws Exception {
        heldReleased.countDown();
        if (manager != null) {
            manager.closeProject().join();
            manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void theLockOfAFolderWithAClaimedCheckpointWriteStaysUntilTheWriteHasEnded(Ending ending) throws Exception {
        Path songB = ending == Ending.OPEN_ANOTHER ? anotherProjectOnDisk() : null;
        AtomicBoolean afterTheHold = new AtomicBoolean();
        java.util.List<String> readersAfterTheHold = new java.util.concurrent.CopyOnWriteArrayList<>();
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                holding), null, new ProjectLockManager(CLOCK, "alice", "studio", 1L, new AtomicFileWriter(),
                lockFile -> {
                    if (afterTheHold.get()) {
                        readersAfterTheHold.add(Thread.currentThread().getName());
                    }
                    return Files.readString(lockFile);
                }));
        Path songA = manager.createProject("Song A", workspace).projectPath();
        ProjectLockManager songAsLease = manager.getLockManager();
        String songAsLock = songAsLease.currentLock().orElseThrow().lockId();
        CheckpointManager checkpoints = manager.getCheckpointManager();
        assertThat(checkpoints.awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("fixture: Song A's checkpoints were seeded").isTrue();
        holdTheNext.set(true);
        CompletableFuture<Path> claimed = checkpoints.writeCheckpoint("<daw-project/>", 1L, CheckpointOrigin.USER);
        assertThat(heldEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the write reached its publish").isTrue();

        // The held write waits up to the whole timeout: a call that waited for it would not return within half.
        CompletableFuture.runAsync(() -> end(ending, songB)).get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);

        assertThat(ProjectLockManager.peekLock(songA)).as("Song A's lock stays while its write is held")
                .hasValueSatisfying(lock -> assertThat(lock.lockId()).isEqualTo(songAsLock));
        assertThat(manager.getLockManager()).as("the manager carries on with a fresh lease")
                .isNotSameAs(songAsLease);
        assertThat(manager.whenRetiredLeasesReleased()).isNotDone();
        afterTheHold.set(true);
        heldReleased.countDown();
        assertThat(claimed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).exists();
        manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(readersAfterTheHold)
                .as("the release read the lock on a thread of its own, never on the checkpoint writer that ended"
                        + " the claim (stage 1's review S3)")
                .contains("daw-lease-retire");

        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released once the write ended")
                .doesNotExist();
        assertThat(songAsLease.status()).isEqualTo(LockStatus.NONE);
    }

    @Test
    void withNothingOwedTheLockIsStillReleasedOffTheCallerAndTheLeaseIsNeverReused() throws Exception {
        java.util.List<Thread> lockReaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                holding), null, new ProjectLockManager(CLOCK, "alice", "studio", 1L, new AtomicFileWriter(),
                lockFile -> {
                    lockReaders.add(Thread.currentThread());
                    return Files.readString(lockFile);
                }));
        Path songA = manager.createProject("Song A", workspace).projectPath();
        ProjectLockManager lease = manager.getLockManager();
        assertThat(manager.getCheckpointManager().awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("fixture: Song A's checkpoints were seeded").isTrue();
        lockReaders.clear();

        manager.closeProject().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("released once nothing was owed")
                .doesNotExist();
        assertThat(lockReaders).as("the release read the lock on a thread of its own, never the caller's")
                .isNotEmpty()
                .allSatisfy(reader -> assertThat(reader.getName()).isEqualTo("daw-lease-retire"));
        assertThat(manager.getLockManager()).as("a retired lease is never reused").isNotSameAs(lease);
        assertThat(manager.whenRetiredLeasesReleased()).isCompleted();
    }

    /**
     * Stage 1's review N4: a read-only open starts no checkpoint generation,
     * so its close owes nothing of the checkpoints of the folder open before
     * it — its release (which holds no lock) never waits for that folder's
     * claimed write, while that folder's own lock still does.
     */
    @Test
    void aReadOnlyProjectsCloseNeverWaitsForThePreviousFoldersClaimedWrite() throws Exception {
        Path songB = anotherProjectOnDisk();
        ProjectLockManager bob = new ProjectLockManager(CLOCK, "bob", "studio", 2L);
        bob.forceAcquire(songB);
        try {
            manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                    holding), null, new ProjectLockManager(CLOCK, "alice", "studio", 1L));
            manager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
            Path songA = manager.createProject("Song A", workspace).projectPath();
            CheckpointManager checkpoints = manager.getCheckpointManager();
            assertThat(checkpoints.awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                    .as("fixture: Song A's checkpoints were seeded").isTrue();
            holdTheNext.set(true);
            CompletableFuture<Path> claimed = checkpoints.writeCheckpoint("<daw-project/>", 1L,
                    CheckpointOrigin.USER);
            assertThat(heldEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the write reached its publish")
                    .isTrue();
            manager.openProject(songB);
            assertThat(manager.isReadOnly()).as("fixture: Song B is open read-only").isTrue();

            manager.closeProject().get(TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);

            assertThat(ProjectLockManager.peekLock(songA)).as("Song A's lock still waits for its held write")
                    .isPresent();
            heldReleased.countDown();
            assertThat(claimed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).exists();
            manager.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
        } finally {
            heldReleased.countDown();
            bob.close();
        }
    }

    private void end(Ending ending, Path songB) {
        try {
            switch (ending) {
                case CLOSE -> manager.closeProject();
                case OPEN_ANOTHER -> manager.openProject(songB);
                case CREATE_ANOTHER -> manager.createProject("Song C", workspace);
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** Song B, made and released by a manager of its own; returns its folder. */
    private Path anotherProjectOnDisk() throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10,
                true)), null, new ProjectLockManager(CLOCK, "bob", "studio", 2L));
        Path songB = other.createProject("Song B", Files.createDirectories(workspace.resolve("elsewhere")))
                .projectPath();
        other.closeProject().join();
        return songB;
    }
}
