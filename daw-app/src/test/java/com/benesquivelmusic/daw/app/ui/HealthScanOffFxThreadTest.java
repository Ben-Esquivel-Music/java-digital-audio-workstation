package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.HealthBadge;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.ProjectScanResult;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectHealthScanner#scan} runs OFF the FX thread on a
 * virtual thread and marshals its result back through the {@link FxDispatcher},
 * and that {@link ProjectHealthScanner#scanBlocking} classifies migrated /
 * healthy projects and stale recoverable locks.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class HealthScanOffFxThreadTest {

    @TempDir
    Path projectDir;

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
    void scanRunsOnVirtualThreadAndMarshalsResultToFx() throws Exception {
        Instant modified = Instant.parse("2026-04-01T12:00:00Z");
        writeXmlProject("Off-Thread Song", modified);
        // Give the project some on-disk weight in checkpoints/ so the size is non-zero.
        Path checkpoints = Files.createDirectories(projectDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR));
        Files.write(checkpoints.resolve("cp1.bin"), new byte[4096]);

        long expectedBytes = ProjectDiskUsage.compute(projectDir).totalBytes();

        AtomicReference<ProjectScanResult> captured = new AtomicReference<>();
        Thread t = dispatcher == null ? null : new ProjectHealthScanner(dispatcher)
                .scan(projectDir, captured::set);

        assertThat(t).isNotNull();
        assertThat(t.isVirtual()).isTrue(); // proves the scan ran OFF the FX thread
        t.join(2_000);
        assertThat(t.isAlive()).isFalse();

        // scan() posts the consumer via dispatcher.onFx (runLater); flush it
        // with an FX-thread barrier before reading the captured result.
        runOnFxThread(() -> { /* barrier — lets the posted consumer run first */ });

        ProjectScanResult result = captured.get();
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Off-Thread Song");
        assertThat(result.sizeOnDiskBytes()).isEqualTo(expectedBytes);
        assertThat(result.lastOpened()).isEqualTo(modified);
    }

    @Test
    void scanBlockingReportsMigratedWhenBackupSiblingPresent() throws IOException {
        writeXmlProject("Migrated Song", Instant.parse("2026-01-01T00:00:00Z"));
        // A pre-migration backup sibling: project.daw.v7.<stamp>.bak
        Files.writeString(projectDir.resolve("project.daw.v7.20260101-000000.bak"), "<old/>");

        ProjectScanResult result =
                ProjectHealthScanner.scanBlocking(projectDir, InstantSource.system());

        assertThat(result.health()).isEqualTo(HealthBadge.MIGRATED);
        assertThat(result.backupSchemaVersion()).isEqualTo(7);
    }

    @Test
    void scanBlockingReportsNewestBackupByTimestampNotFilenameOrder() throws IOException {
        writeXmlProject("Multi-Migration Song", Instant.parse("2026-06-01T00:00:00Z"));
        // Two pre-migration backups: a v2 migration in January and a v10
        // migration in June. The newest backup (by yyyyMMdd-HHmmss timestamp) is
        // the v10 one — but the v2 *filename* sorts lexicographically GREATER
        // ("…v2…" > "…v10…" because '2' > '1'), so a filename-order scan would
        // wrongly report v2.
        Files.writeString(projectDir.resolve("project.daw.v2.20260101-000000.bak"), "<old/>");
        Files.writeString(projectDir.resolve("project.daw.v10.20260601-000000.bak"), "<old/>");

        ProjectScanResult result =
                ProjectHealthScanner.scanBlocking(projectDir, InstantSource.system());

        assertThat(result.health()).isEqualTo(HealthBadge.MIGRATED);
        assertThat(result.backupSchemaVersion())
                .as("newest backup is chosen by timestamp, not filename order")
                .isEqualTo(10);
    }

    @Test
    void scanBlockingReportsHealthyWhenNoBackupSibling() throws IOException {
        writeXmlProject("Clean Song", Instant.parse("2026-01-01T00:00:00Z"));

        ProjectScanResult result =
                ProjectHealthScanner.scanBlocking(projectDir, InstantSource.system());

        assertThat(result.health()).isEqualTo(HealthBadge.HEALTHY);
        assertThat(result.backupSchemaVersion()).isEqualTo(-1);
        assertThat(result.sessionCount()).isEqualTo(-1); // Stage 2
        assertThat(result.recoverable()).isFalse();
    }

    @Test
    void scanBlockingReportsRecoverableForStaleLock() throws IOException {
        writeXmlProject("Crashed Song", Instant.parse("2026-01-01T00:00:00Z"));

        // Acquire a lock at t0, then evaluate the scan with a clock 11 minutes
        // later — past the 10-minute stale threshold.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ProjectLockManager holder =
                new ProjectLockManager(InstantSource.fixed(t0), "alice", "studio-mac", 1L);
        holder.tryAcquire(projectDir);

        InstantSource later = InstantSource.fixed(t0.plus(Duration.ofMinutes(11)));

        ProjectScanResult result = ProjectHealthScanner.scanBlocking(projectDir, later);

        assertThat(result.lockPresent()).isTrue();
        assertThat(result.lockStale()).isTrue();
        assertThat(result.recoverable()).isTrue();
        assertThat(result.lockHolderLabel()).isEqualTo("alice @ studio-mac");
        assertThat(ProjectHealthScanner.isRecoverable(projectDir, later)).isTrue();
    }

    private void writeXmlProject(String name, Instant lastModified) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>%s</name>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                        <last-modified>%s</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(name, lastModified);
        Files.writeString(projectDir.resolve("project.daw"), xml);
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
