package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RollBackUsesExistingControllerTest {

    @Test
    void migrationHistoryRollbackDelegatesToSelectedBackupOverload() throws Exception {
        Path source = sourceFile();
        String text = Files.readString(source);
        String showHistory = text.substring(
                text.indexOf("void showMigrationHistory(Path projectDir)"),
                text.indexOf("void rollbackMigration(Path projectDir, MigrationReport report)"));
        String selectedRollback = text.substring(
                text.indexOf("void rollbackMigration(Path projectDir, MigrationReport report, Path selectedBackup)"),
                text.indexOf("private void showMigrationDiff"));

        assertThat(showHistory)
                .contains("rollbackMigration(projectDir, reportFor(event.getEntry()), event.getBackupPath())");
        assertThat(selectedRollback)
                .contains("Files.copy(selectedBackup, projectFile")
                .doesNotContain("delete")
                .doesNotContain("deleteIfExists");
    }

    private static Path sourceFile() {
        Path moduleLocal = Path.of(
                "src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java");
        return Files.exists(moduleLocal)
                ? moduleLocal
                : Path.of("daw-app").resolve(moduleLocal);
    }
}
