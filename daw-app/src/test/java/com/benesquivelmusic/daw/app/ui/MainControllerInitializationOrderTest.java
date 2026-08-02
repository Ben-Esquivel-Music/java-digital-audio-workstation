package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins startup dependencies that must exist before their UI consumers. */
final class MainControllerInitializationOrderTest {

    @Test
    void notificationBarIsInitializedBeforeTempoEditor() throws IOException {
        Path sourceFile = Path.of("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);

        int initializeMethod = source.indexOf("private void initialize()");
        int notificationBarInitialization = source.indexOf(
                "initializeNotificationBar();", initializeMethod);
        int tempoEditorCreation = source.indexOf(
                "createTempoEditController();", initializeMethod);

        assertThat(initializeMethod).isNotNegative();
        assertThat(notificationBarInitialization).isGreaterThan(initializeMethod);
        assertThat(tempoEditorCreation).isGreaterThan(notificationBarInitialization);
    }
}
