package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.LockConflictHandler;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

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
 * until the user picks an option, or, while a close runs, until the FX
 * thread has taken Cancel without showing it ({@link #resolve}).</p>
 *
 * <p>Wired into {@code ProjectManager} by {@code MainController}, which
 * passes the lifecycle controller's closing door:
 * {@code projectManager.setLockConflictHandler(new LockConflictDialog(java.time.Instant::now, closingDoor))}.
 * The no-argument and clock-only constructors never read a closing door,
 * so a dialog built with them is shown while a close runs. For headless
 * tests, supply a deterministic {@link LockConflictHandler} directly
 * instead of this dialog.</p>
 */
@com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog(
        "migrate to DawgDialog — story 276 follow-up; not a Dialog "
        + "subclass (LockConflictHandler that builds Alerts internally)")
public final class LockConflictDialog implements LockConflictHandler {

    /** Default cancel resolution when the dialog is dismissed without a choice. */
    static final LockConflictResolution DEFAULT_RESOLUTION = LockConflictResolution.CANCEL;

    private static final Logger LOG = Logger.getLogger(LockConflictDialog.class.getName());

    private final Supplier<Instant> clock;
    /** The name of the door whose close runs now, or empty; read on the FX thread before a dialog shows. */
    private final Supplier<Optional<String>> closingDoor;

    /** Creates a dialog that uses the system clock for "last seen" rendering. */
    public LockConflictDialog() {
        this(Instant::now);
    }

    /** Visible for tests — allows injecting a fake clock. */
    LockConflictDialog(Supplier<Instant> clock) {
        this(clock, Optional::empty);
    }

    /**
     * Creates a dialog that shows nothing while a close runs.
     *
     * @param clock       the clock of the "last seen" rendering
     * @param closingDoor the name of the door whose close runs now, or empty
     *                    while none runs ({@code ProjectLifecycleController.closingDoor});
     *                    read on the FX thread
     */
    LockConflictDialog(Supplier<Instant> clock, Supplier<Optional<String>> closingDoor) {
        this.clock = clock;
        this.closingDoor = Objects.requireNonNull(closingDoor, "closingDoor must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>While a close runs — the door {@code closingDoor} names, read on the
     * FX thread in the turn the dialog would show — no dialog is shown: the
     * resolution is Cancel, logged at INFO. A dialog shown then would be
     * fenced, one shown after the close's own dialog would also disable it,
     * being application-modal, and an exit waiting for the work that asks
     * would wait for the dialog's answer, since that work's worker blocks
     * here until it comes (ruling R-1.6; PR #989 review 5711460812).</p>
     */
    @Override
    public LockConflictResolution resolve(ProjectLock existingLock, boolean stale) {
        if (existingLock == null) {
            // ProjectManager only calls this handler when existingLock != null,
            // so this branch is purely defensive. Return CANCEL to prevent the
            // open from proceeding without an explicit user choice.
            return DEFAULT_RESOLUTION;
        }
        if (Platform.isFxApplicationThread()) {
            return showAndAwait(existingLock, stale);
        }
        AtomicReference<LockConflictResolution> result =
                new AtomicReference<>(DEFAULT_RESOLUTION);
        CountDownLatch latch = new CountDownLatch(1);
        FxDispatcher.runOnFx(() -> {
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
        Optional<String> closing = closingDoor.get();
        if (closing.isPresent()) {
            return refusedWhileClosing(existingLock, closing.get());
        }
        String holder = holderIdentity(existingLock);

        ButtonType readOnlyBtn = new ButtonType("Open Read-Only", ButtonType.OK.getButtonData());
        ButtonType takeOverBtn = new ButtonType("Take Over (Steal Lock)");
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonType.CANCEL.getButtonData());

        Alert alert = new Alert(stale ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION);
        alert.setTitle("Project Locked by " + holder);
        alert.setHeaderText(stale
                ? "Project is locked by " + holder + ", but the lock appears stale"
                : "Project is locked by " + holder);
        alert.setContentText(buildContent(existingLock, stale));
        alert.getButtonTypes().setAll(readOnlyBtn, takeOverBtn, cancelBtn);
        ThemeManager.getDefault().applyTo(alert.getDialogPane());

        Optional<ButtonType> chosen = alert.showAndWait();
        if (chosen.isEmpty()) {
            return DEFAULT_RESOLUTION;
        }
        ButtonType c = chosen.get();
        if (c == readOnlyBtn) return LockConflictResolution.OPEN_READ_ONLY;
        if (c == takeOverBtn) return confirmTakeOver(existingLock, stale);
        return LockConflictResolution.CANCEL;
    }

    /**
     * The resolution while {@code door}'s close runs: Cancel, without the
     * dialog, logged at INFO. A dialog shown then would be fenced, one shown
     * after the close's own dialog would also disable it, being
     * application-modal, and an exit waiting for the work that asks waits for
     * its answer (ruling R-1.6; PR #989 review 5711460812). FX thread.
     */
    private static LockConflictResolution refusedWhileClosing(ProjectLock existingLock, String door) {
        String holder = holderIdentity(existingLock);
        LOG.info(() -> "The Project Locked dialog of " + holder + "'s lock was not shown while " + door
                + " closes the open project: its Cancel was taken, so the locked folder was neither opened"
                + " read-only nor taken over, and the lock was left to " + holder + ".");
        return LockConflictResolution.CANCEL;
    }

    /**
     * The take-over confirmation's resolution while {@code door}'s close runs:
     * Cancel, without the confirmation, logged at INFO ({@link #confirmTakeOver};
     * ruling R-1.6; PR #989 review 5711460812). FX thread.
     */
    private static LockConflictResolution takeOverRefusedWhileClosing(String holder, String door) {
        LOG.info(() -> "The Confirm Lock Take-Over dialog of " + holder + "'s lock was not shown while " + door
                + " closes the open project: its Cancel was taken, so the locked folder was neither opened"
                + " read-only nor taken over, and the lock was left to " + holder + ".");
        return LockConflictResolution.CANCEL;
    }

    /**
     * Shows a secondary confirmation dialog before stealing the lock.
     * When the lock is stale (no recent heartbeat), the warning is softer
     * because the holding session has likely crashed. When the lock is
     * active, the warning is stronger to prevent accidental data loss.
     * While a close runs — the door {@code closingDoor} names, read on the
     * FX thread once the Project Locked dialog, already up when the close
     * began, has answered Take Over — the confirmation is not shown: the
     * resolution is Cancel, logged at INFO, so the lock is not taken over
     * ({@link #takeOverRefusedWhileClosing}). A confirmation shown then would
     * be fenced, one shown after the close's own dialog would also disable
     * it, being application-modal, and an exit waiting for the work that asks
     * would wait for its answer, since that work's worker blocks in
     * {@link #resolve} until it comes (ruling R-1.6; PR #989 review
     * 5711460812).
     */
    private LockConflictResolution confirmTakeOver(ProjectLock existingLock, boolean stale) {
        String holder = holderIdentity(existingLock);
        Optional<String> closing = closingDoor.get();
        if (closing.isPresent()) {
            return takeOverRefusedWhileClosing(holder, closing.get());
        }
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Confirm Lock Take-Over");
        if (stale) {
            confirm.setHeaderText("Take over stale lock from " + holder + "?");
            confirm.setContentText("The lock appears stale — the holding session may have "
                    + "crashed. Taking over should be safe, but any unsaved changes in "
                    + "that session will be lost.");
        } else {
            confirm.setHeaderText("Take over active lock from " + holder + "?");
            confirm.setContentText("WARNING: The other session (" + holder
                    + ", pid " + existingLock.pid() + ") appears to be active. "
                    + "Stealing the lock may cause data loss if the other session "
                    + "saves concurrently. Proceed only if you are sure the other "
                    + "session is no longer in use.");
        }
        confirm.getButtonTypes().setAll(
                new ButtonType("Steal Lock", ButtonType.OK.getButtonData()),
                new ButtonType("Cancel", ButtonType.CANCEL.getButtonData()));
        ThemeManager.getDefault().applyTo(confirm.getDialogPane());

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            return LockConflictResolution.TAKE_OVER;
        }
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

    private static String holderIdentity(ProjectLock lock) {
        return lock.user() + "@" + lock.hostname();
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
