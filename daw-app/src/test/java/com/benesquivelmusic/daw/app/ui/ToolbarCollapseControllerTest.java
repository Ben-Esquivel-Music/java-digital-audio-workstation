package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class ToolbarCollapseControllerTest {

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
        return Preferences.userRoot().node("toolbarCollapseTest_" + System.nanoTime());
    }

    // ── Constructor null checks ──────────────────────────────────────────────

    @Test
    void shouldRejectNullSidebarToolbar() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarCollapseController(
                    null, new Button(), freshPrefs()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullExpandCollapseButton() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarCollapseController(
                    new VBox(), null, freshPrefs()))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullPreferences() throws Exception {
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarCollapseController(
                    new VBox(), new Button(), null))
                    .isInstanceOf(NullPointerException.class);
        });
    }

    // ── Initial state ────────────────────────────────────────────────────────

    @Test
    void shouldStartExpandedByDefault() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(controller.isCollapsed()).isFalse();
        });
    }

    @Test
    void shouldSetExpandedWidthOnInitialize() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(sidebar.getPrefWidth())
                    .isEqualTo(ToolbarCollapseController.EXPANDED_WIDTH);
            assertThat(sidebar.getMinWidth())
                    .isEqualTo(ToolbarCollapseController.EXPANDED_WIDTH);
            assertThat(sidebar.getMaxWidth())
                    .isEqualTo(ToolbarCollapseController.EXPANDED_WIDTH);
        });
    }

    // ── Toggle behavior ──────────────────────────────────────────────────────

    @Test
    void shouldCollapseOnFirstToggle() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle();

            assertThat(controller.isCollapsed()).isTrue();
        });
    }

    @Test
    void shouldExpandOnSecondToggle() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle();
            controller.toggle();

            assertThat(controller.isCollapsed()).isFalse();
        });
    }

    // ── Button content display ───────────────────────────────────────────────

    @Test
    void shouldSetButtonsToGraphicOnlyWhenCollapsed() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button sidebarButton = new Button("Home");
            sidebar.getChildren().add(sidebarButton);
            Button toggleButton = new Button("Expand");
            sidebar.getChildren().add(toggleButton);

            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle();

            assertThat(sidebarButton.getContentDisplay())
                    .isEqualTo(ContentDisplay.GRAPHIC_ONLY);
        });
    }

    @Test
    void shouldSetButtonsToLeftWhenExpanded() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button sidebarButton = new Button("Home");
            sidebar.getChildren().add(sidebarButton);
            Button toggleButton = new Button("Expand");
            sidebar.getChildren().add(toggleButton);

            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(sidebarButton.getContentDisplay())
                    .isEqualTo(ContentDisplay.LEFT);
        });
    }

    // ── Section label visibility ─────────────────────────────────────────────

    @Test
    void shouldHideSectionLabelsWhenCollapsed() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Label sectionLabel = new Label("TOOLS");
            sectionLabel.getStyleClass().add("toolbar-section-label");
            sidebar.getChildren().add(sectionLabel);
            Button toggleButton = new Button("Expand");
            sidebar.getChildren().add(toggleButton);

            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle();

            assertThat(sectionLabel.isVisible()).isFalse();
            assertThat(sectionLabel.isManaged()).isFalse();
        });
    }

    @Test
    void shouldShowSectionLabelsWhenExpanded() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Label sectionLabel = new Label("TOOLS");
            sectionLabel.getStyleClass().add("toolbar-section-label");
            sidebar.getChildren().add(sectionLabel);
            Button toggleButton = new Button("Expand");
            sidebar.getChildren().add(toggleButton);

            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(sectionLabel.isVisible()).isTrue();
            assertThat(sectionLabel.isManaged()).isTrue();
        });
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    void shouldPersistCollapsedState() throws Exception {
        runOnFxThread(() -> {
            Preferences prefs = freshPrefs();
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, prefs);
            controller.initialize();

            controller.toggle(); // collapse

            assertThat(prefs.getBoolean(
                    ToolbarCollapseController.PREF_KEY_TOOLBAR_COLLAPSED, false))
                    .isTrue();
        });
    }

    @Test
    void shouldRestoreCollapsedStateFromPrefs() throws Exception {
        runOnFxThread(() -> {
            Preferences prefs = freshPrefs();
            prefs.putBoolean(ToolbarCollapseController.PREF_KEY_TOOLBAR_COLLAPSED, true);

            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, prefs);
            controller.initialize();

            assertThat(controller.isCollapsed()).isTrue();
        });
    }

    @Test
    void shouldRestoreExpandedStateFromPrefs() throws Exception {
        runOnFxThread(() -> {
            Preferences prefs = freshPrefs();
            prefs.putBoolean(ToolbarCollapseController.PREF_KEY_TOOLBAR_COLLAPSED, false);

            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, prefs);
            controller.initialize();

            assertThat(controller.isCollapsed()).isFalse();
        });
    }

    // ── Button wiring ────────────────────────────────────────────────────────

    @Test
    void shouldWireButtonOnAction() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(toggleButton.getOnAction()).isNotNull();
        });
    }

    // ── Button width in collapsed/expanded ───────────────────────────────────

    @Test
    void shouldSetCollapsedButtonWidth() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button sidebarButton = new Button("Home");
            sidebar.getChildren().add(sidebarButton);
            Button toggleButton = new Button("Expand");
            sidebar.getChildren().add(toggleButton);

            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle();

            assertThat(sidebarButton.getPrefWidth())
                    .isEqualTo(ToolbarCollapseController.COLLAPSED_BUTTON_WIDTH);
        });
    }

    @Test
    void shouldSetExpandedButtonWidth() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button sidebarButton = new Button("Home");
            sidebar.getChildren().add(sidebarButton);
            Button toggleButton = new Button("Expand");
            sidebar.getChildren().add(toggleButton);

            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(sidebarButton.getPrefWidth())
                    .isEqualTo(ToolbarCollapseController.EXPANDED_BUTTON_WIDTH);
        });
    }

    // ── Toggle button text updates ───────────────────────────────────────────

    @Test
    void shouldShowExpandTextWhenCollapsed() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle(); // collapse

            assertThat(toggleButton.getText()).isEqualTo("Expand");
        });
    }

    @Test
    void shouldShowCollapseTextWhenExpanded() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(toggleButton.getText()).isEqualTo("Collapse");
        });
    }

    // ── Collapsed width on sidebar ───────────────────────────────────────────

    @Test
    void shouldSetCollapsedWidthOnSidebar() throws Exception {
        runOnFxThread(() -> {
            Preferences prefs = freshPrefs();
            prefs.putBoolean(ToolbarCollapseController.PREF_KEY_TOOLBAR_COLLAPSED, true);

            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, prefs);
            controller.initialize();

            assertThat(sidebar.getPrefWidth())
                    .isEqualTo(ToolbarCollapseController.COLLAPSED_WIDTH);
        });
    }

    // ── setCollapsed ─────────────────────────────────────────────────────────

    @Test
    void setCollapsedTrueShouldCollapseWhenExpanded() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            assertThat(controller.isCollapsed()).isFalse();
            controller.setCollapsed(true);
            assertThat(controller.isCollapsed()).isTrue();
        });
    }

    @Test
    void setCollapsedFalseShouldExpandWhenCollapsed() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            controller.toggle(); // collapse
            assertThat(controller.isCollapsed()).isTrue();

            controller.setCollapsed(false);
            assertThat(controller.isCollapsed()).isFalse();
        });
    }

    @Test
    void setCollapsedShouldNoOpWhenAlreadyInRequestedState() throws Exception {
        runOnFxThread(() -> {
            VBox sidebar = new VBox();
            Button toggleButton = new Button("Expand");
            ToolbarCollapseController controller =
                    new ToolbarCollapseController(sidebar, toggleButton, freshPrefs());
            controller.initialize();

            // Already expanded — setCollapsed(false) should be a no-op
            assertThat(controller.isCollapsed()).isFalse();
            controller.setCollapsed(false);
            assertThat(controller.isCollapsed()).isFalse();

            // Collapse, then setCollapsed(true) should be a no-op
            controller.setCollapsed(true);
            assertThat(controller.isCollapsed()).isTrue();
            controller.setCollapsed(true);
            assertThat(controller.isCollapsed()).isTrue();
        });
    }
}
