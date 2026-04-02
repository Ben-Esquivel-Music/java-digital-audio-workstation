package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class ResponsiveToolbarControllerTest {

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

    private Preferences freshPrefs() {
        return Preferences.userRoot().node("responsiveToolbarTest_" + System.nanoTime());
    }

    // ── Constructor null checks ──────────────────────────────────────────────

    @Test
    void shouldRejectNullCollapseController() {
        assertThatThrownBy(() -> new ResponsiveToolbarController(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCollapseControllerWithThreshold() {
        assertThatThrownBy(() -> new ResponsiveToolbarController(null, 1600.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectZeroThreshold() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            assertThatThrownBy(() -> new ResponsiveToolbarController(collapse, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void shouldRejectNegativeThreshold() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            assertThatThrownBy(() -> new ResponsiveToolbarController(collapse, -100))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    // ── Default threshold ────────────────────────────────────────────────────

    @Test
    void shouldUseDefaultCollapseThreshold() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse);
            assertThat(responsive.getCollapseThreshold())
                    .isEqualTo(ResponsiveToolbarController.DEFAULT_COLLAPSE_THRESHOLD);
        });
    }

    @Test
    void shouldUseCustomCollapseThreshold() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse, 1400.0);
            assertThat(responsive.getCollapseThreshold()).isEqualTo(1400.0);
        });
    }

    // ── Attach null check ────────────────────────────────────────────────────

    @Test
    void shouldRejectNullSceneOnAttach() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse);
            assertThatThrownBy(() -> responsive.attach(null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullSceneOnDetach() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse);
            assertThatThrownBy(() -> responsive.detach(null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    // ── Auto-collapse at narrow width ────────────────────────────────────────

    @Test
    void shouldCollapseWhenAttachedToNarrowScene() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            Preferences prefs = freshPrefs();
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(sidebar, toggleButton, prefs);
            collapse.initialize();

            // Scene narrower than threshold → should collapse
            Scene scene = new Scene(new Group(), 1280, 720);
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse, 1600.0);
            responsive.attach(scene);

            assertThat(collapse.isCollapsed()).isTrue();
        });
    }

    // ── Auto-expand at wide width ────────────────────────────────────────────

    @Test
    void shouldExpandWhenAttachedToWideScene() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            Preferences prefs = freshPrefs();
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(sidebar, toggleButton, prefs);
            collapse.initialize();

            // Manually collapse first
            collapse.toggle();
            assertThat(collapse.isCollapsed()).isTrue();

            // Scene wider than threshold → should expand
            Scene scene = new Scene(new Group(), 1920, 1080);
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse, 1600.0);
            responsive.attach(scene);

            assertThat(collapse.isCollapsed()).isFalse();
        });
    }

    // ── Exact threshold boundary ─────────────────────────────────────────────

    @Test
    void shouldCollapseAtExactThreshold() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            collapse.initialize();

            // Scene width exactly at threshold → should collapse
            Scene scene = new Scene(new Group(), 1600, 900);
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse, 1600.0);
            responsive.attach(scene);

            assertThat(collapse.isCollapsed()).isTrue();
        });
    }

    @Test
    void shouldExpandJustAboveThreshold() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            collapse.initialize();

            // Collapse first
            collapse.toggle();
            assertThat(collapse.isCollapsed()).isTrue();

            // Scene width just above threshold → should expand
            Scene scene = new Scene(new Group(), 1601, 900);
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse, 1600.0);
            responsive.attach(scene);

            assertThat(collapse.isCollapsed()).isFalse();
        });
    }

    // ── Detach stops responding ──────────────────────────────────────────────

    @Test
    void shouldNotFailOnDetachWithoutAttach() throws Exception {
        runOnFxThread(() -> {
            ToolbarCollapseController collapse =
                    new ToolbarCollapseController(new VBox(), new Button(), freshPrefs());
            ResponsiveToolbarController responsive =
                    new ResponsiveToolbarController(collapse);

            Scene scene = new Scene(new Group(), 1920, 1080);
            // detach without prior attach should not throw
            responsive.detach(scene);
        });
    }
}
