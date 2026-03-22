package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final AutoSaveConfig config;
    private final List<AutoSaveListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger checkpointCounter = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Path> checkpointFiles = new ArrayList<>();

    private ScheduledExecutorService scheduler;
    private Path projectDirectory;

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
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
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

            notifyAfter(checkpointId);
        } catch (IOException e) {
            notifyFailed(checkpointId, e);
        }
    }

    private String buildCheckpointId(int index) {
        return "chk-" + index + "-" + TIMESTAMP_FMT.format(Instant.now());
    }

    private String buildCheckpointContent(String checkpointId, int index) {
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
