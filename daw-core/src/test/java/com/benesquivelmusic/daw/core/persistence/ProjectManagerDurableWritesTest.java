package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProjectManager} writes the project file only through its
 * {@link AtomicFileWriter} (story 331, Persistence Integrity book §4.1): a
 * head write that fails before its publish leaves {@code project.daw} as it
 * was, with no temp beside it and the project still dirty, and an owed
 * sibling backup is a durable copy that never overwrites a backup another
 * writer published under the same name.
 */
class ProjectManagerDurableWritesTest {

    @TempDir
    Path tempDir;

    private ProjectManager manager;

    @AfterEach
    void releaseTheManager() {
        if (manager != null) {
            manager.abandonProject();
        }
    }

    @Test
    void aHeadWriteThatFailsBeforeItsPublishLeavesTheProjectFileAndNoTemp() throws IOException {
        IOException crash = new IOException("injected failure between the forced temp and the move");
        AtomicBoolean failing = new AtomicBoolean();
        manager = manager(MigrationRegistry.defaultRegistry(), (temp, target, options) -> {
            if (failing.get()) {
                throw crash;
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        });
        Path directory = manager.createProject("Song", tempDir).projectPath();
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        saved(manager, project);
        Path projectFile = directory.resolve("project.daw");
        byte[] saved = Files.readAllBytes(projectFile);

        project.createAudioTrack("Vox");
        project.markDirty();
        failing.set(true);

        assertThat(SaveTestSupport.failed(manager, project).cause()).isSameAs(crash);

        assertThat(Files.readAllBytes(projectFile)).as("the previous head, byte for byte").isEqualTo(saved);
        assertThat(project.isDirty()).as("a failed write never marks the project clean").isTrue();
        try (Stream<Path> entries = Files.list(directory)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .as("no temp beside the project file")
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void anOwedBackupNeverOverwritesABackupAnotherWriterPublishedUnderItsName() throws IOException {
        List<String> publishes = new CopyOnWriteArrayList<>();
        AtomicBoolean planting = new AtomicBoolean(true);
        manager = manager(forcedMigrationRegistry(), (temp, target, options) -> {
            String name = target.getFileName().toString();
            if (name.endsWith(".bak") && planting.getAndSet(false)) {
                Files.writeString(target, "another session's backup");
            }
            try {
                AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
            } catch (IOException refused) {
                publishes.add(name + " refused " + List.of(options));
                throw refused;
            }
            publishes.add(name + " " + List.of(options));
        });
        Path directory = manager.createProject("Legacy", tempDir).projectPath();
        Path projectFile = directory.resolve("project.daw");
        Files.writeString(projectFile, new ProjectSerializer()
                .serialize(new DawProject("Legacy", AudioFormat.CD_QUALITY)));
        byte[] original = Files.readAllBytes(projectFile);
        manager.abandonProject();
        manager.openProject(directory);
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("fixture: the open migrated").isTrue();
        publishes.clear();

        saved(manager, manager.getCurrentDawProject());

        List<Path> backups;
        try (Stream<Path> entries = Files.list(directory)) {
            backups = entries.filter(path -> path.getFileName().toString().endsWith(".bak")).toList();
        }
        assertThat(backups).hasSize(2);
        Path planted = backups.stream().filter(path -> !path.toString().endsWith("-1.bak")).findFirst().orElseThrow();
        Path ours = planted.resolveSibling(planted.getFileName().toString().replace(".bak", "-1.bak"));
        assertThat(planted).as("the other writer's backup is untouched").hasContent("another session's backup");
        assertThat(backups).as("ours took the next suffix").contains(ours);
        assertThat(Files.readAllBytes(ours)).as("ours holds the original bytes").isEqualTo(original);
        assertThat(publishes).as("the backup is published, never replacing, before the head is replaced")
                .containsExactly(
                        planted.getFileName() + " refused []",
                        ours.getFileName() + " []",
                        "project.daw [ATOMIC_MOVE, REPLACE_EXISTING]");
    }

    private static ProjectManager manager(MigrationRegistry registry, AtomicFileWriter.Mover mover) {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)),
                null, new ProjectLockManager(), new ProjectDeserializer(registry),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover));
    }

    /**
     * A registry one schema version above {@link MigrationRegistry#CURRENT_VERSION}
     * with no-op steps, so every open of a current file migrates and owes a
     * backup (the shape {@code ProjectManagerMigrationBackupTest} uses).
     */
    private static MigrationRegistry forcedMigrationRegistry() {
        int target = MigrationRegistry.CURRENT_VERSION + 1;
        MigrationRegistry.Builder builder = MigrationRegistry.builder(target);
        for (int version = 1; version < target; version++) {
            builder.add(ProjectMigration.step(version, "v" + version + "→v" + (version + 1), document -> document));
        }
        return builder.build();
    }
}
