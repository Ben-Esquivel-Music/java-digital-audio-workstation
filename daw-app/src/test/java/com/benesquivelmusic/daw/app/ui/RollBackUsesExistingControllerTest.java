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
        // Story 331: the selected backup replaces project.daw through the atomic writer, following a
        // project.daw that is a symbolic link to its .daw file (ruling M3).
        assertThat(selectedRollback.replaceAll("\\s+", " "))
                .contains("fileWriter.copy(selectedBackup, projectFile, AtomicFileWriter.Overwrite.REPLACE,"
                        + " AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE)")
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
