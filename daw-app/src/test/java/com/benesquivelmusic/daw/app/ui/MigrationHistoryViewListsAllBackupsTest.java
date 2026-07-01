package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationHistoryViewListsAllBackupsTest {

    @Test
    void listsEveryMigrationBackupInTimelineOrder(@TempDir Path projectDir)
            throws Exception {
        Path first = backup(projectDir, "project.daw.v1.20260620-010203.bak");
        Path second = backup(projectDir, "project.daw.v2.20260620-020304.bak");
        Path third = backup(projectDir, "project.daw.v3.20260620-030405.bak");

        assertThat(MigrationHistoryView.scanProjectDirectory(projectDir, 4))
                .extracting(MigrationHistoryView.MigrationHistoryEntry::backupPath)
                .containsExactly(first, second, third);
    }

    private static Path backup(Path projectDir, String fileName) throws Exception {
        Path path = projectDir.resolve(fileName);
        Files.writeString(path, "<project/>");
        return path;
    }
}
