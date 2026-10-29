package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
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
 *
 * <p>Story 333 (Persistence Integrity book §7.3; UI Design Book §3.1, tokens
 * by role, never by hex): each status is a style class beside
 * {@code lock-status-indicator} — {@link #HELD_STYLE_CLASS} on the
 * {@code -ok} fill, {@link #READ_ONLY_STYLE_CLASS} on {@code -warn},
 * {@link #STOLEN_STYLE_CLASS} on {@code -danger}, the text
 * {@code -text-on-accent} — defined in {@code styles.css}; none for no lock.
 * The indicator writes no inline style. A stolen lock's tooltip names
 * File ▸ Save As… (ruling R7).</p>
 */
public final class LockStatusIndicator extends Label {

    /** The style class of a lock this session holds. */
    static final String HELD_STYLE_CLASS = "lock-held";
    /** The style class of a read-only open. */
    static final String READ_ONLY_STYLE_CLASS = "lock-read-only";
    /** The style class of a lock another session took over. */
    static final String STOLEN_STYLE_CLASS = "lock-stolen";

    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path. May be {@code null} in a pure-unit context (the compatibility
     * constructor defaults it to {@link FxDispatcher#getDefault()});
     * {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Creates an indicator that marshals off-thread updates through the
     * {@link FxDispatcher#getDefault() app-scoped default} seam.
     */
    public LockStatusIndicator() {
        this(FxDispatcher.getDefault());
    }

    /**
     * Creates an indicator with an explicit FX-thread marshalling seam
     * (story 289).
     *
     * @param fxDispatcher the FX-thread marshalling seam, or {@code null} to use
     *                     the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public LockStatusIndicator(FxDispatcher fxDispatcher) {
        // May be null in a pure-unit context; postFx() falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
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
            postFx(() -> applyStatus(s, l));
        }
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    private void applyStatus(LockStatus status, ProjectLock lock) {
        getStyleClass().removeAll(HELD_STYLE_CLASS, READ_ONLY_STYLE_CLASS, STOLEN_STYLE_CLASS);
        switch (status) {
            case HELD -> {
                setText("● Locked");
                getStyleClass().add(HELD_STYLE_CLASS);
                setTooltip(new Tooltip("You hold the write lock for this project."
                        + Optional.ofNullable(lock)
                            .map(l -> "\nHolder: " + l.user() + "@" + l.hostname() + " (pid " + l.pid() + ")")
                            .orElse("")));
            }
            case READ_ONLY -> {
                setText("◔ Read-only");
                getStyleClass().add(READ_ONLY_STYLE_CLASS);
                setTooltip(new Tooltip("Project is read-only because another session holds the lock."));
            }
            case STOLEN -> {
                setText("⚠ Lock stolen");
                getStyleClass().add(STOLEN_STYLE_CLASS);
                setTooltip(new Tooltip(ProjectCloseText.text("lock.tooltip.stolen")));
            }
            case NONE -> {
                setText("");
                setTooltip(null);
            }
        }
    }
}
