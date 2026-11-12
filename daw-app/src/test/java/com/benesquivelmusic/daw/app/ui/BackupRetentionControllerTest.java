package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 335 (studio ruling R1) — {@link BackupRetentionController} delivers
 * the checkpoint retention policy to the {@link CheckpointManager} and keeps
 * it in the policy store; it targets no directory and prunes nothing itself.
 * Startup delivers the stored policy, Apply delivers now and saves off the
 * caller's thread with saves chained so the last Apply wins, a failed save is
 * reported through the Settings adapter, and the application's exit waits for
 * a save still running ({@code PendingWrites}).
 *
 * <p>No JavaFX toolkit is needed: the saves run on an injected executor.</p>
 */
class BackupRetentionControllerTest {

    private static final BackupRetentionPolicy CUSTOM = new BackupRetentionPolicy(
            3, 5, 7, 2, Duration.ofDays(42), 123L * 1024L * 1024L);

    @Test
    void startupDeliversTheDefaultWhenNothingIsStored(@TempDir Path tempDir) {
        CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(tempDir.resolve("backup-retention.json")), checkpoints, Runnable::run);

        assertThat(checkpoints.getRetentionPolicy()).as("a manager never given a policy uses the cap").isEmpty();
        assertThat(controller.deliverStored()).contains(BackupRetentionPolicy.DEFAULT);
        assertThat(checkpoints.getRetentionPolicy()).contains(BackupRetentionPolicy.DEFAULT);
        assertThat(controller.currentPolicy()).isEqualTo(BackupRetentionPolicy.DEFAULT);
    }

    @Test
    void startupDeliversTheStoredPolicyAcrossARestart(@TempDir Path tempDir) {
        Path file = tempDir.resolve("backup-retention.json");
        BackupRetentionController first = new BackupRetentionController(
                new BackupRetentionPolicyStore(file), new CheckpointManager(AutoSaveConfig.DEFAULT), Runnable::run);
        first.deliverAndSave(CUSTOM).join();

        CheckpointManager restarted = new CheckpointManager(AutoSaveConfig.DEFAULT);
        BackupRetentionController second = new BackupRetentionController(
                new BackupRetentionPolicyStore(file), restarted, Runnable::run);

        assertThat(second.deliverStored()).contains(CUSTOM);
        assertThat(restarted.getRetentionPolicy()).contains(CUSTOM);
    }

    @Test
    void applyDeliversNowAndSavesOnTheSaveExecutorWithTheLastApplyWinning(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("backup-retention.json");
        CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
        Queue<Runnable> saves = new ArrayDeque<>();
        Executor queued = saves::add;
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(file), checkpoints, queued);
        BackupRetentionPolicy second = CUSTOM.withKeepRecent(9);

        CompletableFuture<Void> firstSave = controller.deliverAndSave(CUSTOM);
        assertThat(checkpoints.getRetentionPolicy()).as("delivered at once, before any save ran").contains(CUSTOM);
        CompletableFuture<Void> secondSave = controller.deliverAndSave(second);
        assertThat(checkpoints.getRetentionPolicy()).contains(second);
        assertThat(file).as("nothing saved on the caller's thread").doesNotExist();
        assertThat(saves).as("the second save waits for the first").hasSize(1);

        saves.remove().run();
        assertThat(firstSave).isCompleted();
        assertThat(BackupRetentionPolicyStore.load(file)).contains(CUSTOM);
        saves.remove().run();
        assertThat(secondSave).isCompleted();
        assertThat(BackupRetentionPolicyStore.load(file)).as("the last Apply's policy is on disk").contains(second);
        assertThat(controller.currentPolicy()).isEqualTo(second);
    }

    @Test
    void aFailedSaveCompletesExceptionallyKeepsTheDeliveryAndReachesTheAdapterCallback(@TempDir Path tempDir)
            throws IOException {
        Path notADirectory = Files.writeString(tempDir.resolve("blocker"), "x");
        CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(notADirectory.resolve("backup-retention.json")), checkpoints,
                Runnable::run);

        CompletableFuture<Void> save = controller.deliverAndSave(CUSTOM);
        assertThat(save).isCompletedExceptionally();
        assertThatThrownBy(save::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(UncheckedIOException.class);
        assertThat(checkpoints.getRetentionPolicy()).as("it applies until the application quits").contains(CUSTOM);

        AtomicInteger failures = new AtomicInteger();
        BackupSettingsAccess access = controller.settingsAccess(Optional::empty, failures::incrementAndGet);
        access.applyPolicy(CUSTOM.withKeepDaily(1));
        assertThat(failures).as("the adapter reports the failed save once").hasValue(1);
        assertThat(checkpoints.getRetentionPolicy()).contains(CUSTOM.withKeepDaily(1));

        assertThat(controller.deliverAndSave(CUSTOM.withKeepDaily(2)))
                .as("a failed save does not stop the next one from running").isCompletedExceptionally();
    }

    @Test
    void theAdapterReadsTheEnforcedPolicyAndTheProjectFolderAndAGoodSaveReportsNothing(@TempDir Path tempDir) {
        CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(tempDir.resolve("backup-retention.json")), checkpoints, Runnable::run);
        controller.deliverStored();
        AtomicInteger failures = new AtomicInteger();
        BackupSettingsAccess access = controller.settingsAccess(() -> Optional.of(tempDir), failures::incrementAndGet);

        assertThat(access.currentPolicy()).isEqualTo(BackupRetentionPolicy.DEFAULT);
        assertThat(access.projectDirectory()).contains(tempDir);
        access.applyPolicy(CUSTOM);
        assertThat(access.currentPolicy()).as("the policy the checkpoint manager enforces").isEqualTo(CUSTOM);
        assertThat(failures).hasValue(0);
    }

    @Test
    void theExitWaitsForASaveStillRunning(@TempDir Path tempDir) throws InterruptedException {
        Queue<Runnable> saves = new ArrayDeque<>();
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(tempDir.resolve("backup-retention.json")),
                new CheckpointManager(AutoSaveConfig.DEFAULT), saves::add);
        assertThat(controller.pending()).isFalse();
        assertThat(controller.awaitUntil(System.nanoTime())).isTrue();

        controller.deliverAndSave(CUSTOM);
        assertThat(controller.pending()).isTrue();
        assertThat(controller.awaitUntil(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)))
                .as("a deadline that passes while the save is still queued").isFalse();

        saves.remove().run();
        assertThat(controller.pending()).isFalse();
        assertThat(controller.awaitUntil(System.nanoTime())).isTrue();
    }
}
