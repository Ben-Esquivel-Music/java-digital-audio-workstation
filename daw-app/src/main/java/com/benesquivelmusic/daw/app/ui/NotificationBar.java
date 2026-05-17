package com.benesquivelmusic.daw.app.ui;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Optional;

/**
 * The transient toast-style notification bar (UI Design Book §5.10,
 * §7.3, story 273).
 *
 * <p>A pill (not a fully-coloured banner): {@code -surface-1}
 * background, {@code -radius-2} corners, 32 px tall, with a 4 px left
 * bar in the level's semantic colour drawn as a {@link
 * javafx.scene.shape.Rectangle} child (per §7.3 — not a CSS border).
 * The visual is the shared {@link NotificationPill}; this class adds the
 * transient behaviour: fade in / out, flat 5 s auto-dismiss per §5.10,
 * and the trailing dismiss affordance.</p>
 *
 * <p>Every shown notification — all levels, with any action+label — is
 * recorded into the {@link NotificationHistoryService}, the single log
 * that also feeds the inspector Notifications section (§7.8 — exactly
 * one notification stream feeding both surfaces).</p>
 *
 * <p>Usage from {@code MainController}:</p>
 * <pre>{@code
 *   notificationBar.show(NotificationLevel.SUCCESS, "Track added: Audio 1");
 *   notificationBar.show(NotificationLevel.ERROR, "Save failed: disk full");
 *   notificationBar.showWithUndo(NotificationLevel.SUCCESS, "Removed track", this::onUndo);
 * }</pre>
 */
public final class NotificationBar extends StackPane {

    /** Flat auto-dismiss for all levels per §5.10. */
    static final long DEFAULT_AUTO_DISMISS_MS = 5_000;

    private static final double FADE_DURATION_MS = 200;

    private final NotificationPill pill = new NotificationPill(true);

    /** Reduce-motion opt-out (story 279 binds this later — mirrors InspectorDrawer). */
    private final BooleanProperty animated =
            new SimpleBooleanProperty(this, "animated", true);

    private Timeline dismissTimeline;
    private FadeTransition dismissFade;
    private NotificationLevel currentLevel;
    private NotificationHistoryService historyService;
    private long autoDismissOverrideMillis = -1;

    public NotificationBar() {
        getStyleClass().add("notification-bar");
        setAccessibleRole(AccessibleRole.NODE);
        setManaged(false);
        setVisible(false);

        pill.installDismiss(this::dismiss);
        getChildren().add(pill);
    }

    /**
     * Shows a notification with the given level and message.
     *
     * @param level   the notification severity level
     * @param message the message to display
     */
    public void show(NotificationLevel level, String message) {
        showInternal(level, message, null, null);
    }

    /**
     * Shows a notification with a borderless action affordance.
     *
     * @param level       the notification severity level
     * @param message     the message to display
     * @param actionLabel the action button label (e.g. "Configure input")
     * @param action      the action to invoke when the button is clicked
     */
    public void show(NotificationLevel level, String message,
                     String actionLabel, Runnable action) {
        showInternal(level, message, actionLabel, action);
    }

    /**
     * Shows a notification with an "Undo" action for destructive operations.
     *
     * @param level      the notification severity level
     * @param message    the message to display
     * @param undoAction the action to invoke when "Undo" is clicked
     */
    public void showWithUndo(NotificationLevel level, String message, Runnable undoAction) {
        showInternal(level, message, NotificationPill.msg("notification.action.undo"), undoAction);
    }

    /**
     * Dismisses the current notification. Animated by default (200 ms
     * fade-out, §3.5); immediate (0 ms) when {@link #animatedProperty()}
     * is {@code false} (story 279 reduce-motion).
     */
    public void dismiss() {
        stopDismissTimeline();
        if (!isAnimated()) {
            stopDismissFade();
            hideNow();
            return;
        }
        // A fade-out is already in flight — don't stack a second one
        // racing on the same node's opacity.
        if (dismissFade != null) {
            return;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(FADE_DURATION_MS), this);
        fadeOut.setFromValue(getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(_ -> {
            dismissFade = null;
            hideNow();
        });
        dismissFade = fadeOut;
        fadeOut.play();
    }

    private void hideNow() {
        setVisible(false);
        setManaged(false);
        // Clear the level class on BOTH nodes: the bar root carries it for
        // the `.notification-bar` CSS scope and the inner pill carries its
        // own copy for the shared `.notification-pill` rules — independent
        // style-class lists, so resetting the accent-bar fill needs both.
        pill.clearLevelStyle();
        clearLevelStyle();
    }

    private void stopDismissFade() {
        if (dismissFade != null) {
            dismissFade.stop();
            dismissFade = null;
        }
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
        return pill.getMessage();
    }

    /**
     * Sets the notification history service used to record every shown
     * notification (story 273 — the single notification log).
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
     * When set to a positive value, this overrides the default 5 s
     * duration. Set to {@code -1} to revert to the default.
     *
     * @param millis the auto-dismiss timeout in milliseconds, or -1 for the default
     */
    public void setAutoDismissMillis(long millis) {
        if (millis <= 0 && millis != -1) {
            throw new IllegalArgumentException("millis must be positive or -1");
        }
        this.autoDismissOverrideMillis = millis;
    }

    /**
     * Returns the auto-dismiss timeout override, or {@code -1} if the
     * default 5 s duration is used.
     */
    public long getAutoDismissMillis() {
        return autoDismissOverrideMillis;
    }

    /** @return the underlying shared pill — exposed for styling tests. */
    public NotificationPill getPill() {
        return pill;
    }

    // ── Reduce-motion (story 279 binds this later) ────────────────────────

    public BooleanProperty animatedProperty() { return animated; }
    public boolean isAnimated()                { return animated.get(); }
    public void setAnimated(boolean a)         { animated.set(a); }

    private void showInternal(NotificationLevel level, String message,
                              String actionLabel, Runnable action) {
        // Cancel any pending auto-dismiss / in-flight fade-out before
        // scheduling a new one (a stale fade must not hide a fresh toast).
        stopDismissTimeline();
        stopDismissFade();

        // Record into the single notification log (all levels, with the
        // *raw* action so the history pill re-triggers only the action —
        // a history click must not try to dismiss this transient toast).
        if (historyService != null) {
            historyService.record(level, message,
                    Optional.ofNullable(action), Optional.ofNullable(actionLabel));
        }

        // On the transient toast, clicking the action also dismisses it
        // (story 273 §5.10 — stops & nulls the auto-dismiss Timeline via
        // dismiss()). Dismiss *first*: if the action itself posts a new
        // toast (e.g. Undo → "Undone"), dismissing afterwards would hide
        // that fresh toast. The history surface keeps the unwrapped action.
        Runnable toastAction = action == null ? null : () -> {
            dismiss();
            action.run();
        };

        currentLevel = level;
        clearLevelStyle();
        getStyleClass().add(level.styleClass());
        pill.apply(level, message, actionLabel, toastAction);

        // Show with fade-in.
        setVisible(true);
        setManaged(true);
        if (isAnimated()) {
            setOpacity(0.0);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(FADE_DURATION_MS), this);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            fadeIn.play();
        } else {
            setOpacity(1.0);
        }

        long dismissMillis = autoDismissOverrideMillis > 0
                ? autoDismissOverrideMillis
                : DEFAULT_AUTO_DISMISS_MS;
        dismissTimeline = new Timeline(
                new KeyFrame(Duration.millis(dismissMillis), _ -> dismiss()));
        dismissTimeline.play();
    }

    private void stopDismissTimeline() {
        if (dismissTimeline != null) {
            dismissTimeline.stop();
            dismissTimeline = null;
        }
    }

    private void clearLevelStyle() {
        for (NotificationLevel level : NotificationLevel.values()) {
            getStyleClass().remove(level.styleClass());
        }
    }
}
