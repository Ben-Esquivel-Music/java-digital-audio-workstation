package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserPanelControllerTest {

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

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNullBrowserPanel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new BrowserPanelController(
                    null, new Button(), new BorderPane()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullButton() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new BrowserPanelController(
                    new BrowserPanel(), null, new BorderPane()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullRootPane() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new BrowserPanelController(
                    new BrowserPanel(), new Button(), null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldStartWithPanelHidden() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            assertThat(controller.isPanelVisible()).isFalse();
            assertThat(rootPane.getRight()).isNull();
        });
    }

    @Test
    void shouldTogglePanelVisible() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // Toggle on
            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isTrue();
            assertThat(rootPane.getRight()).isSameAs(panel);
        });
    }

    @Test
    void shouldTogglePanelBackToHidden() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // Toggle on then off
            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isTrue();

            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isFalse();
        });
    }

    @Test
    void shouldAddActiveStyleWhenPanelVisible() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            controller.toggleBrowserPanel();
            assertThat(button.getStyleClass()).contains("toolbar-button-active");
        });
    }

    @Test
    void shouldRemoveActiveStyleWhenPanelHidden() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            assertThat(button.getStyleClass()).doesNotContain("toolbar-button-active");
        });
    }

    @Test
    void shouldWireButtonOnAction() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            assertThat(button.getOnAction()).isNotNull();
        });
    }

    @Test
    void shouldReturnBrowserPanel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);

            assertThat(controller.getBrowserPanel()).isSameAs(panel);
        });
    }
}
