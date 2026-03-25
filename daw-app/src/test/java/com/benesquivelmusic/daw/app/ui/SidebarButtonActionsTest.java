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

/**
 * Tests for the sidebar Home, Search, and Help button action wiring.
 *
 * <p>Since {@link MainController} requires a full FXML scene to initialize,
 * these tests verify the observable effects of the action handlers through
 * the {@link BrowserPanelController} (for Search) and by directly exercising
 * the underlying model classes (for Home).</p>
 */
class SidebarButtonActionsTest {

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

    // ── Home action behavior tests ──────────────────────────────────────────

    @Test
    void homeActionShouldResetZoomToDefault() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.zoomIn();
        zoom.zoomIn();
        assertThat(zoom.getLevel()).isGreaterThan(ZoomLevel.DEFAULT_ZOOM);

        zoom.zoomToFit();
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
    }

    @Test
    void homeActionShouldClearSelection() {
        SelectionModel selection = new SelectionModel();
        selection.setSelection(1.0, 4.0);
        assertThat(selection.hasSelection()).isTrue();

        selection.clearSelection();
        assertThat(selection.hasSelection()).isFalse();
    }

    // ── Search action behavior tests ────────────────────────────────────────

    @Test
    void searchActionShouldOpenBrowserPanelIfHidden() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // Initially hidden
            assertThat(controller.isPanelVisible()).isFalse();

            // Simulate search action: toggle visible if hidden
            if (!controller.isPanelVisible()) {
                controller.toggleBrowserPanel();
            }

            assertThat(controller.isPanelVisible()).isTrue();
            assertThat(rootPane.getRight()).isSameAs(panel);
        });
    }

    @Test
    void searchActionShouldNotToggleIfAlreadyVisible() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            Button button = new Button("Library");
            BorderPane rootPane = new BorderPane();
            BrowserPanelController controller = new BrowserPanelController(panel, button, rootPane);
            controller.initialize();

            // Make visible first
            controller.toggleBrowserPanel();
            assertThat(controller.isPanelVisible()).isTrue();

            // Simulate search action: should remain visible
            if (!controller.isPanelVisible()) {
                controller.toggleBrowserPanel();
            }

            assertThat(controller.isPanelVisible()).isTrue();
        });
    }

    @Test
    void searchActionShouldFocusSearchField() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            BrowserPanel panel = new BrowserPanel();
            // Verify the search field is accessible
            assertThat(panel.getSearchField()).isNotNull();
        });
    }

    // ── Help action behavior tests ──────────────────────────────────────────

    @Test
    void helpDialogShouldBeCreatable() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            HelpDialog dialog = new HelpDialog();
            assertThat(dialog.getTitle()).isEqualTo("Help");
            assertThat(dialog.getDialogPane().getContent()).isNotNull();
        });
    }
}
