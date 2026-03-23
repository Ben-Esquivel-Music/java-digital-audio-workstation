package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the browser/library panel toggle from the sidebar toolbar button.
 *
 * <p>Single-click on the toolbar "Library" button toggles the browser panel
 * on the right side of the main {@link BorderPane}. The panel slides in/out
 * with a smooth animation and the button receives the
 * {@code toolbar-button-active} CSS class when the panel is visible.</p>
 */
public final class BrowserPanelController {

    private static final Logger LOG = Logger.getLogger(BrowserPanelController.class.getName());
    private static final Duration ANIMATION_DURATION = Duration.millis(250);

    private final BrowserPanel browserPanel;
    private final Button browserButton;
    private final BorderPane rootPane;
    private boolean panelVisible;
    private Runnable onVisibilityChanged;

    /**
     * Creates a new controller wiring the browser panel to the toolbar button.
     *
     * @param browserPanel  the browser panel to toggle
     * @param browserButton the toolbar button that triggers the toggle
     * @param rootPane      the root {@link BorderPane} where the panel is placed
     */
    public BrowserPanelController(BrowserPanel browserPanel,
                                  Button browserButton,
                                  BorderPane rootPane) {
        if (browserPanel == null) {
            throw new NullPointerException("browserPanel must not be null");
        }
        if (browserButton == null) {
            throw new NullPointerException("browserButton must not be null");
        }
        if (rootPane == null) {
            throw new NullPointerException("rootPane must not be null");
        }
        this.browserPanel = browserPanel;
        this.browserButton = browserButton;
        this.rootPane = rootPane;
        this.panelVisible = false;
    }

    /**
     * Initializes the button handler. Must be called after UI construction.
     */
    public void initialize() {
        browserButton.setOnAction(event -> toggleBrowserPanel());
        updateButtonActiveState();
        LOG.fine("Browser panel controller initialized");
    }

    /**
     * Toggles the browser panel visibility on the right side of the root pane.
     */
    void toggleBrowserPanel() {
        panelVisible = !panelVisible;
        if (panelVisible) {
            showPanel();
        } else {
            hidePanel();
        }
        updateButtonActiveState();
        if (onVisibilityChanged != null) {
            onVisibilityChanged.run();
        }
        LOG.fine(() -> "Browser panel toggled: " + (panelVisible ? "visible" : "hidden"));
    }

    /**
     * Returns whether the browser panel is currently visible.
     *
     * @return {@code true} if the panel is visible
     */
    public boolean isPanelVisible() {
        return panelVisible;
    }

    /**
     * Sets a callback that is invoked after the panel visibility changes.
     *
     * @param callback the callback to invoke, or {@code null} to remove
     */
    public void setOnVisibilityChanged(Runnable callback) {
        this.onVisibilityChanged = callback;
    }

    /**
     * Returns the browser panel managed by this controller.
     *
     * @return the browser panel
     */
    public BrowserPanel getBrowserPanel() {
        return browserPanel;
    }

    private void showPanel() {
        browserPanel.setOpacity(0.0);
        rootPane.setRight(browserPanel);

        Timeline timeline = new Timeline(
                new KeyFrame(ANIMATION_DURATION,
                        new KeyValue(browserPanel.opacityProperty(), 1.0))
        );
        timeline.play();
    }

    private void hidePanel() {
        Timeline timeline = new Timeline(
                new KeyFrame(ANIMATION_DURATION,
                        new KeyValue(browserPanel.opacityProperty(), 0.0))
        );
        timeline.setOnFinished(event -> rootPane.setRight(null));
        timeline.play();
    }

    private void updateButtonActiveState() {
        List<String> styles = browserButton.getStyleClass();
        if (panelVisible) {
            if (!styles.contains("toolbar-button-active")) {
                styles.add("toolbar-button-active");
            }
        } else {
            styles.remove("toolbar-button-active");
        }
    }
}
