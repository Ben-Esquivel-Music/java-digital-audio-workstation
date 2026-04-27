package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectManager} surfaces the migration report
 * after a load and that the very first save of a migrated project
 * snapshots the original file as a sibling backup — the rollback path
 * for users who don't want the in-memory migration committed.
 */
class ProjectManagerMigrationBackupTest {

    @TempDir
    Path tempDir;

    private ProjectManager createManager(MigrationRegistry registry) {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager checkpointManager = new CheckpointManager(config);
        return new ProjectManager(checkpointManager, null,
                new ProjectLockManager(),
                new ProjectDeserializer(registry));
    }

    @Test
    void noBackupWhenLoadDidNotMigrate() throws IOException {
        ProjectManager manager = createManager(MigrationRegistry.defaultRegistry());

        ProjectMetadata metadata = manager.createProject("Native", tempDir);
        Path projectFile = metadata.projectPath().resolve("project.daw");
        Files.writeString(projectFile, new ProjectSerializer()
                .serialize(new DawProject("Native", AudioFormat.CD_QUALITY)));
        manager.abandonProject();

        manager.openProject(metadata.projectPath());

        assertThat(manager.getLastMigrationReport().wasMigrated()).isFalse();

        DawProject loaded = manager.getCurrentDawProject();
        manager.saveDawProject(loaded);

        // No .bak files should exist because nothing was migrated.
        try (Stream<Path> entries = Files.list(metadata.projectPath())) {
            List<Path> backups = entries.filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .toList();
            assertThat(backups).isEmpty();
        }
        // And the on-disk file is still the current schema version.
        assertThat(Files.readString(projectFile)).contains(
                "version=\"" + MigrationRegistry.CURRENT_VERSION + "\"");
    }

    @Test
    void firstSaveAfterMigrationWritesBackupOfOriginalFile() throws Exception {
        // Build a registry that forces every load to "migrate" — even at
        // the current schema version we promote v1 to v2 and back-mark
        // v2 as current. The serializer still emits v1 (production
        // value), so any opened project triggers migration.
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "test-promote-v1-to-v2", d -> d))
                .build();
        ProjectManager manager = createManager(registry);

        // Bootstrap a project on disk using the production (v1) serializer.
        ProjectMetadata metadata = manager.createProject("Legacy", tempDir);
        Path projectFile = metadata.projectPath().resolve("project.daw");
        DawProject dawProject = new DawProject("Legacy", AudioFormat.CD_QUALITY);
        Files.writeString(projectFile, new ProjectSerializer().serialize(dawProject));
        String originalXml = Files.readString(projectFile);
        manager.abandonProject();

        // Re-open through the migrating manager — this should trigger the
        // synthetic migration above.
        manager.openProject(metadata.projectPath());
        MigrationReport report = manager.getLastMigrationReport();
        assertThat(report.wasMigrated()).isTrue();
        assertThat(report.applied())
                .extracting(MigrationReport.AppliedMigration::description)
                .containsExactly("test-promote-v1-to-v2");

        // Save: the manager must back up the pre-migration file before
        // overwriting it.
        DawProject loaded = manager.getCurrentDawProject();
        manager.saveDawProject(loaded);

        try (Stream<Path> entries = Files.list(metadata.projectPath())) {
            List<Path> backups = entries
                    .filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .toList();
            assertThat(backups)
                    .as("a backup of the original file must be written on first save after migration")
                    .hasSize(1);
            assertThat(backups.getFirst().getFileName().toString())
                    .startsWith("project.daw.v" + report.fromVersion() + ".");
            assertThat(Files.readString(backups.getFirst())).isEqualTo(originalXml);
        }

        // A second save must not write another backup — the rollback
        // snapshot is taken exactly once per load.
        manager.saveDawProject(loaded);
        try (Stream<Path> entries = Files.list(metadata.projectPath())) {
            long backupCount = entries
                    .filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .count();
            assertThat(backupCount).isEqualTo(1);
        }
    }

    @Test
    void abandonProjectLeavesOriginalFileUntouched() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "test-migrate", d -> d))
                .build();
        ProjectManager manager = createManager(registry);

        ProjectMetadata metadata = manager.createProject("Discard", tempDir);
        Path projectFile = metadata.projectPath().resolve("project.daw");
        DawProject dawProject = new DawProject("Discard", AudioFormat.CD_QUALITY);
        Files.writeString(projectFile, new ProjectSerializer().serialize(dawProject));
        String originalXml = Files.readString(projectFile);
        manager.abandonProject();

        manager.openProject(metadata.projectPath());
        assertThat(manager.getLastMigrationReport().wasMigrated()).isTrue();

        // User chose to discard — no save, so no state change on disk.
        manager.abandonProject();

        assertThat(Files.readString(projectFile)).isEqualTo(originalXml);
        try (Stream<Path> entries = Files.list(metadata.projectPath())) {
            assertThat(entries.filter(p -> p.getFileName().toString().endsWith(".bak")))
                    .isEmpty();
        }
    }
}
