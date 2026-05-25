package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the undo history panel: build, rebuild, and animated toggle on
 * the right side of the root pane.
 *
 * <p>Extracted from {@code MainController} to separate panel management
 * from the main coordinator. The notification-history surface is no
 * longer a standalone panel here — story 273 folded it into the
 * inspector drawer's Notifications section, so this controller only owns
 * the undo-history panel now.</p>
 */
final class HistoryPanelController {

    private static final Logger LOG = Logger.getLogger(HistoryPanelController.class.getName());

    interface Host {
        UndoManager undoManager();
        void updateUndoRedoState();
        void refreshArrangementCanvas();
        boolean isBrowserPanelVisible();
        void hideBrowserPanel();
        void updateStatusBar(String text, DawIcon icon);
    }

    private final BorderPane rootPane;
    private final javafx.scene.control.Button historyButton;
    private final Host host;

    private UndoHistoryPanel undoHistoryPanel;
    private boolean historyPanelVisible;

    HistoryPanelController(BorderPane rootPane,
                           javafx.scene.control.Button historyButton,
                           Host host) {
        this.rootPane = rootPane;
        this.historyButton = historyButton;
        this.host = host;
    }

    void build() {
        undoHistoryPanel = new UndoHistoryPanel(host.undoManager());
        historyButton.setOnAction(_ -> toggleHistoryPanel());
        LOG.fine("Built undo history panel");
    }

    void rebuild() {
        if (undoHistoryPanel != null) {
            undoHistoryPanel.dispose();
        }
        host.undoManager().addHistoryListener(_ -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                host.updateUndoRedoState();
                host.refreshArrangementCanvas();
            } else {
                javafx.application.Platform.runLater(() -> {
                    host.updateUndoRedoState();
                    host.refreshArrangementCanvas();
                });
            }
        });
        undoHistoryPanel = new UndoHistoryPanel(host.undoManager());
        if (historyPanelVisible) {
            rootPane.setRight(undoHistoryPanel);
        }
    }

    void toggleHistoryPanel() {
        historyPanelVisible = !historyPanelVisible;
        // Panel show/hide is transitional motion (UI Design Book §3.5) —
        // gated by global Reduce Motion (story 279). With Reduce Motion on
        // the panel is shown/removed at once with no opacity fade.
        boolean animate = MotionManager.getDefault().isAnimationAllowed();
        if (historyPanelVisible) {
            if (host.isBrowserPanelVisible()) {
                host.hideBrowserPanel();
            }
            rootPane.setRight(undoHistoryPanel);
            if (animate) {
                undoHistoryPanel.setOpacity(0.0);
                new Timeline(new KeyFrame(Duration.millis(250),
                        new KeyValue(undoHistoryPanel.opacityProperty(), 1.0))).play();
            } else {
                undoHistoryPanel.setOpacity(1.0);
            }
            host.updateStatusBar("Undo History panel opened", DawIcon.HISTORY);
        } else {
            if (animate) {
                Timeline timeline = new Timeline(new KeyFrame(Duration.millis(250),
                        new KeyValue(undoHistoryPanel.opacityProperty(), 0.0)));
                // Guard: only clear the right slot if WE still own it. A sibling
                // right-pane (e.g. BrowserPanel) may have taken it over while
                // our fade was in flight; clearing unconditionally would wipe it.
                timeline.setOnFinished(_ -> {
                    if (rootPane.getRight() == undoHistoryPanel) {
                        rootPane.setRight(null);
                    }
                });
                timeline.play();
            } else {
                rootPane.setRight(null);
            }
            host.updateStatusBar("Undo History panel closed", DawIcon.HISTORY);
        }
        updateHistoryButtonActiveState();
    }

    boolean isHistoryPanelVisible() {
        return historyPanelVisible;
    }

    void setHistoryPanelVisible(boolean visible) {
        this.historyPanelVisible = visible;
        updateHistoryButtonActiveState();
    }

    private void updateHistoryButtonActiveState() {
        List<String> styles = historyButton.getStyleClass();
        if (historyPanelVisible) {
            if (!styles.contains("toolbar-button-active")) {
                styles.add("toolbar-button-active");
            }
        } else {
            styles.remove("toolbar-button-active");
        }
    }
}
