package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The Backups-category disk-usage scan (story 308, Settings View Design
 * Book §2.7 / §5.8 / §6.6) — the second canonical off-FX-thread settings
 * task after {@link DeviceEnumerationTask}.
 *
 * <p>The scanner (production: {@code ProjectDiskUsage.compute} over the
 * open project directory) runs on a virtual thread (JEP 444) named
 * {@code settings-disk-usage}; only the immutable result crosses back to
 * JavaFX through the dispatcher seam ({@link FxDispatcher#runOnFx} in
 * production). The busy callback brackets the scan so the shell can show
 * the §5.8 "recomputing…" affordance via
 * {@code SettingsShell.setGroupBusy("backups", "diskUsage", …)}.</p>
 *
 * <h2>Cancellation contract</h2>
 *
 * <p>{@link #cancel()} interrupts the worker and bumps the generation so
 * any in-flight or late result is <em>discarded</em>, never delivered —
 * the §2.7 upgrade over the old dialog's fire-and-forget
 * {@code javafx.concurrent.Task}. {@code ProjectDiskUsage.compute} walks
 * the file tree with non-interruptible NIO calls, so cancellation cannot
 * abort the walk mid-flight; discard-on-arrival is the guarantee this
 * task makes (the worker is a daemon-like virtual thread and simply
 * finishes into a dropped callback).</p>
 */
public final class DiskUsageTask {

    /** One labelled pie slice — pure data, no toolkit type involved. */
    public record Slice(String label, long bytes) {
        public Slice {
            Objects.requireNonNull(label, "label must not be null");
        }
    }

    private final Callable<ProjectDiskUsage> scanner;
    private final Consumer<ProjectDiskUsage> onSucceeded;
    private final Consumer<Boolean> onRunningChanged;
    private final Consumer<Throwable> onFailed;
    private final Consumer<Runnable> fxDispatcher;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    private volatile boolean cancelled;

    /** Creates a production task whose callbacks are always posted to JavaFX. */
    public DiskUsageTask(Callable<ProjectDiskUsage> scanner,
                         Consumer<ProjectDiskUsage> onSucceeded,
                         Consumer<Boolean> onRunningChanged,
                         Consumer<Throwable> onFailed) {
        this(scanner, onSucceeded, onRunningChanged, onFailed, FxDispatcher::runOnFx);
    }

    /** Test seam for observing the dispatch boundary without starting JavaFX. */
    DiskUsageTask(Callable<ProjectDiskUsage> scanner,
                  Consumer<ProjectDiskUsage> onSucceeded,
                  Consumer<Boolean> onRunningChanged,
                  Consumer<Throwable> onFailed,
                  Consumer<Runnable> fxDispatcher) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.onSucceeded = Objects.requireNonNull(onSucceeded, "onSucceeded must not be null");
        this.onRunningChanged = Objects.requireNonNull(
                onRunningChanged, "onRunningChanged must not be null");
        this.onFailed = Objects.requireNonNull(onFailed, "onFailed must not be null");
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher must not be null");
    }

    /**
     * Starts (or restarts) the scan. Returns before the scanner runs, so
     * a slow filesystem walk cannot block pane construction or JavaFX.
     */
    public void start() {
        if (cancelled) {
            return;
        }
        long requestGeneration = generation.incrementAndGet();
        interruptWorker();
        dispatchIfCurrent(requestGeneration, () -> onRunningChanged.accept(true));
        Thread scan = Thread.ofVirtual()
                .name("settings-disk-usage")
                .unstarted(() -> scan(requestGeneration));
        worker.set(scan);
        scan.start();
    }

    private void scan(long requestGeneration) {
        try {
            ProjectDiskUsage usage = scanner.call();
            dispatchIfCurrent(requestGeneration, () -> {
                onSucceeded.accept(usage);
                onRunningChanged.accept(false);
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dispatchIfCurrent(requestGeneration, () -> onRunningChanged.accept(false));
        } catch (Exception failure) {
            dispatchIfCurrent(requestGeneration, () -> {
                onFailed.accept(failure);
                onRunningChanged.accept(false);
            });
        } finally {
            worker.compareAndSet(Thread.currentThread(), null);
        }
    }

    /**
     * Cancels the scan permanently: interrupts the worker, invalidates
     * every queued callback (generation bump + cancelled flag) and turns
     * the busy affordance off. A result that arrives after this call is
     * discarded — see the class Javadoc for why discard-on-arrival is
     * the strongest guarantee available.
     */
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        generation.incrementAndGet();
        interruptWorker();
        fxDispatcher.accept(() -> onRunningChanged.accept(false));
    }

    private void interruptWorker() {
        Thread scan = worker.getAndSet(null);
        if (scan != null) {
            scan.interrupt();
        }
    }

    private void dispatchIfCurrent(long requestGeneration, Runnable callback) {
        fxDispatcher.accept(() -> {
            if (!cancelled && generation.get() == requestGeneration) {
                callback.run();
            }
        });
    }

    // ── Pure aggregation seam (unit-testable with no toolkit) ────────────────

    /**
     * The three labelled slices the §5.8 pie renders — the same
     * "Autosaves (1.2 MiB)" formatted-bytes labels the absorbed dialog
     * produced. Pure math; the FX {@code PieChart.Data} conversion stays
     * with the caller.
     */
    public static List<Slice> slices(ProjectDiskUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        return List.of(
                new Slice("Autosaves (" + formatBytes(usage.autosavesBytes()) + ")",
                        usage.autosavesBytes()),
                new Slice("Archives (" + formatBytes(usage.archivesBytes()) + ")",
                        usage.archivesBytes()),
                new Slice("Assets (" + formatBytes(usage.assetsBytes()) + ")",
                        usage.assetsBytes()));
    }

    /** B / KiB / MiB / GiB rendering, ported verbatim from the absorbed dialog. */
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }
}
