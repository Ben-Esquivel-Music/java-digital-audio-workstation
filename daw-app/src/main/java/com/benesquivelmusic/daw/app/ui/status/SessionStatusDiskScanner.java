package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The §1.8 disk-space contract source for the Session Status Strip (story 295).
 * Every 30&nbsp;s it scans the current project's file store for free space and
 * an estimated capture-minutes figure, then publishes both into
 * {@link ProjectOperationProgress#setDisk(long, double)}.
 *
 * <h2>Off the FX thread (§2.1, {@code javafx-application-design} §11)</h2>
 *
 * <p>The scan runs on a <strong>background virtual thread</strong>
 * ({@code Thread.ofVirtual()}, story 205) — the FX thread never calls
 * {@link java.nio.file.FileStore#getUsableSpace()}. The result crosses to the
 * FX thread only through the model's keyed {@link ProjectOperationProgress}
 * mutator, which marshals via the story-289 {@code FxDispatcher}; many scans
 * within one frame therefore coalesce to a single strip repaint. This is a
 * single background task, not a structured-concurrency fan-out (a Non-Goal).</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #start()} schedules the periodic scan (firing immediately, then
 * every {@value #SCAN_INTERVAL_SECONDS}&nbsp;s) on a daemon scheduler;
 * {@link #stop()} shuts it down. The project directory and audio format are
 * read live through {@link Supplier}s, so a project open/close is reflected on
 * the next scan with no re-wiring.</p>
 */
public final class SessionStatusDiskScanner {

    /** Scan cadence in seconds (§4.4 / §6.2: refresh every 30 s). */
    public static final long SCAN_INTERVAL_SECONDS = 30L;

    private final ProjectOperationProgress progress;
    private final Supplier<Path> projectDirectory;
    private final Supplier<AudioFormat> audioFormat;
    private final ScheduledExecutorService scheduler;

    private boolean started;

    /**
     * Creates a scanner publishing into {@code progress}.
     *
     * @param progress         the status model to publish disk facts into; must not be {@code null}
     * @param projectDirectory supplies the current project directory, or {@code null} when none is open; must not be {@code null}
     * @param audioFormat      supplies the current project's audio format (for the capture estimate), or {@code null}; must not be {@code null}
     */
    public SessionStatusDiskScanner(ProjectOperationProgress progress,
                                    Supplier<Path> projectDirectory,
                                    Supplier<AudioFormat> audioFormat) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        this.audioFormat = Objects.requireNonNull(audioFormat, "audioFormat must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daw-disk-scan-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the periodic disk scan — fires immediately, then every
     * {@value #SCAN_INTERVAL_SECONDS}&nbsp;s. Idempotent.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        scheduler.scheduleAtFixedRate(
                this::scanNow, 0L, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops the periodic scan and releases the scheduler thread. Idempotent. */
    public synchronized void stop() {
        started = false;
        scheduler.shutdownNow();
    }

    /**
     * Spawns one disk scan on a fresh background <strong>virtual</strong> thread
     * and returns it (so callers — and tests — can join on completion). The
     * scan reads the file store off the FX thread and publishes the result via
     * the model's dispatcher-marshalled mutator.
     *
     * @return the started virtual scan thread
     */
    public Thread scanNow() {
        Thread t = Thread.ofVirtual().name("daw-disk-scan").unstarted(this::scanOnce);
        t.start();
        return t;
    }

    /**
     * Performs one scan on the calling (virtual) thread: reads the usable bytes
     * of the current project's file store and the capture-minutes estimate, and
     * publishes both into the model. When no project is open the disk facts are
     * cleared to "unknown".
     */
    private void scanOnce() {
        Path dir = projectDirectory.get();
        if (dir == null) {
            progress.setDisk(ProjectOperationProgress.UNKNOWN_BYTES,
                    ProjectOperationProgress.UNKNOWN_MINUTES);
            return;
        }
        long free = ProjectDiskUsage.usableSpaceBytes(dir);
        AudioFormat format = audioFormat.get();
        double minutes = (free < 0 || format == null)
                ? ProjectOperationProgress.UNKNOWN_MINUTES
                : estimatedRecordMinutes(free, format);
        progress.setDisk(free, minutes);
    }

    /**
     * Estimates the minutes of uncompressed multitrack capture that
     * {@code freeBytes} affords at {@code format}. Pure and toolkit-free.
     *
     * @param freeBytes usable bytes on the file store ({@code < 0} → unknown)
     * @param format    the capture audio format; must not be {@code null}
     * @return the estimated minutes, or {@link ProjectOperationProgress#UNKNOWN_MINUTES}
     *         when {@code freeBytes < 0} or the format yields a non-positive byte rate
     */
    public static double estimatedRecordMinutes(long freeBytes, AudioFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        if (freeBytes < 0) {
            return ProjectOperationProgress.UNKNOWN_MINUTES;
        }
        double bytesPerSecond =
                format.sampleRate() * format.channels() * (format.bitDepth() / 8.0);
        if (bytesPerSecond <= 0) {
            return ProjectOperationProgress.UNKNOWN_MINUTES;
        }
        double bytesPerMinute = bytesPerSecond * 60.0;
        return freeBytes / bytesPerMinute;
    }
}
