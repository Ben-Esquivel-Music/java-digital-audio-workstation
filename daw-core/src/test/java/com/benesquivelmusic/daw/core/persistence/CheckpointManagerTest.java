package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStartAndStop() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofMinutes(5), 10, true);
        CheckpointManager manager = new CheckpointManager(config);

        assertThat(manager.isRunning()).isFalse();

        manager.start(tempDir);
        assertThat(manager.isRunning()).isTrue();

        manager.stop();
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void shouldNotStartWhenDisabled() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofMinutes(5), 10, false);
        CheckpointManager manager = new CheckpointManager(config);

        manager.start(tempDir);
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void shouldPerformManualCheckpoint() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        manager.performCheckpoint();
        assertThat(manager.getCheckpointCount()).isEqualTo(1);
        assertThat(manager.getCheckpointFiles()).hasSize(1);

        Path checkpointFile = manager.getCheckpointFiles().getFirst();
        assertThat(checkpointFile).exists();
        assertThat(checkpointFile.getFileName().toString()).startsWith("checkpoint-001-");

        manager.stop();
    }

    @Test
    void shouldPerformMultipleCheckpoints() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        manager.performCheckpoint();
        manager.performCheckpoint();
        manager.performCheckpoint();

        assertThat(manager.getCheckpointCount()).isEqualTo(3);
        assertThat(manager.getCheckpointFiles()).hasSize(3);

        manager.stop();
    }

    @Test
    void shouldPruneOldCheckpoints() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 2, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        manager.performCheckpoint();
        manager.performCheckpoint();
        manager.performCheckpoint();

        assertThat(manager.getCheckpointCount()).isEqualTo(3);
        assertThat(manager.getCheckpointFiles()).hasSize(2);

        manager.stop();
    }

    @Test
    void shouldNotifyListeners() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        List<String> beforeEvents = new ArrayList<>();
        List<String> afterEvents = new ArrayList<>();

        manager.addListener(new AutoSaveListener() {
            @Override
            public void onBeforeCheckpoint(String checkpointId) {
                beforeEvents.add(checkpointId);
            }

            @Override
            public void onAfterCheckpoint(String checkpointId) {
                afterEvents.add(checkpointId);
            }

            @Override
            public void onCheckpointFailed(String checkpointId, Throwable cause) {
            }
        });

        manager.performCheckpoint();

        assertThat(beforeEvents).hasSize(1);
        assertThat(afterEvents).hasSize(1);

        manager.stop();
    }

    @Test
    void shouldCreateCheckpointDirectory() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        manager.performCheckpoint();

        assertThat(tempDir.resolve("checkpoints")).isDirectory();

        manager.stop();
    }

    @Test
    void shouldReturnConfig() {
        AutoSaveConfig config = AutoSaveConfig.LONG_SESSION;
        CheckpointManager manager = new CheckpointManager(config);

        assertThat(manager.getConfig()).isSameAs(config);
    }

    @Test
    void shouldIgnoreCheckpointWithoutProjectDirectory() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);

        manager.performCheckpoint();

        assertThat(manager.getCheckpointCount()).isZero();
    }

    @Test
    void shouldWriteCheckpointContent() throws Exception {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        manager.performCheckpoint();

        Path checkpointFile = manager.getCheckpointFiles().getFirst();
        String content = Files.readString(checkpointFile);
        assertThat(content).contains("# DAW Checkpoint");
        assertThat(content).contains("id=chk-1-");
        assertThat(content).contains("index=1");

        manager.stop();
    }

    // ── Reconfigure ──────────────────────────────────────────────────────────

    @Test
    void shouldReconfigureWhileRunning() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofMinutes(5), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);
        assertThat(manager.isRunning()).isTrue();

        AutoSaveConfig newConfig = new AutoSaveConfig(Duration.ofSeconds(30), 20, true);
        manager.reconfigure(newConfig);

        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.getConfig()).isSameAs(newConfig);
        assertThat(manager.getConfig().autoSaveInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(manager.getConfig().maxCheckpoints()).isEqualTo(20);

        manager.stop();
    }

    @Test
    void shouldReconfigureWhileStopped() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofMinutes(5), 10, true);
        CheckpointManager manager = new CheckpointManager(config);

        AutoSaveConfig newConfig = new AutoSaveConfig(Duration.ofSeconds(60), 5, true);
        manager.reconfigure(newConfig);

        assertThat(manager.isRunning()).isFalse();
        assertThat(manager.getConfig()).isSameAs(newConfig);
    }

    @Test
    void shouldRejectNullConfigOnReconfigure() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofMinutes(5), 10, true);
        CheckpointManager manager = new CheckpointManager(config);

        assertThatThrownBy(() -> manager.reconfigure(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPreserveCheckpointsAfterReconfigure() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);
        manager.start(tempDir);

        manager.performCheckpoint();
        assertThat(manager.getCheckpointCount()).isEqualTo(1);

        AutoSaveConfig newConfig = new AutoSaveConfig(Duration.ofSeconds(30), 10, true);
        manager.reconfigure(newConfig);

        assertThat(manager.getCheckpointCount()).isEqualTo(1);
        assertThat(manager.getCheckpointFiles()).hasSize(1);

        manager.stop();
    }
}
