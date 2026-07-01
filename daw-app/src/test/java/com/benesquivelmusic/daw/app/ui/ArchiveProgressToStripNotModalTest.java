package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveProgressToStripNotModalTest {

    @Test
    void archiveProjectRegistersStripOperationWithoutProgressModal() throws Exception {
        Path source = sourceFile();
        String text = Files.readString(source);
        String method = text.substring(
                text.indexOf("void onArchiveProject()"),
                text.indexOf("void onRestoreFromArchive()"));

        assertThat(method).contains("progress.addOperation(archiveOp)");
        assertThat(method).contains("progress.removeOperation(archiveOp)");
        assertThat(method).doesNotContain("new TaskProgressIndicator");
        assertThat(method).doesNotContain(".showAndWait()");
    }

    private static Path sourceFile() {
        Path moduleLocal = Path.of(
                "src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java");
        return Files.exists(moduleLocal)
                ? moduleLocal
                : Path.of("daw-app").resolve(moduleLocal);
    }
}
