package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DarkThemeHelperTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private <T> T runOnFxThread(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (error.get() != null) {
            throw error.get();
        }
        return ref.get();
    }

    @Test
    void stylesheetUrlShouldNotBeNull() {
        String url = DarkThemeHelper.getStylesheetUrl();
        assertThat(url).isNotNull();
        assertThat(url).contains("styles.css");
    }

    @Test
    void stylesheetUrlShouldBeConsistentAcrossCalls() {
        String url1 = DarkThemeHelper.getStylesheetUrl();
        String url2 = DarkThemeHelper.getStylesheetUrl();
        assertThat(url1).isEqualTo(url2);
    }

    @Test
    void applyToDialogShouldAddStylesheet() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        boolean hasStylesheet = runOnFxThread(() -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            DarkThemeHelper.applyTo(dialog);
            DialogPane pane = dialog.getDialogPane();
            return pane.getStylesheets().stream()
                    .anyMatch(s -> s.contains("styles.css"));
        });
        assertThat(hasStylesheet).isTrue();
    }

    @Test
    void applyToDialogShouldAddRootPaneStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        boolean hasRootPaneClass = runOnFxThread(() -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            DarkThemeHelper.applyTo(dialog);
            return dialog.getDialogPane().getStyleClass().contains("root-pane");
        });
        assertThat(hasRootPaneClass).isTrue();
    }

    @Test
    void applyToDialogShouldBeIdempotent() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        int stylesheetCount = runOnFxThread(() -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            DarkThemeHelper.applyTo(dialog);
            DarkThemeHelper.applyTo(dialog);
            DarkThemeHelper.applyTo(dialog);
            return (int) dialog.getDialogPane().getStylesheets().stream()
                    .filter(s -> s.contains("styles.css"))
                    .count();
        });
        assertThat(stylesheetCount).isEqualTo(1);
    }

    @Test
    void applyToSceneShouldAddStylesheet() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        boolean hasStylesheet = runOnFxThread(() -> {
            Scene scene = new Scene(new StackPane(), 100, 100);
            DarkThemeHelper.applyTo(scene);
            return scene.getStylesheets().stream()
                    .anyMatch(s -> s.contains("styles.css"));
        });
        assertThat(hasStylesheet).isTrue();
    }

    @Test
    void applyToSceneShouldBeIdempotent() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        int stylesheetCount = runOnFxThread(() -> {
            Scene scene = new Scene(new StackPane(), 100, 100);
            DarkThemeHelper.applyTo(scene);
            DarkThemeHelper.applyTo(scene);
            return (int) scene.getStylesheets().stream()
                    .filter(s -> s.contains("styles.css"))
                    .count();
        });
        assertThat(stylesheetCount).isEqualTo(1);
    }

    @Test
    void helpDialogShouldHaveDarkThemeStylesheet() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        boolean hasStylesheet = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            return dialog.getDialogPane().getStylesheets().stream()
                    .anyMatch(s -> s.contains("styles.css"));
        });
        assertThat(hasStylesheet).isTrue();
    }

    @Test
    void helpDialogShouldHaveRootPaneStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        boolean hasRootPaneClass = runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            return dialog.getDialogPane().getStyleClass().contains("root-pane");
        });
        assertThat(hasRootPaneClass).isTrue();
    }
}
