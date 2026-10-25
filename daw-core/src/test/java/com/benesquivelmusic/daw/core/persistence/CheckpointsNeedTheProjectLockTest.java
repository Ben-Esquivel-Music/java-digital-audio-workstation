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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
 * {@link ProjectLockManager#verifyOurs()} passes.
 */
class CheckpointsNeedTheProjectLockTest {

    /** A helper temp of a checkpoint name, as a hard kill mid-write leaves it. */
    private static final String STRAY_TEMP = ".checkpoint-007-20261023T120000.daw.0123456789abcdef.tmp";

    @TempDir
    Path tempDir;

    private final List<ProjectManager> managers = new ArrayList<>();

    @AfterEach
    void releaseTheManagers() {
        managers.forEach(ProjectManager::abandonProject);
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

        checkpoints.start(folder).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    private ProjectManager manager(String user) {
        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)), null,
                new ProjectLockManager(InstantSource.fixed(Instant.parse("2026-10-23T12:00:00Z")), user, "studio",
                        managers.size() + 1L));
        managers.add(manager);
        return manager;
    }
}
