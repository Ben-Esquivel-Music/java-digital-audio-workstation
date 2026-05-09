package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.LockConflictHandler;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Story 187 — JavaFX implementation of {@link LockConflictHandler} that
 * surfaces a project-lock conflict to the user and lets them choose between
 * <em>Open Read-Only</em>, <em>Take Over (Steal Lock)</em>, and <em>Cancel</em>.
 *
 * <p>The dialog displays the holder's identity (user@hostname, pid), when
 * the project was opened, when the holder was last seen, and whether the
 * lock appears stale (no recent heartbeat). It is safe to invoke from any
 * thread: when called off the FX Application Thread, the dialog is shown
 * via {@link Platform#runLater} and the calling thread blocks on a latch
 * until the user picks an option.</p>
 *
 * <p>Wired into {@code ProjectManager} via
 * {@code projectManager.setLockConflictHandler(new LockConflictDialog())}.
 * For headless tests, supply a deterministic {@link LockConflictHandler}
 * directly instead of this dialog.</p>
 */
public final class LockConflictDialog implements LockConflictHandler {

    /** Default cancel resolution when the dialog is dismissed without a choice. */
    static final LockConflictResolution DEFAULT_RESOLUTION = LockConflictResolution.CANCEL;

    private final Supplier<Instant> clock;

    /** Creates a dialog that uses the system clock for "last seen" rendering. */
    public LockConflictDialog() {
        this(Instant::now);
    }

    /** Visible for tests — allows injecting a fake clock. */
    LockConflictDialog(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public LockConflictResolution resolve(ProjectLock existingLock, boolean stale) {
        if (existingLock == null) {
            // Nothing to ask about — let the open proceed (TAKE_OVER will write
            // a fresh lock); but keep the safe default of CANCEL.
            return DEFAULT_RESOLUTION;
        }
        if (Platform.isFxApplicationThread()) {
            return showAndAwait(existingLock, stale);
        }
        AtomicReference<LockConflictResolution> result =
                new AtomicReference<>(DEFAULT_RESOLUTION);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(showAndAwait(existingLock, stale));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DEFAULT_RESOLUTION;
        }
        return result.get();
    }

    private LockConflictResolution showAndAwait(ProjectLock existingLock, boolean stale) {
        ButtonType readOnlyBtn = new ButtonType("Open Read-Only", ButtonType.OK.getButtonData());
        ButtonType takeOverBtn = new ButtonType("Take Over (Steal Lock)");
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonType.CANCEL.getButtonData());

        Alert alert = new Alert(stale ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION);
        alert.setTitle("Project Locked");
        alert.setHeaderText(stale
                ? "Project is locked, but the lock appears stale"
                : "Project is locked by another session");
        alert.setContentText(buildContent(existingLock, stale));
        alert.getButtonTypes().setAll(readOnlyBtn, takeOverBtn, cancelBtn);
        DarkThemeHelper.applyTo(alert);

        Optional<ButtonType> chosen = alert.showAndWait();
        if (chosen.isEmpty()) {
            return DEFAULT_RESOLUTION;
        }
        ButtonType c = chosen.get();
        if (c == readOnlyBtn) return LockConflictResolution.OPEN_READ_ONLY;
        if (c == takeOverBtn) return LockConflictResolution.TAKE_OVER;
        return LockConflictResolution.CANCEL;
    }

    /** Visible for tests — formats the dialog body without showing UI. */
    String buildContent(ProjectLock lock, boolean stale) {
        Instant now = clock.get();
        StringBuilder sb = new StringBuilder();
        sb.append("Holder: ").append(lock.user()).append('@').append(lock.hostname())
          .append(" (pid ").append(lock.pid()).append(")\n");
        sb.append("Opened: ").append(lock.openedAt()).append('\n');
        sb.append("Last seen: ").append(lock.lastSeenAt())
          .append("  (").append(formatAge(Duration.between(lock.lastSeenAt(), now))).append(" ago)\n");
        if (stale) {
            sb.append('\n')
              .append("This lock has not been refreshed recently — the holding session "
                      + "may have crashed. Taking over is usually safe in this case.");
        } else {
            sb.append('\n')
              .append("Open read-only to view without changes, or take over to steal "
                      + "the write lock from the other session.");
        }
        return sb.toString();
    }

    private static String formatAge(Duration d) {
        if (d.isNegative()) {
            return "just now";
        }
        long seconds = d.getSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + (minutes % 60) + "m";
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h";
    }
}
