package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/** A fast writer may report seeding before start returns, but its generation is already public. */
class CheckpointStartPublicationTest {

    @TempDir
    Path projectDirectory;

    private final CheckpointManager manager = new CheckpointManager(
            new AutoSaveConfig(Duration.ofHours(1), 10, false), new AtomicFileWriter(),
            InstantSource.fixed(Instant.parse("2026-10-25T12:00:00Z")), Files::delete,
            CheckpointManager.STOP_WAIT, Runnable::run);

    @AfterEach
    void stop() {
        manager.stop();
    }

    @Test
    void aSeededFactAlreadyNamesTheRunningGenerationEvenBeforeStartReturns() throws Exception {
        Path checkpoints = Files.createDirectories(projectDirectory.resolve("checkpoints"));
        Path newest = Files.writeString(checkpoints.resolve("checkpoint-047-20261022T181000.daw"), document(47));
        List<CheckpointEvent> events = new ArrayList<>();
        List<OptionalLong> generationsAtDelivery = new ArrayList<>();
        manager.addEventListener(event -> {
            events.add(event);
            generationsAtDelivery.add(manager.runningGeneration());
        });

        manager.start(projectDirectory).get(5, TimeUnit.SECONDS);

        long generation = manager.runningGeneration().orElseThrow();
        assertThat(events).containsExactly(new CheckpointEvent.Seeded(generation, Optional.of(newest)));
        assertThat(generationsAtDelivery).containsExactly(OptionalLong.of(generation));
    }

    @Test
    void aRequestMadeByTheSeededListenerRunsAfterSeedingInThatSameGeneration() throws Exception {
        Path checkpoints = Files.createDirectories(projectDirectory.resolve("checkpoints"));
        Files.writeString(checkpoints.resolve("checkpoint-047-20261022T181000.daw"), document(47));
        List<CheckpointEvent> events = new ArrayList<>();
        AtomicReference<CompletableFuture<Path>> requested = new AtomicReference<>();
        manager.addEventListener(event -> {
            events.add(event);
            if (event instanceof CheckpointEvent.Seeded) {
                requested.set(manager.writeCheckpoint(document(48), 48, CheckpointOrigin.USER));
            }
        });

        manager.start(projectDirectory).get(5, TimeUnit.SECONDS);

        Path written = requested.get().get(5, TimeUnit.SECONDS);
        assertThat(CheckpointFileName.parse(written.getFileName().toString()).orElseThrow().index()).isEqualTo(48);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).isInstanceOf(CheckpointEvent.Seeded.class);
        assertThat(events.getLast()).isInstanceOf(CheckpointEvent.Written.class);
        assertThat(events).extracting(CheckpointEvent::generation)
                .containsOnly(manager.runningGeneration().orElseThrow());
    }
}
