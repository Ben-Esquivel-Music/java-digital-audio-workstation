package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class MigrationHistoryViewTest {

    private <T> T onFx(Callable<T> c) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(c.call());
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) {
            throw err.get();
        }
        return ref.get();
    }

    @Test
    void scanProjectDirectoryListsBackupsInTimelineOrderAndInfersTargets(
            @TempDir Path projectDir) throws Exception {
        Path first = writeBackup(projectDir, "project.daw.v1.20260620-010203.bak");
        Path second = writeBackup(projectDir, "project.daw.v3.20260621-010203.bak");
        Path collision = writeBackup(projectDir, "project.daw.v3.20260621-010203-1.bak");
        Files.writeString(projectDir.resolve("project.daw.vx.20260621-010203.bak"), "ignore");
        Files.writeString(projectDir.resolve("notes.txt"), "ignore");

        MigrationRegistry registry = MigrationRegistry.builder(4)
                .add(ProjectMigration.step(1, "rename pan-law attribute", doc -> doc))
                .add(ProjectMigration.step(2, "add bed-bus channel-gains", doc -> doc))
                .add(ProjectMigration.step(3, "persist clip color tags", doc -> doc))
                .build();

        List<MigrationHistoryView.MigrationHistoryEntry> entries =
                MigrationHistoryView.scanProjectDirectory(projectDir, registry);

        assertThat(entries).extracting(MigrationHistoryView.MigrationHistoryEntry::backupPath)
                .containsExactly(first, second, collision);
        assertThat(entries.get(0).fromSchemaVersion()).isEqualTo(1);
        assertThat(entries.get(0).toSchemaVersion()).isEqualTo(3);
        assertThat(entries.get(0).timestamp())
                .isEqualTo(Instant.parse("2026-06-20T01:02:03Z"));
        assertThat(entries.get(0).triggerText()).isEqualTo("Auto on open");
        assertThat(entries.get(0).bullets())
                .anySatisfy(b -> assertThat(b).contains("rename pan-law attribute"))
                .anySatisfy(b -> assertThat(b).contains("add bed-bus channel-gains"));
        assertThat(entries.get(1).toSchemaVersion()).isEqualTo(4);
        assertThat(entries.get(2).toSchemaVersion()).isEqualTo(4);
    }

    @Test
    void viewStoresEntriesAndFiresTypedActionEvents(@TempDir Path projectDir)
            throws Exception {
        Path backup = projectDir.resolve("project.daw.v1.20260620-010203.bak");
        MigrationHistoryView.MigrationHistoryEntry entry =
                new MigrationHistoryView.MigrationHistoryEntry(
                        1,
                        2,
                        Instant.parse("2026-06-20T01:02:03Z"),
                        "Auto on open",
                        backup,
                        List.of("v1 -> v2 - test migration"));

        AtomicReference<MigrationHistoryView.MigrationHistoryEvent> diff =
                new AtomicReference<>();
        AtomicReference<MigrationHistoryView.MigrationHistoryEvent> rollback =
                new AtomicReference<>();

        onFx(() -> {
            MigrationHistoryView view = new MigrationHistoryView(List.of(entry));
            view.setOnDiffRequested(diff::set);
            view.setOnRollbackRequested(rollback::set);

            assertThat(view.getEntries()).containsExactly(entry);
            assertThat(view.getListView().getSelectionModel().getSelectedItem())
                    .isEqualTo(entry);

            view.requestDiff(entry);
            view.requestRollback(entry);
            return null;
        });

        assertThat(diff.get()).isNotNull();
        assertThat(diff.get().getEventType()).isEqualTo(MigrationHistoryView.DIFF_REQUESTED);
        assertThat(diff.get().getEntry()).isEqualTo(entry);
        assertThat(diff.get().getBackupPath()).isEqualTo(backup);

        assertThat(rollback.get()).isNotNull();
        assertThat(rollback.get().getEventType())
                .isEqualTo(MigrationHistoryView.ROLLBACK_REQUESTED);
        assertThat(rollback.get().getEntry()).isEqualTo(entry);
        assertThat(rollback.get().getBackupPath()).isEqualTo(backup);
    }

    private static Path writeBackup(Path projectDir, String fileName) throws Exception {
        Path path = projectDir.resolve(fileName);
        Files.writeString(path, "<daw-project/>");
        return path;
    }
}
