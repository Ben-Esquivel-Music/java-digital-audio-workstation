package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.core.session.WorkingSession;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Session Manager dock — the §4.3 surface from the Project Manager Design Book.
 *
 * <p>A right-hand, collapsible dock that makes the first-class
 * {@link WorkingSession} object visible: it lists the project's full session
 * history with the current (active) session at the top, so the user can answer
 * "what did I do today?", name a session, and navigate its takes and
 * checkpoints. Replaces the implicit "session" that previously lived only in
 * checkpoint timestamps (design book §1.9).</p>
 *
 * <p>The view is a passive renderer: callers fetch the session history off the
 * JavaFX thread (via {@code SessionManager}) and push it in with
 * {@link #setSessions(List)} on the FX thread. No I/O happens here.</p>
 */
public final class SessionManagerDock extends VBox implements Dockable {

    private final Label titleLabel = new Label();
    private final Label emptyLabel = new Label("No sessions yet \u2014 they appear as you work.");
    private final ListView<WorkingSession> sessionList = new ListView<>();
    private final ObservableList<WorkingSession> sessions = FXCollections.observableArrayList();

    /** Constructs an empty Session Manager dock. */
    public SessionManagerDock() {
        getStyleClass().add("session-manager-dock");

        titleLabel.getStyleClass().add("session-manager-title");
        titleLabel.setText("Session Manager");

        emptyLabel.getStyleClass().add("session-manager-empty");
        emptyLabel.setWrapText(true);

        sessionList.getStyleClass().add("session-manager-list");
        sessionList.setItems(sessions);
        sessionList.setPlaceholder(emptyLabel);
        sessionList.setCellFactory(view -> new SessionCell());
        VBox.setVgrow(sessionList, Priority.ALWAYS);

        getChildren().addAll(titleLabel, sessionList);
    }

    /**
     * Sets the project name shown in the dock header.
     *
     * @param projectName the project name, or {@code null}/blank to fall back
     *                    to the generic "Session Manager" title
     */
    public void setProjectName(String projectName) {
        titleLabel.setText(projectName == null || projectName.isBlank()
                ? "Session Manager"
                : projectName);
    }

    /**
     * Replaces the displayed session history.
     *
     * @param history the sessions to show, newest-first; must not be
     *               {@code null}
     */
    public void setSessions(List<WorkingSession> history) {
        Objects.requireNonNull(history, "history must not be null");
        sessions.setAll(history);
    }

    /** {@return an unmodifiable view of the sessions shown by the dock} */
    public ObservableList<WorkingSession> getSessions() {
        return FXCollections.unmodifiableObservableList(sessions);
    }

    // ── Dockable contract ────────────────────────────────────────────────────

    @Override public String dockId() { return DefaultWorkspaces.PANEL_SESSION_MANAGER; }
    @Override public String displayName() { return "Session Manager"; }
    @Override public String iconName() { return "HISTORY"; }
    @Override public DockZone preferredZone() { return DockZone.RIGHT; }

    // ── Row rendering ────────────────────────────────────────────────────────

    private static final class SessionCell extends ListCell<WorkingSession> {

        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final HBox row = new HBox(8);

        SessionCell() {
            getStyleClass().add("session-manager-cell");
            nameLabel.getStyleClass().add("session-manager-cell-name");
            statusLabel.getStyleClass().add("session-manager-cell-status");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(nameLabel, spacer, statusLabel);
        }

        @Override
        protected void updateItem(WorkingSession session, boolean empty) {
            super.updateItem(session, empty);
            if (empty || session == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            nameLabel.setText(session.name());
            statusLabel.setText(describe(session));
            setGraphic(row);
        }
    }

    /**
     * Builds the right-hand status summary for a session row, e.g.
     * {@code "active · 4h 12m"} or {@code "closed · 1h 38m · 14 takes"}.
     *
     * <p>Package-private and free of JavaFX so it can be unit-tested headlessly.</p>
     *
     * @param session the session to summarise
     * @return the human-readable status line
     */
    static String describe(WorkingSession session) {
        Objects.requireNonNull(session, "session must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append(session.isActive() ? "active" : "closed");
        sb.append(" \u00b7 ").append(formatElapsed(session.elapsed(Instant.now())));
        int takes = session.takeIds().size();
        if (takes > 0) {
            sb.append(" \u00b7 ").append(takes).append(takes == 1 ? " take" : " takes");
        }
        return sb.toString();
    }

    /**
     * Formats a duration as {@code "Hh Mm"} (e.g. {@code "4h 12m"}), or
     * {@code "Mm"} when under an hour.
     *
     * @param duration the duration to format; negative values clamp to zero
     * @return the compact elapsed string
     */
    static String formatElapsed(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        long totalMinutes = Math.max(0, duration.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
