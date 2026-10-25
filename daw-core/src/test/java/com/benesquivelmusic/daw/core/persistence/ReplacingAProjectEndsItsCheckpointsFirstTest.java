package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A create or an open replaces the open project, and ends its checkpoints
 * before any step that can fail (story 331; ruling F9): when the new
 * project's lock cannot be written, the call throws with no project's
 * checkpoints running, so neither a save nor a Create Checkpoint can write
 * the new project's document into the old project's {@code checkpoints/}.
 * The lock writer fails every write in the new project's folder.
 */
class ReplacingAProjectEndsItsCheckpointsFirstTest {

    private static final String NEW_PROJECT = "Beta";

    @TempDir
    Path tempDir;

    private final IOException lockWriteFailure = new IOException("injected lock write failure");
    private CheckpointManager checkpoints;
    private ProjectManager manager;
    private Path alpha;

    @BeforeEach
    void openAlphaWithACheckpoint() throws Exception {
        AtomicFileWriter lockWriter = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (target.toString().contains(NEW_PROJECT)) {
                        throw lockWriteFailure;
                    }
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                });
        checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
        manager = new ProjectManager(checkpoints, null,
                new ProjectLockManager(InstantSource.system(), "alice", "test-host", 1L, lockWriter),
                new ProjectDeserializer());
        alpha = manager.createProject("Alpha", tempDir).projectPath();
        checkpoints.writeCheckpoint("<daw-project name=\"Alpha\"/>", 1, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() {
        manager.abandonProject();
        checkpoints.stop();
    }

    @Test
    void aCreateWhoseLockCannotBeWrittenLeavesNoCheckpointsRunningAndTheOldFolderAsItWas() throws Exception {
        Map<String, String> before = checkpointsIn(alpha);

        assertThatThrownBy(() -> manager.createProject(NEW_PROJECT, tempDir)).isSameAs(lockWriteFailure);

        assertNoCheckpointsRunAndNothingReachesTheOldFolder(before);
        assertThat(manager.getCurrentProject().name()).as("the state after the failure: the new project is current")
                .isEqualTo(NEW_PROJECT);
        DawProject beta = new DawProject(NEW_PROJECT, AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> manager.save(beta)).as("and cannot be saved: no save target was installed")
                .isInstanceOf(IllegalStateException.class).hasMessage("No project is currently open");
    }

    @Test
    void anOpenWhoseLockTakeoverCannotBeWrittenLeavesNoCheckpointsRunningAndTheOldFolderAsItWas()
            throws Exception {
        Path beta = projectHeldByAnotherSession();
        manager.setLockConflictHandler((holder, stale) -> LockConflictResolution.TAKE_OVER);
        Map<String, String> before = checkpointsIn(alpha);

        assertThatThrownBy(() -> manager.openProject(beta)).isSameAs(lockWriteFailure);

        assertNoCheckpointsRunAndNothingReachesTheOldFolder(before);
        assertThat(manager.getCurrentProject().projectPath())
                .as("the state after the failure: the open failed before it replaced the project")
                .isEqualTo(alpha);
    }

    private void assertNoCheckpointsRunAndNothingReachesTheOldFolder(Map<String, String> before)
            throws Exception {
        assertThat(checkpoints.runningGeneration()).as("no generation runs").isEmpty();
        assertThat(checkpoints.isRunning()).isFalse();
        CompletableFuture<Path> createCheckpoint = checkpoints.writeCheckpoint("<daw-project name=\"Beta\"/>", 2,
                CheckpointOrigin.USER);
        assertThat(createCheckpoint).as("Create Checkpoint is refused").isCompletedExceptionally();
        assertThat(createCheckpoint.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessage("Checkpoints are not running: no project directory is open for checkpoints");
        assertThat(checkpointsIn(alpha)).as("the old folder's checkpoints, names and bytes").isEqualTo(before)
                .isNotEmpty();
    }

    /** {@value #NEW_PROJECT} on disk, its lock held by another session that never released it. */
    private Path projectHeldByAnotherSession() throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = SaveTestSupport.projectOnDisk(other, NEW_PROJECT, tempDir.resolve("elsewhere"));
        ProjectLockManager bob = new ProjectLockManager(InstantSource.system(), "bob", "other-host", 2L);
        assertThat(bob.tryAcquire(directory).wasAcquired()).as("fixture: another session holds the lock").isTrue();
        return directory;
    }

    /** Each file in {@code projectDir/checkpoints} by name, with its content. */
    private static Map<String, String> checkpointsIn(Path projectDir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Path directory = projectDir.resolve("checkpoints");
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                files.put(entry.getFileName().toString(), Files.readString(entry));
            }
        }
        return files;
    }
}
