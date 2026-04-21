package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Owns the ripple-mode toolbar button, the red status-bar banner shown when
 * ripple is active, and the session-scoped confirmation prompt that guards
 * {@link RippleMode#ALL_TRACKS}.
 *
 * <p>Story 138 — {@code docs/user-stories/138-ripple-edit-mode.md}.</p>
 */
final class RippleModeController {

    /**
     * Host hooks into the main application: holds the project (source of
     * truth for the ripple mode), provides UI refresh, and surfaces
     * notifications.
     */
    interface Host {
        DawProject project();
        void markProjectDirty();
        void showNotification(NotificationLevel level, String message);
    }

    private final Host host;
    private final ToolbarStateStore toolbarStateStore;
    private final Button cyclingButton;
    private final Label bannerLabel;

    /**
     * Tracks whether the ALL_TRACKS confirmation has already been shown — or
     * accepted — during this application session. Reset on each JVM start so
     * that a user who previously said "Yes, just this time" still sees the
     * prompt tomorrow.
     */
    private boolean allTracksPromptAcceptedThisSession;

    RippleModeController(Host host,
                         ToolbarStateStore toolbarStateStore,
                         Button cyclingButton,
                         Label bannerLabel) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.toolbarStateStore = Objects.requireNonNull(
                toolbarStateStore, "toolbarStateStore must not be null");
        this.cyclingButton = Objects.requireNonNull(
                cyclingButton, "cyclingButton must not be null");
        this.bannerLabel = Objects.requireNonNull(bannerLabel, "bannerLabel must not be null");

        cyclingButton.setOnAction(_ -> cycle());
        refresh();
    }

    /** Returns the current ripple mode read from the host project. */
    RippleMode mode() {
        return host.project().getRippleMode();
    }

    /**
     * Advances the ripple mode in the cycle {@code OFF → PER_TRACK → ALL_TRACKS → OFF}.
     * When the next step is {@code ALL_TRACKS} and the user has not suppressed the
     * confirmation, a prompt is shown; declining leaves the mode unchanged.
     */
    void cycle() {
        setMode(mode().next());
    }

    /** Applies a specific mode, honouring the ALL_TRACKS confirmation gate. */
    void setMode(RippleMode target) {
        Objects.requireNonNull(target, "target must not be null");
        if (target == RippleMode.ALL_TRACKS && !confirmAllTracks()) {
            return;
        }
        host.project().setRippleMode(target);
        host.markProjectDirty();
        refresh();
        if (target == RippleMode.OFF) {
            host.showNotification(NotificationLevel.INFO, "Ripple editing disabled");
        } else {
            host.showNotification(NotificationLevel.INFO, target.displayName());
        }
    }

    /**
     * Shows the aggressive-red banner when ripple is on, hides it otherwise.
     * Also updates the toolbar button's icon + tooltip so the current state
     * is visible even without looking at the banner.
     */
    void refresh() {
        RippleMode current = mode();

        // Toolbar button: tooltip + icon reflect the mode. We use three
        // distinct DAW icons so the three states are visually distinguishable
        // at a glance: SHUFFLE for OFF (idle), MOVE for PER_TRACK (single
        // lane), ALERT for ALL_TRACKS (destructive cascade).
        DawIcon icon = switch (current) {
            case OFF -> DawIcon.SHUFFLE;
            case PER_TRACK -> DawIcon.MOVE;
            case ALL_TRACKS -> DawIcon.ALERT;
        };
        cyclingButton.setGraphic(IconNode.of(icon, 14));
        cyclingButton.setText(current.displayName());
        cyclingButton.setTooltip(new Tooltip(current.displayName()
                + "  (Shift+1 / Shift+2 / Shift+3)"));
        cyclingButton.getStyleClass().removeAll(
                "ripple-off", "ripple-per-track", "ripple-all-tracks");
        cyclingButton.getStyleClass().add(switch (current) {
            case OFF -> "ripple-off";
            case PER_TRACK -> "ripple-per-track";
            case ALL_TRACKS -> "ripple-all-tracks";
        });

        // Status-bar banner: visible only when ripple is active.
        boolean active = current != RippleMode.OFF;
        bannerLabel.setVisible(active);
        bannerLabel.setManaged(active);
        if (active) {
            bannerLabel.setText(current.displayName());
            bannerLabel.setGraphic(IconNode.of(DawIcon.ALERT, 12));
        } else {
            bannerLabel.setText("");
            bannerLabel.setGraphic(null);
        }
    }

    /**
     * Call whenever the project reference changes (load, new, etc.) so that
     * UI reflects the persisted mode of the newly-active project.
     */
    void onProjectChanged() {
        allTracksPromptAcceptedThisSession = false;
        refresh();
    }

    /**
     * Returns {@code true} if the user confirmed (or has suppressed) the
     * ALL_TRACKS prompt. The "don't ask again" checkbox persists via
     * {@link ToolbarStateStore}; in-session acceptance is remembered so the
     * user is not asked twice during the same run.
     */
    private boolean confirmAllTracks() {
        if (toolbarStateStore.loadRippleAllTracksPromptSuppressed()) {
            return true;
        }
        if (allTracksPromptAcceptedThisSession) {
            return true;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Enable Ripple: All Tracks");
        dialog.setHeaderText("Ripple: All Tracks is destructive across tracks");

        Label body = new Label(
                "Deleting or moving a clip will shift later clips on every track, "
              + "potentially affecting material you did not intend to move.\n\n"
              + "Are you sure you want to enable Ripple: All Tracks?");
        body.setWrapText(true);
        body.setMaxWidth(440);

        CheckBox suppress = new CheckBox("Don't ask again");

        VBox content = new VBox(12, body, suppress);
        content.setPadding(new Insets(16));

        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        pane.setGraphic(IconNode.of(DawIcon.ALERT, 32));
        pane.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);

        HBox.setMargin(body, new Insets(0));

        var result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            if (suppress.isSelected()) {
                toolbarStateStore.saveRippleAllTracksPromptSuppressed(true);
            }
            allTracksPromptAcceptedThisSession = true;
            return true;
        }
        return false;
    }
}
