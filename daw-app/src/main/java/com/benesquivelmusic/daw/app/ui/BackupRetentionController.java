package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.PendingWrites;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Story 191 / 308 / 335 — delivers the checkpoint retention policy to the
 * one {@link CheckpointManager} and keeps it in
 * {@code ~/.daw/backup-retention.json} (Persistence Integrity book §4.8,
 * studio ruling R1).
 *
 * <p>The policy governs the open project's {@code checkpoints/} folder: the
 * manager applies it after every checkpoint write, inside that write's
 * claim, and with a policy set it replaces the fixed checkpoint cap. This
 * controller deletes nothing and prunes nothing; no code creates, prunes or
 * deletes {@code ~/.daw/autosaves}, and there is no background prune.</p>
 *
 * <ul>
 *   <li>{@link #deliverStored()} — at startup, reads the stored policy (or
 *       {@link BackupRetentionPolicy#DEFAULT} when no file exists) and
 *       delivers it before any checkpoint generation starts, so the first
 *       write of the first project already prunes by it. A file that exists
 *       but cannot be read never becomes a policy the user did not choose:
 *       a policy that keeps every checkpoint is delivered instead, so
 *       nothing is deleted until a policy is applied in Settings, and the
 *       caller warns the user. It reads a file of under 1&nbsp;KB on
 *       the FX thread in {@code MainController.initialize()}, before the
 *       stage shows: a deliberate, bounded exception to the off-FX I/O rule
 *       (javafx-application-design §11), because the delivery must precede
 *       the first checkpoint.</li>
 *   <li>{@link #deliverAndSave(BackupRetentionPolicy)} — the Settings
 *       ▸ Backups Apply: delivers the policy now (a volatile write on the FX
 *       thread) and saves it off the FX thread through the store's
 *       {@code AtomicFileWriter}; saves are chained, so the last Apply's
 *       policy is the one on disk.</li>
 *   <li>As {@link PendingWrites}, the application's exit waits for a save
 *       still running, so a retention change survives an immediate quit.</li>
 * </ul>
 */
public final class BackupRetentionController implements PendingWrites {

    private static final Logger LOG = Logger.getLogger(BackupRetentionController.class.getName());

    /** Starts each save on a virtual thread of its own; {@link #deliverAndSave} chains them in order. */
    private static final Executor SAVE_THREAD =
            command -> Thread.ofVirtual().name("daw-retention-policy-write").start(command);

    /**
     * Delivered while the stored policy cannot be read: every checkpoint is
     * recent (no byte cap, no age cap), so a prune deletes nothing.
     */
    static final BackupRetentionPolicy KEEP_EVERY_CHECKPOINT =
            new BackupRetentionPolicy(Integer.MAX_VALUE, 0, 0, 0, Duration.ZERO, 0L);

    private final BackupRetentionPolicyStore store;
    private final CheckpointManager checkpoints;
    private final Executor saveExecutor;

    /**
     * Whether the stored policy could not be read and no policy was applied
     * since: {@link #KEEP_EVERY_CHECKPOINT} is delivered, and the rows show
     * {@link BackupRetentionPolicy#DEFAULT}. Written and read on the FX thread.
     */
    private volatile boolean storedPolicyUnreadable;

    /** Guards {@link #lastSave}. */
    private final Object saves = new Object();
    /** The last save {@link #deliverAndSave} started; it may complete exceptionally. */
    private CompletableFuture<Void> lastSave = CompletableFuture.completedFuture(null);

    /**
     * Creates the controller over the policy store and the one checkpoint
     * manager; each save runs on a virtual thread of its own.
     *
     * @param store       the policy store
     * @param checkpoints the checkpoint manager the policy is delivered to
     */
    public BackupRetentionController(BackupRetentionPolicyStore store, CheckpointManager checkpoints) {
        this(store, checkpoints, SAVE_THREAD);
    }

    /**
     * Test seam: as {@link #BackupRetentionController(BackupRetentionPolicyStore, CheckpointManager)},
     * with the executor the saves run on.
     */
    BackupRetentionController(BackupRetentionPolicyStore store, CheckpointManager checkpoints,
                              Executor saveExecutor) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints must not be null");
        this.saveExecutor = Objects.requireNonNull(saveExecutor, "saveExecutor must not be null");
    }

    /**
     * Startup: loads the stored policy ({@link BackupRetentionPolicy#DEFAULT}
     * when no file exists) and delivers it to the checkpoint manager. Call
     * before any checkpoint generation starts.
     *
     * <p>A file that exists but cannot be read, parsed or validated
     * ({@link BackupRetentionPolicyStore#loadGlobal()}) is never replaced by
     * the default, which would delete checkpoints the stored policy keeps:
     * {@link #KEEP_EVERY_CHECKPOINT} is delivered, so policy pruning deletes
     * nothing until a policy is applied ({@link #deliverAndSave}); the rows
     * meanwhile show {@link BackupRetentionPolicy#DEFAULT}. The caller tells
     * the user (studio-engineer ruling on an unreadable retention file).</p>
     *
     * @return the delivered policy, or empty when the stored one could not be
     *         read and nothing is deleted until a policy is applied
     */
    public Optional<BackupRetentionPolicy> deliverStored() {
        Optional<BackupRetentionPolicy> stored;
        try {
            stored = store.loadGlobal();
        } catch (IOException unreadable) {
            LOG.log(Level.WARNING, "Checkpoint retention could not be read from " + store.globalPath()
                    + "; no checkpoint is deleted until a policy is applied in Settings", unreadable);
            storedPolicyUnreadable = true;
            checkpoints.setRetentionPolicy(KEEP_EVERY_CHECKPOINT);
            return Optional.empty();
        }
        BackupRetentionPolicy policy = stored.orElse(BackupRetentionPolicy.DEFAULT);
        storedPolicyUnreadable = false;
        checkpoints.setRetentionPolicy(policy);
        return Optional.of(policy);
    }

    /**
     * The policy the Backups rows show: the one the checkpoint manager
     * enforces — the delivered one, never re-read from disk — or
     * {@link BackupRetentionPolicy#DEFAULT} before any delivery and while the
     * stored policy could not be read (when nothing is deleted).
     *
     * @return the policy the rows show
     */
    public BackupRetentionPolicy currentPolicy() {
        if (storedPolicyUnreadable) {
            return BackupRetentionPolicy.DEFAULT;
        }
        return checkpoints.getRetentionPolicy().orElse(BackupRetentionPolicy.DEFAULT);
    }

    /**
     * Apply: delivers {@code policy} to the checkpoint manager now — it
     * applies after the open project's next checkpoint; delivering it deletes
     * nothing — and saves it off the FX thread, after every save started
     * before. A failed save completes the returned future exceptionally (an
     * {@link UncheckedIOException}) and is logged at {@code WARNING}; the
     * delivered policy still applies until the application quits. It ends
     * the keep-every-checkpoint delivery of a stored policy that could not be
     * read, and its save replaces that file.
     *
     * @param policy the edited policy
     * @return the save's completion
     */
    public CompletableFuture<Void> deliverAndSave(BackupRetentionPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        checkpoints.setRetentionPolicy(policy);
        storedPolicyUnreadable = false;
        synchronized (saves) {
            CompletableFuture<Void> save = lastSave.handle((_, _) -> null)
                    .thenRunAsync(() -> saveNow(policy), saveExecutor);
            lastSave = save;
            return save;
        }
    }

    private void saveNow(BackupRetentionPolicy policy) {
        try {
            store.saveGlobal(policy);
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "Checkpoint retention could not be saved to " + store.globalPath()
                    + "; it applies until the application quits", failure);
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The Settings ▸ Backups access seam over this controller: the rows read
     * {@link #currentPolicy()}, the hold on an unreadable stored policy is
     * reported as it stands now (until {@link #deliverAndSave} ends it), Apply
     * runs {@link #deliverAndSave}, and the open project's folder feeds the
     * disk-usage readout.
     *
     * @param projectDirectory the open project's folder, or empty when none is saved
     * @param onSaveFailed     what runs, on the saving thread, when a save fails
     * @return the access seam
     */
    public BackupSettingsAccess settingsAccess(Supplier<Optional<Path>> projectDirectory,
                                               Runnable onSaveFailed) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Objects.requireNonNull(onSaveFailed, "onSaveFailed must not be null");
        return new BackupSettingsAccess() {
            @Override
            public BackupRetentionPolicy currentPolicy() {
                return BackupRetentionController.this.currentPolicy();
            }

            @Override
            public boolean storedPolicyUnreadable() {
                return storedPolicyUnreadable;
            }

            @Override
            public Optional<Path> projectDirectory() {
                return projectDirectory.get();
            }

            @Override
            public void applyPolicy(BackupRetentionPolicy policy) {
                deliverAndSave(policy).whenComplete((_, failure) -> {
                    if (failure != null) {
                        onSaveFailed.run();
                    }
                });
            }
        };
    }

    @Override
    public boolean pending() {
        synchronized (saves) {
            return !lastSave.isDone();
        }
    }

    @Override
    public boolean awaitUntil(long deadlineNanos) throws InterruptedException {
        CompletableFuture<Void> awaited;
        synchronized (saves) {
            awaited = lastSave;
        }
        try {
            awaited.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException stillSaving) {
            return false;
        } catch (ExecutionException failed) {
            // The save is over; its failure was logged and reported to the caller of deliverAndSave.
            return true;
        }
    }
}
