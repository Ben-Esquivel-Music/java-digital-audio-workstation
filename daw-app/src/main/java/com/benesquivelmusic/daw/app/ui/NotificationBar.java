package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * A toast-style notification bar that displays brief, auto-dismissing messages
 * above the status bar.
 *
 * <p>Notifications are visually distinguished by {@link NotificationLevel}:
 * green for success, blue for info, orange for warnings, and red for errors.
 * Destructive operations can include an "Undo" action link.</p>
 *
 * <p>Usage from {@code MainController}:</p>
 * <pre>{@code
 *   notificationBar.show(NotificationLevel.SUCCESS, "Track added: Audio 1");
 *   notificationBar.show(NotificationLevel.ERROR, "Save failed: disk full");
 *   notificationBar.showWithUndo(NotificationLevel.SUCCESS, "Removed track: Audio 1", this::onUndo);
 * }</pre>
 */
public final class NotificationBar extends HBox {

    private static final double ICON_SIZE = 14;
    private static final double FADE_DURATION_MS = 200;

    private final Label iconLabel = new Label();
    private final Label messageLabel = new Label();
    private final Hyperlink undoLink = new Hyperlink("Undo");
    private final Label dismissButton = new Label("✕");

    private PauseTransition autoDismissTimer;
    private NotificationLevel currentLevel;
    private NotificationHistoryService historyService;
    private long autoDismissOverrideMillis = -1;

    public NotificationBar() {
        getStyleClass().add("notification-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        setPadding(new Insets(6, 16, 6, 16));
        setManaged(false);
        setVisible(false);

        iconLabel.getStyleClass().add("notification-icon");
        messageLabel.getStyleClass().add("notification-message");
        undoLink.getStyleClass().add("notification-undo-link");
        undoLink.setVisible(false);
        undoLink.setManaged(false);

        dismissButton.getStyleClass().add("notification-dismiss");
        dismissButton.setOnMouseClicked(_ -> dismiss());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(iconLabel, messageLabel, undoLink, spacer, dismissButton);
    }

    /**
     * Shows a notification with the given level and message.
     *
     * @param level   the notification severity level
     * @param message the message to display
     */
    public void show(NotificationLevel level, String message) {
        showInternal(level, message, null);
    }

    /**
     * Shows a notification with an "Undo" action link for destructive operations.
     *
     * @param level      the notification severity level
     * @param message    the message to display
     * @param undoAction the action to invoke when "Undo" is clicked
     */
    public void showWithUndo(NotificationLevel level, String message, Runnable undoAction) {
        showInternal(level, message, undoAction);
    }

    /**
     * Immediately dismisses the current notification with a fade-out animation.
     */
    public void dismiss() {
        if (autoDismissTimer != null) {
            autoDismissTimer.stop();
            autoDismissTimer = null;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(FADE_DURATION_MS), this);
        fadeOut.setFromValue(getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(_ -> {
            setVisible(false);
            setManaged(false);
            clearLevelStyle();
        });
        fadeOut.play();
    }

    /**
     * Returns the currently displayed notification level, or {@code null} if hidden.
     */
    public NotificationLevel getCurrentLevel() {
        return isVisible() ? currentLevel : null;
    }

    /**
     * Returns the currently displayed message text.
     */
    public String getMessage() {
        return messageLabel.getText();
    }

    /**
     * Sets the notification history service used to record warning and error
     * notifications for the history panel.
     *
     * @param historyService the history service, or {@code null} to disable recording
     */
    public void setHistoryService(NotificationHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * Returns the notification history service, or {@code null} if none is set.
     */
    public NotificationHistoryService getHistoryService() {
        return historyService;
    }

    /**
     * Sets a global auto-dismiss timeout override in milliseconds.
     * When set to a positive value, this overrides the per-level default
     * durations. Set to {@code -1} to revert to per-level defaults.
     *
     * @param millis the auto-dismiss timeout in milliseconds, or -1 for per-level defaults
     */
    public void setAutoDismissMillis(long millis) {
        if (millis <= 0 && millis != -1) {
            throw new IllegalArgumentException("millis must be positive or -1");
        }
        this.autoDismissOverrideMillis = millis;
    }

    /**
     * Returns the auto-dismiss timeout override, or {@code -1} if per-level defaults are used.
     */
    public long getAutoDismissMillis() {
        return autoDismissOverrideMillis;
    }

    private void showInternal(NotificationLevel level, String message, Runnable undoAction) {
        // Record to history service (warnings and errors only)
        if (historyService != null) {
            historyService.record(level, message);
        }

        // Cancel any pending auto-dismiss
        if (autoDismissTimer != null) {
            autoDismissTimer.stop();
        }

        // Update style
        clearLevelStyle();
        currentLevel = level;
        getStyleClass().add(level.styleClass());

        // Update content
        iconLabel.setGraphic(IconNode.of(level.icon(), ICON_SIZE));
        messageLabel.setText(message);

        // Undo link
        if (undoAction != null) {
            undoLink.setVisible(true);
            undoLink.setManaged(true);
            undoLink.setOnAction(_ -> {
                undoAction.run();
                dismiss();
            });
        } else {
            undoLink.setVisible(false);
            undoLink.setManaged(false);
            undoLink.setOnAction(null);
        }

        // Show with fade-in
        setVisible(true);
        setManaged(true);
        setOpacity(0.0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(FADE_DURATION_MS), this);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        // Schedule auto-dismiss
        long dismissMillis = autoDismissOverrideMillis > 0
                ? autoDismissOverrideMillis
                : level.autoDismissMillis();
        autoDismissTimer = new PauseTransition(Duration.millis(dismissMillis));
        autoDismissTimer.setOnFinished(_ -> dismiss());
        autoDismissTimer.play();
    }

    private void clearLevelStyle() {
        for (NotificationLevel level : NotificationLevel.values()) {
            getStyleClass().remove(level.styleClass());
        }
    }
}
