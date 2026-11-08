package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A session that does not hold a project's lock never seeds, writes, prunes
 * or cleans up that project's checkpoints (story 331, studio-engineer item 1;
 * Persistence Integrity book §4.8): a read-only open starts no checkpoints —
 * and stops those of the project open before it — and the checkpoints' write
 * guard, which {@link ProjectManager} installs, refuses while this session
 * holds no lock ({@link ProjectLockManager#verifyHeld()}), where
 * {@link ProjectLockManager#verifyOurs()} passes. Each generation checks the
 * lease its own open or create took (story 333), never the next project's.
 */
class CheckpointsNeedTheProjectLockTest {

    /** A helper temp of a checkpoint name, as a hard kill mid-write leaves it. */
    private static final String STRAY_TEMP = ".checkpoint-007-20261023T120000.daw.0123456789abcdef.tmp";

    @TempDir
    Path tempDir;

    private final List<ProjectManager> managers = new ArrayList<>();

    @AfterEach
    void releaseTheManagers() {
        // Each release reads and deletes a lock file: let it end before the temporary folder is deleted.
        managers.forEach(manager -> SaveTestSupport.await(manager.closeProject()));
    }

    @Test
    void aReadOnlyOpenStartsNoCheckpointsAndStopsThoseOfTheProjectOpenBefore() throws Exception {
        ProjectManager alice = manager("alice");
        Path shared = alice.createProject("Shared", tempDir).projectPath();
        assertThat(alice.getCheckpointManager().awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("fixture: alice's seeding finished before the stray temp appears").isTrue();
        Path strayTemp = Files.createDirectories(shared.resolve("checkpoints")).resolve(STRAY_TEMP);
        Files.writeString(strayTemp, "torn");
        ProjectManager bob = manager("bob");
        bob.setLockConflictHandler((holder, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        bob.createProject("Own", tempDir);
        CheckpointManager bobsCheckpoints = bob.getCheckpointManager();
        assertThat(bobsCheckpoints.isRunning()).as("fixture: bob's own project's checkpoints run").isTrue();

        bob.openProject(shared);

        assertThat(bob.isReadOnly()).as("fixture: alice holds the lock").isTrue();
        assertThat(bobsCheckpoints.isRunning()).as("no checkpoint scheduler runs for a read-only open").isFalse();
        assertThat(strayTemp).as("nothing seeded or cleaned the folder").exists();
        assertThat(bobsCheckpoints.getCheckpointFiles()).isEmpty();
        CompletableFuture<Path> refused = bobsCheckpoints.writeCheckpoint("<daw-project/>",
                new DawProject("Shared", AudioFormat.CD_QUALITY).getEditRevision(), CheckpointOrigin.USER);
        assertThatThrownBy(() -> refused.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IOException.class).hasMessageContaining("Checkpoints are not running");
        assertThat(checkpointNames(shared)).containsExactly(STRAY_TEMP);
    }

    @Test
    void theWriteGuardRefusesWhileTheSessionHoldsNoLock() throws Exception {
        ProjectManager manager = manager("carol");
        Path folder = Files.createDirectories(tempDir.resolve("Unlocked"));
        Path strayTemp = Files.createDirectories(folder.resolve("checkpoints")).resolve(STRAY_TEMP);
        Files.writeString(strayTemp, "torn");
        manager.getLockManager().verifyOurs();   // non-vacuity: the plain check passes with no lock held
        CheckpointManager checkpoints = manager.getCheckpointManager();

        // The guard the project manager gives each generation it starts: its lease's verifyHeld (story 333).
        checkpoints.start(folder, manager.getLockManager()::verifyHeld).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Path> written = checkpoints.writeCheckpoint("<daw-project/>",
                new DawProject("Unlocked", AudioFormat.CD_QUALITY).getEditRevision(), CheckpointOrigin.USER);

        assertThatThrownBy(() -> written.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IOException.class)
                .hasMessage("This session holds no project lock (NONE); it does not write the project folder");
        assertThat(strayTemp).as("seeding left the stray temp: the guard refused its delete").exists();
        assertThat(checkpointNames(folder)).as("no checkpoint was written").containsExactly(STRAY_TEMP);
        checkpoints.stop();
    }

    /**
     * Story 333: each checkpoint generation the project manager starts checks
     * the lease its own open took. Song A's checkpoint is held inside its
     * atomic write — claimed — when this session abandons Song A and creates
     * Song B, as New does, and another session then takes Song A over. Once
     * released, the write's prune
     * deletes the oldest checkpoint and asks Song A's lease before compacting
     * Song A's ordering fact; that lease refuses, so the fact is left as it
     * was. A guard that read the lease current at the job — Song B's, which
     * holds its lock — would have let the compaction write into the folder
     * another session owns. Song A's lock, now the other session's, is never
     * deleted by the release of this session's lease.
     *
     * <p>The prune's own delete of the oldest checkpoint, in a folder another
     * session now holds, is a pre-existing gap of story 331's prune — a
     * prune's deletes run inside a write that passed the guard and do not
     * check it again — not a contract this test pins: a later story that
     * guards the prune's deletes flips the fixture assertion below.</p>
     */
    @Test
    void aClaimedWriteOfAReplacedProjectChecksTheLeaseOfItsOwnOpenNeverTheNextProjects() throws Exception {
        CountDownLatch heldEntered = new CountDownLatch(1);
        CountDownLatch heldReleased = new CountDownLatch(1);
        AtomicBoolean holdTheNext = new AtomicBoolean();
        AtomicFileWriter holding = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
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
        ProjectManager alice = manager("alice", new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 1, true),
                holding));
        try {
            Path songA = alice.createProject("Song A", tempDir).projectPath();
            CheckpointManager checkpoints = alice.getCheckpointManager();
            assertThat(checkpoints.awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                    .as("fixture: Song A's checkpoints were seeded").isTrue();
            Path oldest = checkpoints.writeCheckpoint("<daw-project/>", 1L, CheckpointOrigin.USER)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            holdTheNext.set(true);
            CompletableFuture<Path> claimed = checkpoints.writeCheckpoint("<daw-project/>", 2L, CheckpointOrigin.USER);
            assertThat(heldEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the write reached its publish").isTrue();
            Path fact = songA.resolve("checkpoints").resolve(CheckpointOrder.FILE_NAME);
            String factWhileHeld = Files.readString(fact);

            alice.closeProject();
            alice.createProject("Song B", tempDir);
            ProjectLock mallorys = new ProjectLockManager(InstantSource.fixed(Instant.parse("2026-10-23T12:00:00Z")),
                    "mallory", "elsewhere", 99L).forceAcquire(songA);
            heldReleased.countDown();
            assertThat(claimed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).exists();
            alice.whenRetiredLeasesReleased().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(oldest).as("fixture: the prune ran, so the compaction asked a guard").doesNotExist();
            assertThat(Files.readString(fact)).as("Song A's own lease refused the compaction").isEqualTo(factWhileHeld);
            assertThat(ProjectLockManager.peekLock(songA)).as("the other session's lock is left alone")
                    .hasValueSatisfying(lock -> assertThat(lock.lockId()).isEqualTo(mallorys.lockId()));
            assertThat(alice.getLockManager().currentLock()).as("Song B's lease holds Song B").isPresent();
        } finally {
            heldReleased.countDown();
        }
    }

    private ProjectManager manager(String user) {
        return manager(user, new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    private ProjectManager manager(String user, CheckpointManager checkpoints) {
        ProjectManager manager = new ProjectManager(checkpoints, null,
                new ProjectLockManager(InstantSource.fixed(Instant.parse("2026-10-23T12:00:00Z")), user, "studio",
                        managers.size() + 1L));
        managers.add(manager);
        return manager;
    }
}
