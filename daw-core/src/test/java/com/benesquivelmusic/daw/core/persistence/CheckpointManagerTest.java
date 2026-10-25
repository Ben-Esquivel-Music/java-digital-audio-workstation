package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointManagerTest {

    private static final long TIMEOUT_SECONDS = 10;

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
    void shouldWriteARequestedCheckpoint() throws Exception {
        CheckpointManager manager = startedManager(10);

        Path written = await(manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE));

        assertThat(manager.getCheckpointCount()).isEqualTo(1);
        assertThat(manager.getCheckpointFiles()).containsExactly(written);
        assertThat(written).exists();
        assertThat(written.getFileName().toString()).startsWith("checkpoint-001-");

        manager.stop();
    }

    @Test
    void shouldWriteMultipleCheckpoints() throws Exception {
        CheckpointManager manager = startedManager(10);

        for (long revision = 1; revision <= 3; revision++) {
            await(manager.writeCheckpoint(document(revision), revision, CheckpointOrigin.AFTER_SAVE));
        }

        assertThat(manager.getCheckpointCount()).isEqualTo(3);
        assertThat(manager.getCheckpointFiles()).hasSize(3);

        manager.stop();
    }

    @Test
    void shouldPruneOldCheckpoints() throws Exception {
        CheckpointManager manager = startedManager(2);

        for (long revision = 1; revision <= 3; revision++) {
            await(manager.writeCheckpoint(document(revision), revision, CheckpointOrigin.AFTER_SAVE));
        }

        assertThat(manager.getCheckpointCount()).isEqualTo(3);
        assertThat(manager.getCheckpointFiles()).hasSize(2);

        manager.stop();
    }

    @Test
    void shouldNotifyListeners() throws Exception {
        CheckpointManager manager = startedManager(10);

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

        await(manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE));

        assertThat(beforeEvents).hasSize(1);
        assertThat(afterEvents).hasSize(1).isEqualTo(beforeEvents);

        manager.stop();
    }

    @Test
    void shouldCreateCheckpointDirectory() throws Exception {
        CheckpointManager manager = startedManager(10);

        await(manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE));

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
    void aCheckpointRequestedWithoutAProjectDirectoryFailsAndWritesNothing() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager manager = new CheckpointManager(config);

        CompletableFuture<Path> request = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);

        assertThat(request).isCompletedExceptionally();
        assertThat(request.exceptionNow()).isInstanceOf(IOException.class);
        assertThat(manager.getCheckpointCount()).isZero();
    }

    @Test
    void aCheckpointHoldsExactlyTheDocumentItWasGiven() throws Exception {
        CheckpointManager manager = startedManager(10);
        String xml = document(7);

        Path written = await(manager.writeCheckpoint(xml, 7, CheckpointOrigin.AFTER_SAVE));

        assertThat(Files.readString(written)).as("no summary text: the document, byte for byte").isEqualTo(xml);

        manager.stop();
    }

    @Test
    void aScheduledOriginIsTheCadencesOwnAndCannotBeRequested() {
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true));

        assertThatThrownBy(() -> manager.writeCheckpoint(document(1), 1, CheckpointOrigin.SCHEDULED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCheckpointWhosePublishFailsIsReportedAndLeavesNoFile() throws Exception {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        IOException crash = new IOException("injected failure before the checkpoint's publish");
        CheckpointManager manager = new CheckpointManager(config, new AtomicFileWriter(
                AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
                    throw crash;
                }));
        List<Throwable> failures = new ArrayList<>();
        manager.addListener(new AutoSaveListener() {
            @Override
            public void onBeforeCheckpoint(String checkpointId) {
            }

            @Override
            public void onAfterCheckpoint(String checkpointId) {
            }

            @Override
            public void onCheckpointFailed(String checkpointId, Throwable cause) {
                failures.add(cause);
            }
        });
        manager.start(tempDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Path> request = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);
        assertThat(request).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        manager.stop();

        assertThat(request.exceptionNow()).as("the original cause, same instance").isSameAs(crash);
        assertThat(failures).as("the writer's exception reaches the listener").containsExactly(crash);
        assertThat(manager.getCheckpointFiles()).isEmpty();
        try (Stream<Path> entries = Files.list(tempDir.resolve("checkpoints"))) {
            assertThat(entries).as("neither a checkpoint nor its temp").isEmpty();
        }
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
    void aConfigurationThatDisablesAutosaveStopsTheCheckpoints() {
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofMinutes(5), 10, true));
        manager.start(tempDir);

        manager.reconfigure(new AutoSaveConfig(Duration.ofMinutes(5), 10, false));

        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void shouldRejectNullConfigOnReconfigure() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofMinutes(5), 10, true);
        CheckpointManager manager = new CheckpointManager(config);

        assertThatThrownBy(() -> manager.reconfigure(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPreserveCheckpointsAfterReconfigure() throws Exception {
        CheckpointManager manager = startedManager(10);

        await(manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE));
        assertThat(manager.getCheckpointCount()).isEqualTo(1);

        AutoSaveConfig newConfig = new AutoSaveConfig(Duration.ofSeconds(30), 10, true);
        manager.reconfigure(newConfig);

        assertThat(manager.getCheckpointCount()).isEqualTo(1);
        assertThat(manager.getCheckpointFiles()).hasSize(1);

        manager.stop();
    }

    private CheckpointManager startedManager(int maxCheckpoints) throws Exception {
        CheckpointManager manager = new CheckpointManager(
                new AutoSaveConfig(Duration.ofHours(1), maxCheckpoints, true));
        manager.start(tempDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return manager;
    }

    private static String document(long revision) {
        return "<daw-project revision=\"" + revision + "\"/>\n";
    }

    private static Path await(CompletableFuture<Path> checkpoint) throws Exception {
        return checkpoint.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
