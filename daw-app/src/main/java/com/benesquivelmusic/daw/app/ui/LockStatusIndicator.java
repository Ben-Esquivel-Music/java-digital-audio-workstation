package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.LockStatus;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Optional;

/**
 * Title-bar indicator that surfaces the current {@link LockStatus} of the
 * open project so the user can tell at a glance whether they hold the write
 * lock, are viewing read-only, or have been kicked out by another session.
 *
 * <p>The indicator is a plain JavaFX {@link Label} and therefore drops into
 * any existing layout. {@link #refresh(ProjectLockManager)} should be invoked
 * after every project open / close, after each lock heartbeat, and whenever
 * a {@code LockStolenException} is observed so the title bar reflects reality
 * immediately.</p>
 */
public final class LockStatusIndicator extends Label {

    private static final String STYLE_BASE = "-fx-padding: 2 8 2 8; -fx-background-radius: 8;";

    public LockStatusIndicator() {
        getStyleClass().add("lock-status-indicator");
        applyStatus(LockStatus.NONE, null);
    }

    /** Reads the current status from the given manager and updates the label. */
    public void refresh(ProjectLockManager lockManager) {
        if (lockManager == null) {
            applyOnFx(LockStatus.NONE, null);
            return;
        }
        applyOnFx(lockManager.status(), lockManager.currentLock().orElse(null));
    }

    /** Directly applies a status (useful from tests and event handlers that already know the status). */
    public void show(LockStatus status, ProjectLock currentLock) {
        applyOnFx(status, currentLock);
    }

    private void applyOnFx(LockStatus status, ProjectLock lock) {
        if (Platform.isFxApplicationThread()) {
            applyStatus(status, lock);
        } else {
            LockStatus s = status;
            ProjectLock l = lock;
            Platform.runLater(() -> applyStatus(s, l));
        }
    }

    private void applyStatus(LockStatus status, ProjectLock lock) {
        switch (status) {
            case HELD -> {
                setText("● Locked");
                setStyle(STYLE_BASE + "-fx-background-color: #1f8a3a; -fx-text-fill: white;");
                setTooltip(new Tooltip("You hold the write lock for this project."
                        + Optional.ofNullable(lock)
                            .map(l -> "\nHolder: " + l.user() + "@" + l.hostname() + " (pid " + l.pid() + ")")
                            .orElse("")));
            }
            case READ_ONLY -> {
                setText("◔ Read-only");
                setStyle(STYLE_BASE + "-fx-background-color: #b8860b; -fx-text-fill: white;");
                setTooltip(new Tooltip("Project is read-only because another session holds the lock."));
            }
            case STOLEN -> {
                setText("⚠ Lock stolen");
                setStyle(STYLE_BASE + "-fx-background-color: #b22222; -fx-text-fill: white;");
                setTooltip(new Tooltip("Another session has taken over the lock. "
                        + "Use Save As to preserve your changes."));
            }
            case NONE -> {
                setText("");
                setStyle(STYLE_BASE);
                setTooltip(null);
            }
        }
    }
}
