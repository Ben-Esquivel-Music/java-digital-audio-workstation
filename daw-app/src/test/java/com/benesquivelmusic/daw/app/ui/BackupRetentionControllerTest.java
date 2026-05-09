package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 191 — verifies that {@link BackupRetentionController} applies the
 * persisted policy to an autosaves directory, schedules a periodic prune,
 * and that the policy survives a "restart" via
 * {@link BackupRetentionPolicyStore}.
 *
 * <p>None of these assertions require a JavaFX toolkit — the controller's
 * applyPolicy/saveAndApply pathways are purely IO + the
 * {@code BackupRetentionService} engine, so the headless unit test can run
 * without a display.</p>
 */
class BackupRetentionControllerTest {

    @Test
    void applyPolicyWithNoBucketsDiscardsAllSnapshots(@TempDir Path tempDir) throws IOException {
        Path autosaves = Files.createDirectories(tempDir.resolve("autosaves"));
        Path globalPolicy = tempDir.resolve("backup-retention.json");

        // One "fresh" snapshot (now) and one "old" (2 days ago).
        Path fresh = Files.writeString(autosaves.resolve("fresh.daw"), "x");
        Path old = Files.writeString(autosaves.resolve("old.daw"), "x");
        Files.setLastModifiedTime(old,
                FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalPolicy);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        BackupRetentionController controller =
                new BackupRetentionController(store, autosaves, scheduler, 60L);
        try {
            // All bucket counts are zero, so no snapshot qualifies for any
            // retention bucket — both are discarded regardless of age.
            BackupRetentionPolicy policy = new BackupRetentionPolicy(
                    0, 0, 0, 0,
                    Duration.ofDays(1),
                    0L);
            int candidates = controller.saveAndApply(policy);

            assertThat(candidates).isEqualTo(2);
            assertThat(Files.exists(old)).isFalse();
            assertThat(Files.exists(fresh)).isFalse();
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void applyPolicyKeepsRecentAndDropsOnlyOldFiles(@TempDir Path tempDir) throws IOException {
        Path autosaves = Files.createDirectories(tempDir.resolve("autosaves"));
        Path globalPolicy = tempDir.resolve("backup-retention.json");

        Path fresh = Files.writeString(autosaves.resolve("fresh.daw"), "x");
        Path old = Files.writeString(autosaves.resolve("old.daw"), "x");
        Files.setLastModifiedTime(old,
                FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalPolicy);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        BackupRetentionController controller =
                new BackupRetentionController(store, autosaves, scheduler, 60L);
        try {
            // keepRecent=10 means any age-eligible file is kept, the
            // 2-day-old file is dropped because of maxAge.
            BackupRetentionPolicy policy = new BackupRetentionPolicy(
                    10, 0, 0, 0,
                    Duration.ofDays(1),
                    0L);
            controller.saveAndApply(policy);

            assertThat(Files.exists(fresh)).isTrue();
            assertThat(Files.exists(old)).isFalse();
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void startSchedulesPeriodicTaskOnExecutor(@TempDir Path tempDir) throws Exception {
        Path autosaves = Files.createDirectories(tempDir.resolve("autosaves"));
        Path globalPolicy = tempDir.resolve("backup-retention.json");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalPolicy);

        // Recording scheduler: captures scheduleAtFixedRate parameters and
        // never actually runs the task. This proves the controller wires
        // the periodic prune through ScheduledExecutorService correctly,
        // without making the test sleep for 60 seconds.
        AtomicInteger scheduleCalls = new AtomicInteger();
        AtomicLong recordedInitialDelay = new AtomicLong(-1);
        AtomicLong recordedPeriod = new AtomicLong();
        AtomicReference<TimeUnit> recordedUnit = new AtomicReference<>();
        AtomicReference<Runnable> recordedTask = new AtomicReference<>();

        ScheduledExecutorService delegate = Executors.newScheduledThreadPool(1);
        ScheduledExecutorService recording = new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override
            public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                    Runnable command, long initialDelay, long period, TimeUnit unit) {
                scheduleCalls.incrementAndGet();
                recordedInitialDelay.set(initialDelay);
                recordedPeriod.set(period);
                recordedUnit.set(unit);
                recordedTask.set(command);
                // return a no-op future so the controller can cancel it.
                return delegate.scheduleAtFixedRate(() -> { },
                        TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1),
                        TimeUnit.MILLISECONDS);
            }
        };

        BackupRetentionController controller =
                new BackupRetentionController(store, autosaves, recording, 1L);
        try {
            controller.start();
            assertThat(scheduleCalls).hasValue(1);
            assertThat(recordedInitialDelay).as("initial delay should be 0 for immediate startup prune")
                    .hasValue(0L);
            assertThat(recordedPeriod).hasValue(1L);
            assertThat(recordedUnit.get()).isEqualTo(TimeUnit.MINUTES);

            // Running the captured task should be safe and exercise the
            // real applyNow() path.
            recordedTask.get().run();

            // start() is idempotent.
            controller.start();
            assertThat(scheduleCalls).hasValue(1);
        } finally {
            controller.shutdown();
            delegate.shutdownNow();
        }
    }

    @Test
    void policyPersistsAcrossRestartViaStore(@TempDir Path tempDir) throws IOException {
        Path autosaves = Files.createDirectories(tempDir.resolve("autosaves"));
        Path globalPolicy = tempDir.resolve("backup-retention.json");

        BackupRetentionPolicy custom = new BackupRetentionPolicy(
                3, 5, 7, 2,
                Duration.ofDays(42),
                123L * 1024L * 1024L);

        // Session 1: save a custom policy through the controller.
        BackupRetentionPolicyStore store1 = new BackupRetentionPolicyStore(globalPolicy);
        ScheduledExecutorService scheduler1 = Executors.newScheduledThreadPool(1);
        BackupRetentionController c1 =
                new BackupRetentionController(store1, autosaves, scheduler1, 60L);
        try {
            c1.saveAndApply(custom);
        } finally {
            c1.shutdown();
        }

        // Session 2: brand-new store + controller backed by the same file,
        // simulating an app restart. The persisted policy must be returned.
        BackupRetentionPolicyStore store2 = new BackupRetentionPolicyStore(globalPolicy);
        ScheduledExecutorService scheduler2 = Executors.newScheduledThreadPool(1);
        BackupRetentionController c2 =
                new BackupRetentionController(store2, autosaves, scheduler2, 60L);
        try {
            assertThat(c2.currentPolicy()).isEqualTo(custom);
        } finally {
            c2.shutdown();
        }
    }

    @Test
    void applyNowOnMissingDirectoryIsNoOp(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("does-not-exist");
        Path globalPolicy = tempDir.resolve("backup-retention.json");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalPolicy);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        BackupRetentionController controller =
                new BackupRetentionController(store, missing, scheduler, 60L);
        try {
            assertThat(controller.applyNow()).isZero();
        } finally {
            controller.shutdown();
        }
    }
}
