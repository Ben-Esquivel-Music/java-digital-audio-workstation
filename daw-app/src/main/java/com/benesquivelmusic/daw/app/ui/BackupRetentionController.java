package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Story 191 — wires the {@link BackupSettingsDialog} into the application,
 * runs the persisted {@link BackupRetentionPolicy} against the global
 * autosaves directory ({@code ~/.daw/autosaves/}) on startup, and schedules
 * a periodic prune so backups created during long sessions are rotated
 * without manual intervention.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #applyNow()} — runs the policy against {@link #autosavesDirectory()}
 *       once. Called on startup and on Apply from {@link BackupSettingsDialog}.</li>
 *   <li>{@link #start()} — registers a hourly periodic task on a single
 *       daemon scheduled thread (per the daw-app conventions for
 *       infrequent I/O-bound work).</li>
 *   <li>{@link #shutdown()} — stops the scheduler. Must be called from the
 *       host on window-hidden so the JVM can exit cleanly.</li>
 * </ol>
 *
 * <p>The "open dialog" entry point ({@link #openDialog(javafx.stage.Window, Path)})
 * constructs a {@link BackupSettingsDialog} pre-populated from the
 * currently-persisted policy. On Apply the new policy is saved through the
 * store and {@link #applyNow()} is invoked so the change takes effect
 * immediately without a restart.</p>
 */
public final class BackupRetentionController {

    private static final Logger LOG = Logger.getLogger(BackupRetentionController.class.getName());

    /** Default cadence for the background prune task. */
    static final long DEFAULT_PERIOD_MINUTES = 60L;

    private final BackupRetentionPolicyStore store;
    private final Path autosavesDirectory;
    private final ScheduledExecutorService scheduler;
    private final long periodMinutes;

    private ScheduledFuture<?> periodicTask;

    /** Creates a controller with the default global autosaves directory. */
    public BackupRetentionController() {
        this(new BackupRetentionPolicyStore(),
                defaultAutosavesDirectory(),
                Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "backup-retention-scheduler");
                    t.setDaemon(true);
                    return t;
                }),
                DEFAULT_PERIOD_MINUTES);
    }

    /**
     * Test / advanced constructor that injects collaborators.
     *
     * @param store              policy persistence
     * @param autosavesDirectory the directory whose snapshots are pruned
     * @param scheduler          single-thread scheduled executor (the
     *                           controller takes ownership and shuts it
     *                           down in {@link #shutdown()})
     * @param periodMinutes      period for the recurring prune task; must be {@code > 0}
     */
    public BackupRetentionController(BackupRetentionPolicyStore store,
                                     Path autosavesDirectory,
                                     ScheduledExecutorService scheduler,
                                     long periodMinutes) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.autosavesDirectory = Objects.requireNonNull(autosavesDirectory,
                "autosavesDirectory must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        if (periodMinutes <= 0L) {
            throw new IllegalArgumentException("periodMinutes must be > 0: " + periodMinutes);
        }
        this.periodMinutes = periodMinutes;
    }

    /** Returns {@code <user.home>/.daw/autosaves}. */
    public static Path defaultAutosavesDirectory() {
        return Paths.get(System.getProperty("user.home", "."), ".daw", "autosaves");
    }

    /** Returns the directory that this controller prunes. */
    public Path autosavesDirectory() {
        return autosavesDirectory;
    }

    /** Returns the policy currently persisted in the store. */
    public BackupRetentionPolicy currentPolicy() {
        return store.loadGlobalOrDefault();
    }

    /**
     * Applies the currently-persisted policy to {@link #autosavesDirectory()}
     * once. Errors are logged but never propagated — pruning is best-effort
     * and must never break the surrounding workflow.
     *
     * @return the number of snapshots marked for deletion (best-effort;
     *         individual file deletions may fail silently)
     */
    public int applyNow() {
        BackupRetentionPolicy policy = currentPolicy();
        return applyPolicy(policy);
    }

    /**
     * Applies the given policy to {@link #autosavesDirectory()} once.
     *
     * @return the number of snapshots marked for deletion (best-effort;
     *         individual file deletions may fail silently)
     */
    public int applyPolicy(BackupRetentionPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (!Files.isDirectory(autosavesDirectory)) {
            return 0;
        }
        try {
            BackupRetentionService.Plan plan =
                    new BackupRetentionService(policy).prune(autosavesDirectory);
            int candidates = plan.discarded().size();
            if (candidates > 0) {
                LOG.log(Level.INFO,
                        "Backup retention attempted to delete {0} snapshot(s) under {1}",
                        new Object[]{candidates, autosavesDirectory});
            }
            return candidates;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to apply backup retention policy", e);
            return 0;
        }
    }

    /**
     * Schedules the periodic prune task. The first execution runs
     * immediately (initial delay = 0) so backups left over from a
     * previous session are pruned at startup on the scheduler thread
     * (not the calling thread). Subsequent calls are no-ops.
     */
    public void start() {
        if (periodicTask != null) {
            return;
        }
        periodicTask = scheduler.scheduleAtFixedRate(
                this::applyNowQuietly,
                0, periodMinutes, TimeUnit.MINUTES);
    }

    private void applyNowQuietly() {
        try {
            applyNow();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Periodic backup retention task failed", e);
        }
    }

    /** Stops the scheduler. Idempotent. */
    public void shutdown() {
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
        scheduler.shutdownNow();
    }

    /**
     * Saves a new policy through the store and immediately applies it to
     * {@link #autosavesDirectory()} so the change takes effect without a
     * restart.
     *
     * @return the number of snapshots deleted by the immediate application
     * @throws IOException if writing the policy file fails
     */
    public int saveAndApply(BackupRetentionPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy must not be null");
        store.saveGlobal(policy);
        return applyPolicy(policy);
    }

    /**
     * Opens the {@link BackupSettingsDialog} as a modal dialog, blocking
     * until the user clicks Apply or Cancel. On Apply the new policy is
     * persisted and applied on a background thread so the FX thread is
     * never blocked by filesystem I/O.
     *
     * @param owner            the owning window for modal placement, may be null
     * @param projectDirectory the active project directory (used by the
     *                         dialog for the disk-usage pie chart), may be null
     */
    public void openDialog(javafx.stage.Window owner, Path projectDirectory) {
        BackupRetentionPolicy current = currentPolicy();
        BackupSettingsDialog dialog =
                new BackupSettingsDialog(current, projectDirectory, autosavesDirectory);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showAndWait().ifPresent(updated -> {
            // Offload save + prune to the scheduler's daemon thread so the
            // FX application thread is never blocked by filesystem I/O.
            scheduler.execute(() -> {
                try {
                    saveAndApply(updated);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to save backup retention policy", e);
                }
            });
        });
    }
}
