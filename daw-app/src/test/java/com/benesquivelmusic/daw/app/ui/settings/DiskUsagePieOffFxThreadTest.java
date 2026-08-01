package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 308 §2.7 / §6.6 — the disk-usage scan is virtual-thread work
 * with an explicit FX dispatch edge, a non-blocking start, a clean
 * cancel that discards late results, and a pure (toolkit-free) slice
 * aggregation seam.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DiskUsagePieOffFxThreadTest {

    @Test
    void blockedScanDoesNotBlockStartAndResultCrossesTheDispatcherOffFx() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean scannedOnFx = new AtomicBoolean(true);
        AtomicReference<Thread> scanThread = new AtomicReference<>();
        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();
        AtomicReference<ProjectDiskUsage> result = new AtomicReference<>();
        List<Boolean> busyStates = new CopyOnWriteArrayList<>();

        Callable<ProjectDiskUsage> scanner = () -> {
            scanThread.set(Thread.currentThread());
            scannedOnFx.set(Platform.isFxApplicationThread());
            release.await();
            return new ProjectDiskUsage(1_234, 0, 5L * 1024 * 1024);
        };
        DiskUsageTask task = new DiskUsageTask(scanner, result::set,
                busyStates::add,
                failure -> { throw new AssertionError(failure); },
                fxPosts::add);

        // start() on the FX thread must return while the scanner is still
        // latched — a slow filesystem walk cannot block pane construction.
        CountDownLatch startReturnedOnFx = new CountDownLatch(1);
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.start();
            } catch (Throwable failure) {
                startFailure.set(failure);
            } finally {
                startReturnedOnFx.countDown();
            }
        });
        assertThat(startReturnedOnFx.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(startFailure.get()).isNull();

        // The busy callback proves start posted through the dispatcher;
        // the FX thread stays responsive while the scanner is latched.
        runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        assertThat(busyStates).containsExactly(true);
        onFx(() -> null); // responsive round-trip while blocked

        release.countDown();
        while (result.get() == null) {
            runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        }

        assertThat(scannedOnFx.get()).as("scan ran off the FX thread").isFalse();
        assertThat(scanThread.get()).isNotNull();
        assertThat(scanThread.get().isVirtual()).isTrue();
        assertThat(scanThread.get().getName()).isEqualTo("settings-disk-usage");
        assertThat(result.get()).isEqualTo(new ProjectDiskUsage(1_234, 0, 5L * 1024 * 1024));
        assertThat(busyStates).containsExactly(true, false);
    }

    @Test
    void cancelDiscardsALateResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // Deliberately interrupt-hostile scanner: it swallows the cancel
        // interrupt and still RETURNS a result — which must be discarded.
        Callable<ProjectDiskUsage> scanner = () -> {
            boolean restoreInterrupt = false;
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException cancelled) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return new ProjectDiskUsage(7, 7, 7);
        };
        List<ProjectDiskUsage> delivered = new CopyOnWriteArrayList<>();
        List<Boolean> busyStates = new CopyOnWriteArrayList<>();
        AtomicInteger dispatched = new AtomicInteger();
        // Inline dispatcher: start posts busy(true), cancel posts
        // busy(false), the worker posts the (guard-discarded) result.
        CountDownLatch threeDispatches = new CountDownLatch(3);
        DiskUsageTask task = new DiskUsageTask(scanner, delivered::add,
                busyStates::add,
                failure -> { throw new AssertionError(failure); },
                posted -> {
                    posted.run();
                    dispatched.incrementAndGet();
                    threeDispatches.countDown();
                });

        task.start();
        task.cancel();
        release.countDown();

        assertThat(threeDispatches.await(10, TimeUnit.SECONDS))
                .as("the late result dispatch arrived (and was discarded inside)")
                .isTrue();
        assertThat(delivered).as("a cancelled scan never delivers a result").isEmpty();
        // start's busy(true) ran inline before the cancel; cancel's
        // unconditional busy(false) leaves the affordance off.
        assertThat(busyStates).containsExactly(true, false);
    }

    @Test
    void sliceAggregationIsPureAndKeepsTheOldLabels() {
        List<DiskUsageTask.Slice> slices = DiskUsageTask.slices(
                new ProjectDiskUsage(1_234, 0, 5L * 1024 * 1024));
        assertThat(slices).containsExactly(
                new DiskUsageTask.Slice("Autosaves (1.2 KiB)", 1_234),
                new DiskUsageTask.Slice("Archives (0 B)", 0),
                new DiskUsageTask.Slice("Assets (5.0 MiB)", 5L * 1024 * 1024));

        assertThat(DiskUsageTask.slices(new ProjectDiskUsage(0, 0, 0)))
                .containsExactly(
                        new DiskUsageTask.Slice("Autosaves (0 B)", 0),
                        new DiskUsageTask.Slice("Archives (0 B)", 0),
                        new DiskUsageTask.Slice("Assets (0 B)", 0));
    }

    private static void runPostedOnFx(Runnable posted) throws Exception {
        assertThat(posted).as("a callback was dispatched").isNotNull();
        onFx(() -> {
            posted.run();
            return null;
        });
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(callable.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        return value.get();
    }
}
