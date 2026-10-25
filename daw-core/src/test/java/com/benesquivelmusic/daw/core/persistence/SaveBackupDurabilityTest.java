package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover.Publish;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.UncapturableEffect;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.BACKUP;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.failed;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.tempsIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backup a migrated open owes is durable and taken before the head is
 * replaced (story 331, ruling F10): the save writer copies the original
 * through the {@link AtomicFileWriter} in {@code NEVER} mode — a forced temp,
 * then a move that never replaces — and only then replaces
 * {@code project.daw}. A copy that fails fails the save with
 * {@code project.daw} untouched and the backup still owed, and a capture that
 * cannot be produced takes no backup at all. Publishes are observed, and the
 * backup's failed, through the mover seam.
 */
class SaveBackupDurabilityTest {

    @TempDir
    Path tempDir;

    private final RecordingMover mover = new RecordingMover();
    private ProjectManager manager;
    private Path directory;
    private byte[] original;

    @BeforeEach
    void openAProjectThatOwesItsMigrationBackup() throws IOException {
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        manager = SaveTestSupport.manager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true), writer),
                new ProjectLockManager(), new ProjectDeserializer(SaveTestSupport.forcedMigrationRegistry()), writer,
                ProjectManager.CHECKPOINT_WAIT);
        directory = SaveTestSupport.projectOnDisk(manager, "Legacy", tempDir);
        original = Files.readAllBytes(directory.resolve("project.daw"));
        manager.openProject(directory);
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("fixture: the open migrated").isTrue();
        mover.clear();
    }

    @AfterEach
    void releaseTheManager() {
        manager.abandonProject();
    }

    @Test
    void theOwedBackupIsPublishedWithoutReplacingBeforeTheHeadIsReplaced() throws IOException {
        saved(manager, manager.getCurrentDawProject());

        List<Publish> fileWrites = mover.publishes(CHECKPOINT.negate());
        assertThat(fileWrites).as("the backup, then the head").hasSize(2);
        assertThat(fileWrites.getFirst().name()).matches("project\\.daw\\.v\\d+\\.\\d{8}-\\d{6}\\.bak");
        assertThat(fileWrites.getFirst().options()).as("a move that never replaces (NEVER)").isEmpty();
        assertThat(fileWrites.getLast().name()).isEqualTo("project.daw");
        assertThat(fileWrites.getLast().options())
                .containsExactly(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertThat(Files.readAllBytes(fileWrites.getFirst().target())).as("the backup holds the original")
                .isEqualTo(original);
    }

    @Test
    void aFailedBackupFailsTheSaveWithTheProjectFileUntouchedAndTheBackupStillOwed() throws IOException {
        IOException refused = new IOException("injected failure of the backup's publish");
        mover.failWith(BACKUP, refused);
        DawProject project = manager.getCurrentDawProject();

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isSameAs(refused);
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).as("project.daw is untouched")
                .isEqualTo(original);
        assertThat(backups()).isEmpty();
        assertThat(tempsIn(directory)).as("no temp is left beside it").isEmpty();
        assertThat(mover.publishes(CHECKPOINT.negate())).as("the head was not written").isEmpty();

        mover.stopFailing();
        saved(manager, project);

        assertThat(backups()).as("the backup was still owed, and the next save took it").singleElement()
                .satisfies(backup -> assertThat(Files.readAllBytes(backup)).isEqualTo(original));
    }

    @Test
    void aCaptureThatCannotBeProducedTakesNoBackup() throws IOException {
        DawProject project = manager.getCurrentDawProject();
        UncapturableEffect effect = new UncapturableEffect();
        project.getTracks().getFirst().getMidiEffectChain().add(effect);

        CompletableFuture<SaveOutcome> pending = manager.save(project);

        assertThat(pending).as("refused at the call: nothing was queued").isDone();
        assertThat(await(pending)).isInstanceOfSatisfying(SaveOutcome.Failed.class, outcome -> {
            assertThat(outcome.cause()).isSameAs(effect.failure);
            assertThat(outcome.safetyCheckpoint()).isEmpty();
        });
        assertThat(mover.publishes()).as("nothing was written").isEmpty();
        assertThat(backups()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).isEqualTo(original);

        effect.disarm();
        saved(manager, project);

        assertThat(backups()).as("the backup was still owed").singleElement()
                .satisfies(backup -> assertThat(Files.readAllBytes(backup)).isEqualTo(original));
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(BACKUP).toList();
        }
    }
}
