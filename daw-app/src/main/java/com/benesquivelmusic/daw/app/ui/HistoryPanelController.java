package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the undo history panel and notification history panel, including
 * build, rebuild, and animated toggle on the right side of the root pane.
 *
 * <p>Extracted from {@code MainController} to separate panel management
 * from the main coordinator.</p>
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
    private final NotificationHistoryService notificationHistoryService;

    private UndoHistoryPanel undoHistoryPanel;
    private NotificationHistoryPanel notificationHistoryPanel;
    private boolean historyPanelVisible;
    private boolean notificationHistoryPanelVisible;

    HistoryPanelController(BorderPane rootPane,
                           javafx.scene.control.Button historyButton,
                           NotificationHistoryService notificationHistoryService,
                           Host host) {
        this.rootPane = rootPane;
        this.historyButton = historyButton;
        this.notificationHistoryService = notificationHistoryService;
        this.host = host;
    }

    void build() {
        undoHistoryPanel = new UndoHistoryPanel(host.undoManager());
        notificationHistoryPanel = new NotificationHistoryPanel(notificationHistoryService);
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
        if (historyPanelVisible) {
            if (host.isBrowserPanelVisible()) {
                host.hideBrowserPanel();
            }
            if (notificationHistoryPanelVisible) {
                toggleNotificationHistoryPanel();
            }
            undoHistoryPanel.setOpacity(0.0);
            rootPane.setRight(undoHistoryPanel);
            new Timeline(new KeyFrame(Duration.millis(250),
                    new KeyValue(undoHistoryPanel.opacityProperty(), 1.0))).play();
            host.updateStatusBar("Undo History panel opened", DawIcon.HISTORY);
        } else {
            Timeline timeline = new Timeline(new KeyFrame(Duration.millis(250),
                    new KeyValue(undoHistoryPanel.opacityProperty(), 0.0)));
            timeline.setOnFinished(_ -> rootPane.setRight(null));
            timeline.play();
            host.updateStatusBar("Undo History panel closed", DawIcon.HISTORY);
        }
        updateHistoryButtonActiveState();
    }

    void toggleNotificationHistoryPanel() {
        notificationHistoryPanelVisible = !notificationHistoryPanelVisible;
        if (notificationHistoryPanelVisible) {
            if (host.isBrowserPanelVisible()) {
                host.hideBrowserPanel();
            }
            if (historyPanelVisible) {
                toggleHistoryPanel();
            }
            notificationHistoryPanel.setOpacity(0.0);
            rootPane.setRight(notificationHistoryPanel);
            new Timeline(new KeyFrame(Duration.millis(250),
                    new KeyValue(notificationHistoryPanel.opacityProperty(), 1.0))).play();
            host.updateStatusBar("Notification History panel opened", DawIcon.BELL_RING);
        } else {
            Timeline timeline = new Timeline(new KeyFrame(Duration.millis(250),
                    new KeyValue(notificationHistoryPanel.opacityProperty(), 0.0)));
            timeline.setOnFinished(_ -> rootPane.setRight(null));
            timeline.play();
            host.updateStatusBar("Notification History panel closed", DawIcon.BELL_RING);
        }
    }

    boolean isHistoryPanelVisible() {
        return historyPanelVisible;
    }

    boolean isNotificationHistoryPanelVisible() {
        return notificationHistoryPanelVisible;
    }

    void setHistoryPanelVisible(boolean visible) {
        this.historyPanelVisible = visible;
    }

    void setNotificationHistoryPanelVisible(boolean visible) {
        this.notificationHistoryPanelVisible = visible;
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
