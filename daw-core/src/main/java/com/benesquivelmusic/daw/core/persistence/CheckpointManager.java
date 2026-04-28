package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionService;
import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Manages automatic checkpoints for long-running DAW projects.
 *
 * <p>The checkpoint manager periodically saves the project state to a series
 * of numbered checkpoint files within the project directory. Old checkpoints
 * are pruned once the configured maximum is reached. This system is designed
 * to protect hours-long recording sessions against data loss from crashes,
 * power failures, or accidental closures.</p>
 *
 * <h2>Checkpoint File Layout</h2>
 * <pre>
 *   project-dir/
 *     checkpoints/
 *       checkpoint-001-20260321T2200.daw
 *       checkpoint-002-20260321T2202.daw
 *       ...
 * </pre>
 */
public final class CheckpointManager {

    private static final String CHECKPOINT_DIR_NAME = "checkpoints";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneId.systemDefault());

    private AutoSaveConfig config;
    private final List<AutoSaveListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger checkpointCounter = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Path> checkpointFiles = new ArrayList<>();
    private volatile BackupRetentionPolicy retentionPolicy;

    private ScheduledExecutorService scheduler;
    private Path projectDirectory;
    private Supplier<String> projectDataSupplier;

    /**
     * Creates a new checkpoint manager with the given auto-save configuration.
     *
     * @param config the auto-save configuration
     */
    public CheckpointManager(AutoSaveConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Starts the checkpoint scheduler for the given project directory.
     *
     * @param projectDirectory the directory where checkpoints will be stored
     */
    public void start(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        if (!config.enabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.projectDirectory = projectDirectory;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daw-checkpoint-scheduler");
            t.setDaemon(true);
            return t;
        });
        long intervalMillis = config.autoSaveInterval().toMillis();
        scheduler.scheduleAtFixedRate(
                this::performCheckpoint,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the checkpoint scheduler.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    /**
     * Reconfigures the checkpoint manager with a new auto-save configuration.
     *
     * <p>If the scheduler is currently running, it is stopped and restarted
     * with the new configuration. If it is not running, only the configuration
     * is updated.</p>
     *
     * @param newConfig the new auto-save configuration
     */
    public void reconfigure(AutoSaveConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig must not be null");
        Path savedProjectDirectory = this.projectDirectory;
        boolean wasRunning = running.get();
        if (wasRunning) {
            stop();
        }
        this.config = newConfig;
        if (wasRunning && savedProjectDirectory != null) {
            start(savedProjectDirectory);
        }
    }

    /**
     * Returns whether the checkpoint scheduler is currently running.
     *
     * @return {@code true} if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Adds a listener for checkpoint events.
     *
     * @param listener the listener to add
     */
    public void addListener(AutoSaveListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(AutoSaveListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns the current checkpoint counter value.
     *
     * @return the number of checkpoints performed
     */
    public int getCheckpointCount() {
        return checkpointCounter.get();
    }

    /**
     * Returns an unmodifiable view of the current checkpoint files.
     *
     * @return the list of checkpoint file paths
     */
    public List<Path> getCheckpointFiles() {
        synchronized (checkpointFiles) {
            return List.copyOf(checkpointFiles);
        }
    }

    /**
     * Returns the auto-save configuration.
     *
     * @return the config
     */
    public AutoSaveConfig getConfig() {
        return config;
    }

    /**
     * Sets a supplier that provides the full project state as a string for
     * inclusion in checkpoint files. When set, checkpoint files contain the
     * complete serialized project state instead of a minimal summary.
     *
     * @param supplier the project data supplier, or {@code null} to revert
     *                 to the default summary format
     */
    public void setProjectDataSupplier(Supplier<String> supplier) {
        this.projectDataSupplier = supplier;
    }

    /**
     * Sets a {@link BackupRetentionPolicy} to apply on every autosave in
     * addition to the simple {@code maxCheckpoints} cap. When non-{@code null}
     * the grandfather-father-son rotation from
     * {@link BackupRetentionService} is run on the checkpoint directory after
     * each successful checkpoint, so older snapshots are kept as
     * hourly / daily / weekly milestones.
     *
     * @param policy the policy to apply, or {@code null} to disable
     *               retention-based pruning (legacy {@code maxCheckpoints}
     *               behaviour only)
     */
    public void setRetentionPolicy(BackupRetentionPolicy policy) {
        this.retentionPolicy = policy;
    }

    /** Returns the active retention policy, or {@code null} if not set. */
    public BackupRetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    /**
     * Performs a single checkpoint. Called automatically by the scheduler,
     * but may also be invoked manually for an explicit save.
     */
    public void performCheckpoint() {
        if (projectDirectory == null) {
            return;
        }
        int index = checkpointCounter.incrementAndGet();
        String checkpointId = buildCheckpointId(index);

        notifyBefore(checkpointId);
        try {
            Path checkpointDir = projectDirectory.resolve(CHECKPOINT_DIR_NAME);
            Files.createDirectories(checkpointDir);

            String fileName = String.format("checkpoint-%03d-%s.daw",
                    index, TIMESTAMP_FMT.format(Instant.now()));
            Path checkpointFile = checkpointDir.resolve(fileName);

            String content = buildCheckpointContent(checkpointId, index);
            Files.writeString(checkpointFile, content);

            synchronized (checkpointFiles) {
                checkpointFiles.add(checkpointFile);
                pruneOldCheckpoints();
            }
            applyRetentionPolicy();

            notifyAfter(checkpointId);
        } catch (IOException e) {
            notifyFailed(checkpointId, e);
        }
    }

    private String buildCheckpointId(int index) {
        return "chk-" + index + "-" + TIMESTAMP_FMT.format(Instant.now());
    }

    private String buildCheckpointContent(String checkpointId, int index) {
        if (projectDataSupplier != null) {
            String data = projectDataSupplier.get();
            if (data != null) {
                return data;
            }
        }
        return String.format("""
                # DAW Checkpoint
                id=%s
                index=%d
                timestamp=%s
                project_dir=%s
                """,
                checkpointId,
                index,
                Instant.now().toString(),
                projectDirectory.toString());
    }

    private void pruneOldCheckpoints() {
        while (checkpointFiles.size() > config.maxCheckpoints()) {
            Path oldest = checkpointFiles.removeFirst();
            try {
                Files.deleteIfExists(oldest);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Applies the optional {@link BackupRetentionPolicy} grandfather-father-son
     * rotation. Performs the directory scan and file deletions <em>outside</em>
     * the {@code checkpointFiles} monitor so concurrent
     * {@link #getCheckpointFiles()} callers (e.g. UI threads) are not blocked
     * by filesystem IO. The {@code checkpointFiles} list is then reconciled
     * with the kept set under the lock.
     */
    private void applyRetentionPolicy() {
        BackupRetentionPolicy policy = this.retentionPolicy;
        if (policy == null || projectDirectory == null) {
            return;
        }
        Path checkpointDir = projectDirectory.resolve(CHECKPOINT_DIR_NAME);
        BackupRetentionService.Plan plan;
        try {
            plan = new BackupRetentionService(policy).prune(checkpointDir);
        } catch (IOException ignored) {
            // best-effort cleanup
            return;
        }
        java.util.Set<Path> kept = new java.util.HashSet<>();
        for (BackupRetentionService.Snapshot s : plan.kept()) {
            kept.add(s.path());
        }
        synchronized (checkpointFiles) {
            checkpointFiles.removeIf(p -> !kept.contains(p));
        }
    }

    private void notifyBefore(String checkpointId) {
        for (AutoSaveListener listener : listeners) {
            listener.onBeforeCheckpoint(checkpointId);
        }
    }

    private void notifyAfter(String checkpointId) {
        for (AutoSaveListener listener : listeners) {
            listener.onAfterCheckpoint(checkpointId);
        }
    }

    private void notifyFailed(String checkpointId, Throwable cause) {
        for (AutoSaveListener listener : listeners) {
            listener.onCheckpointFailed(checkpointId, cause);
        }
    }
}
