package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the story-296 §4.1 ordering: with one stale-lock ("did not exit
 * cleanly") project present, {@link WelcomeView} renders the Recover region
 * <em>above</em> the Continue region and places the stale project in Recover —
 * never in Continue. Classification is synchronous (a single lock-file read per
 * recent path), so placement is asserted with no dispatcher pump.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WelcomeRecoverBeforeRecentTest {

    /** Lock heartbeat instant; the view's clock is 20 min later (> 10 min stale threshold). */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempRoot;

    private FxDispatcher dispatcher;

    @BeforeEach
    void installDispatcher() throws Exception {
        dispatcher = new FxDispatcher();
        FxDispatcher.installDefault(dispatcher);
        runOnFxThread(dispatcher::start);
    }

    @AfterEach
    void disposeDispatcher() throws Exception {
        runOnFxThread(dispatcher::dispose);
        FxDispatcher.installDefault(null);
    }

    @Test
    void recoverRendersAboveContinueAndHoldsTheStaleProject() throws Exception {
        Path staleDir = Files.createDirectory(tempRoot.resolve("crashed-session"));
        Path cleanDir = Files.createDirectory(tempRoot.resolve("clean-session"));
        writeProjectFile(staleDir, "Crashed Song");
        writeProjectFile(cleanDir, "Clean Song");

        // Write a STALE lock into the stale dir: its lastSeenAt is fixed at T0,
        // and the view's clock is T0 + 20 min, so ProjectLockManager.isStale is
        // true (STALE_THRESHOLD = 10 min). forceAcquire writes .project.lock.
        ProjectLockManager holder =
                new ProjectLockManager(InstantSource.fixed(T0), "ben", "studio-pc", 123L);
        holder.forceAcquire(staleDir);

        InstantSource clock = InstantSource.fixed(T0.plus(20, ChronoUnit.MINUTES));

        // Sanity: the scanner classifies the stale dir as recoverable and the
        // clean dir as not — the same cheap read WelcomeView uses for placement.
        assertThat(ProjectHealthScanner.isRecoverable(staleDir, clock)).isTrue();
        assertThat(ProjectHealthScanner.isRecoverable(cleanDir, clock)).isFalse();

        AtomicReference<WelcomeView> viewRef = new AtomicReference<>();
        runOnFxThread(() -> viewRef.set(new WelcomeView(
                List.of(staleDir, cleanDir),
                new ProjectHealthScanner(dispatcher),
                dispatcher,
                clock,
                () -> {},          // onNewProject
                _ -> {},           // onOpenProject
                _ -> {},           // onRecoverProject (story 298)
                _ -> {},           // onDiscardRecovery (story 298)
                () -> {},          // onOpenFromDisk
                () -> {},          // onRestoreArchive
                () -> {})));       // onImportDawproject

        WelcomeView view = viewRef.get();
        assertThat(view).isNotNull();

        // §4.1: Recover renders above Continue.
        var children = view.sectionsBox().getChildren();
        int recoverIndex = children.indexOf(view.recoverSection());
        int continueIndex = children.indexOf(view.continueSection());
        assertThat(recoverIndex).isGreaterThanOrEqualTo(0);
        assertThat(continueIndex).isGreaterThanOrEqualTo(0);
        assertThat(recoverIndex).isLessThan(continueIndex);

        // The stale project is recoverable, never in Continue; the clean one is.
        assertThat(view.recoverPaths()).contains(staleDir).doesNotContain(cleanDir);
        assertThat(view.continuePaths()).contains(cleanDir);

        // Let the per-card background scans finish and clean up the lock so the
        // @TempDir can be deleted (Windows: release the held lock file handle).
        joinPendingScans(view);
        holder.close();
    }

    private void joinPendingScans(WelcomeView view) throws Exception {
        for (Thread scan : view.pendingScans()) {
            scan.join(2_000);
        }
        // Flush the FX queue so any posted applyScanResult consumers have run
        // before teardown disposes the dispatcher.
        runOnFxThread(() -> { /* barrier */ });
    }

    private static void writeProjectFile(Path dir, String name) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>%s</name>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                        <last-modified>2026-01-01T00:00:00Z</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(name);
        Files.writeString(dir.resolve("project.daw"), xml);
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
