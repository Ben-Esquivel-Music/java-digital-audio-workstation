package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointCertificationCompactionTest {
    @TempDir Path folder;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataRemainsBoundedAcrossManyCapOrPolicyPrunes(boolean policy) throws Exception {
        List<Integer> factSizes = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
            if (target.getFileName().toString().equals(CheckpointOrder.FILE_NAME)) factSizes.add(Files.readAllLines(temp).size());
            Files.move(temp, target, options);
        });
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), policy ? 100 : 3, false), writer);
        if (policy) manager.setRetentionPolicy(new BackupRetentionPolicy(0, 0, 0, 0, Duration.ZERO, 0L));
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            for (int revision = 0; revision < 24; revision++) {
                manager.writeCheckpoint("capture " + revision, revision, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
                Set<String> certified = CheckpointOrder.read(folder.resolve("checkpoints"));
                assertThat(certified).containsExactlyInAnyOrderElementsOf(manager.getCheckpointFiles().stream()
                        .map(file -> file.getFileName().toString()).toList());
                assertThat(certified.size()).isLessThanOrEqualTo(policy ? 1 : 3);
            }
            assertThat(factSizes).allMatch(size -> size <= (policy ? 3 : 5));
            assertThat(folder.resolve("checkpoints").resolve(CheckpointOrder.FILE_NAME)).isRegularFile();
        } finally { manager.stop(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyCompleteEnumerationCompactsAbsentCertification(boolean partial) throws Exception {
        Path checkpoints = Files.createDirectories(folder.resolve("checkpoints"));
        Path visible = Files.writeString(checkpoints.resolve("checkpoint-001-20261026T120000.daw"), "visible");
        String absent = "checkpoint-002-20261026T120001.daw";
        CheckpointOrder.record(checkpoints, Set.of(visible.getFileName().toString(), absent));
        CheckpointManager manager = new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false));
        if (partial) manager.setDirectoryReader(_ -> new DirectoryStream<>() {
            @Override public Iterator<Path> iterator() { return List.of(visible).iterator(); }
            @Override public void close() throws IOException { throw new IOException("partial listing"); }
        });
        try {
            if (partial) assertThatThrownBy(() -> manager.start(folder).get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            else manager.start(folder).get(10, TimeUnit.SECONDS);
            Set<String> certified = CheckpointOrder.read(checkpoints);
            assertThat(certified).contains(visible.getFileName().toString());
            assertThat(certified.contains(absent)).isEqualTo(partial);
        } finally { manager.stop(); }
    }

    @Test
    void failedCompactionRetriesWithoutRevokingTheCheckpointBeingPublished() throws Exception {
        AtomicReference<String> pruned = new AtomicReference<>();
        AtomicBoolean failCompaction = new AtomicBoolean();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
            if (target.getFileName().toString().equals(CheckpointOrder.FILE_NAME) && failCompaction.get()
                    && !Files.readString(temp).contains(pruned.get())) throw new IOException("compaction publication refused");
            Files.move(temp, target, options);
        });
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 1, false), writer);
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            Path first = manager.writeCheckpoint("first", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            pruned.set(first.getFileName().toString());
            failCompaction.set(true);
            Path second = manager.writeCheckpoint("second", 2L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(first).doesNotExist();
            assertThat(second).exists();
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints")))
                    .contains(first.getFileName().toString(), second.getFileName().toString());
            failCompaction.set(false);
            Path third = manager.writeCheckpoint("third", 3L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).containsExactly(third.getFileName().toString());
        } finally { manager.stop(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDeleteKeepsItsCertificationUntilAConfirmedRetry(boolean policy) throws Exception {
        AtomicBoolean refuse = new AtomicBoolean(true);
        AtomicReference<Path> protectedFile = new AtomicReference<>();
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), policy ? 100 : 1, false),
                new AtomicFileWriter(), InstantSource.fixed(Instant.parse("2026-10-26T12:00:00Z")), file -> {
                    if (file.equals(protectedFile.get()) && refuse.get()) throw new IOException("delete refused");
                    Files.delete(file);
                }, Duration.ofSeconds(5));
        if (policy) manager.setRetentionPolicy(new BackupRetentionPolicy(0, 0, 0, 0, Duration.ZERO, 0L));
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            Path first = manager.writeCheckpoint("first", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            protectedFile.set(first);
            manager.writeCheckpoint("second", 2L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).contains(first.getFileName().toString());
            refuse.set(false);
            Path third = manager.writeCheckpoint("third", 3L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).containsExactly(third.getFileName().toString());
        } finally { manager.stop(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedPublicationRetainsOnlyPresentOrPendingCertifications(boolean publishedBeforeFailure) throws Exception {
        AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-26T12:00:00Z"));
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
            if (!target.getFileName().toString().equals(CheckpointOrder.FILE_NAME) && fail.get()) {
                if (publishedBeforeFailure) Files.move(temp, target, options);
                throw new IOException("checkpoint publication failed");
            }
            Files.move(temp, target, options);
        });
        CheckpointManager manager = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 1, false),
                writer, time::get, Files::delete, Duration.ofSeconds(5));
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            for (int attempt = 0; attempt < 5; attempt++) {
                time.updateAndGet(instant -> instant.plusSeconds(1));
                assertThatThrownBy(() -> manager.writeCheckpoint("failed", 1L, CheckpointOrigin.USER)
                        .get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
                Set<String> certified = CheckpointOrder.read(folder.resolve("checkpoints"));
                assertThat(certified).hasSize(publishedBeforeFailure ? attempt + 1 : 1);
                for (String name : certified) {
                    assertThat(Files.exists(folder.resolve("checkpoints").resolve(name))).isEqualTo(publishedBeforeFailure);
                }
            }
            fail.set(false);
            time.updateAndGet(instant -> instant.plusSeconds(1));
            Path successful = manager.writeCheckpoint("successful", 2L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            Set<String> certified = CheckpointOrder.read(folder.resolve("checkpoints"));
            assertThat(certified).contains(successful.getFileName().toString()).hasSize(publishedBeforeFailure ? 6 : 1);
        } finally { manager.stop(); }
    }
}
