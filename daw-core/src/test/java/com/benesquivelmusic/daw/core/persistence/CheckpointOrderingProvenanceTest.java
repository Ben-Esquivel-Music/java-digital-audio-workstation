package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryScanner;
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
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class CheckpointOrderingProvenanceTest {
    @TempDir Path folder;

    private Path write(String name) throws IOException {
        return Files.writeString(Files.createDirectories(folder.resolve("checkpoints")).resolve(name), "checkpoint");
    }

    private String newest() throws IOException {
        return new RecoveryScanner().newestCheckpoint(ProjectContext.forProject(folder).checkpointDirectory())
                .orElseThrow().getFileName().toString();
    }

    private CheckpointManager manager(String time) {
        return new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false), new AtomicFileWriter(),
                InstantSource.fixed(Instant.parse(time)), Files::delete, Duration.ofSeconds(5));
    }

    @Test void legacyRepeatedIndicesKeepStampOrder() throws Exception {
        write("checkpoint-001-20261024T090000.daw");
        write("checkpoint-050-20261024T120000.daw");
        write("checkpoint-001-20261025T090000.daw");
        write("checkpoint-003-20261025T090100.daw");
        assertThat(newest()).isEqualTo("checkpoint-003-20261025T090100.daw");
    }

    @Test void legacyIndicesThatNeverRepeatStillKeepStampOrderUntilAFactCoversANewWrite() throws Exception {
        write("checkpoint-011-20261024T120000.daw");
        write("checkpoint-060-20261024T130000.daw");
        write("checkpoint-001-20261025T090000.daw");
        write("checkpoint-005-20261025T090100.daw");
        assertThat(newest()).isEqualTo("checkpoint-005-20261025T090100.daw");
        CheckpointManager manager = manager("2000-01-01T00:00:00Z");
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            Path added = manager.writeCheckpoint("new checkpoint", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(CheckpointFileName.parse(added.getFileName().toString()).orElseThrow().index()).isEqualTo(61);
            assertThat(newest()).isEqualTo(added.getFileName().toString());
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).containsExactly(added.getFileName().toString());
        } finally { manager.stop(); }
    }

    @Test void recordedIndicesOrderTheRepeatedDaylightSavingHourAndSurviveRestart() throws Exception {
        Path early = write("checkpoint-061-20261101T015500.daw");
        Path late = write("checkpoint-062-20261101T010500.daw");
        CheckpointOrder.record(folder.resolve("checkpoints"), Set.of(early.getFileName().toString(), late.getFileName().toString()));
        assertThat(newest()).isEqualTo(late.getFileName().toString());
        CheckpointManager manager = manager("2000-01-01T00:00:00Z");
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            assertThat(manager.getCheckpointFiles().getLast()).isEqualTo(late);
            Path added = manager.writeCheckpoint("next", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(newest()).isEqualTo(added.getFileName().toString());
        } finally { manager.stop(); }
    }

    @Test void failedEnumerationDoesNotCertifyItsLaterWritesEvenAboveAnExistingCoveredIndex() throws Exception {
        Path visible = write("checkpoint-061-20261025T010000.daw");
        write("checkpoint-090-20261025T020000.daw");
        CheckpointOrder.record(folder.resolve("checkpoints"), Set.of(visible.getFileName().toString()));
        CheckpointManager manager = manager("2000-01-01T00:00:00Z");
        manager.setDirectoryReader(_ -> new DirectoryStream<>() {
            @Override public Iterator<Path> iterator() { return List.of(visible).iterator(); }
            @Override public void close() throws IOException { throw new IOException("listing failed after partial results"); }
        });
        try {
            assertThatThrownBy(() -> manager.start(folder).get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            Path untrusted = manager.writeCheckpoint("partial seed", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).doesNotContain(untrusted.getFileName().toString());
            assertThat(newest()).isEqualTo(visible.getFileName().toString());
        } finally { manager.stop(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedEnumerationRevokesAGhostCertificationBeforePublishingTheSameName(boolean priorFileExists) throws Exception {
        Path visible = write("checkpoint-061-20000101T000000.daw");
        String ghost = CheckpointFileName.of(62L, Instant.parse("2000-01-01T00:00:00Z"), java.time.ZoneId.systemDefault()).fileName();
        if (priorFileExists) write(ghost);
        CheckpointOrder.record(folder.resolve("checkpoints"), Set.of(ghost));
        CheckpointManager manager = manager("2000-01-01T00:00:00Z");
        manager.setDirectoryReader(_ -> new DirectoryStream<>() {
            @Override public Iterator<Path> iterator() { return List.of(visible).iterator(); }
            @Override public void close() throws IOException { throw new IOException("partial"); }
        });
        try {
            assertThatThrownBy(() -> manager.start(folder).get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            Path written = manager.writeCheckpoint("untrusted", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS);
            assertThat(written.getFileName().toString()).isEqualTo(ghost);
            assertThat(CheckpointOrder.read(folder.resolve("checkpoints"))).doesNotContain(ghost);
        } finally { manager.stop(); }
    }

    @Test void malformedFactFallsBackToStampOrderWithoutInferringTrust() throws Exception {
        write("checkpoint-061-20261024T000000.daw");
        write("checkpoint-005-20261025T000000.daw");
        Files.writeString(folder.resolve("checkpoints").resolve(CheckpointOrder.FILE_NAME), "invalid ordering fact");
        assertThat(newest()).isEqualTo("checkpoint-005-20261025T000000.daw");
    }

    @Test void anUnreadableFactFallsBackToLegacyStampOrder() throws Exception {
        write("checkpoint-061-20261024T000000.daw");
        write("checkpoint-005-20261025T000000.daw");
        Files.createDirectory(folder.resolve("checkpoints").resolve(CheckpointOrder.FILE_NAME));
        assertThat(newest()).isEqualTo("checkpoint-005-20261025T000000.daw");
    }

    @Test void failedAtomicFactPublicationCannotCertifyOrPublishTheNewCheckpoint() throws Exception {
        Path prior = write("checkpoint-061-20261025T000000.daw");
        CheckpointOrder.record(folder.resolve("checkpoints"), Set.of(prior.getFileName().toString()));
        Path fact = folder.resolve("checkpoints").resolve(CheckpointOrder.FILE_NAME);
        String original = Files.readString(fact);
        AtomicFileWriter failing = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (target.getFileName().toString().equals(CheckpointOrder.FILE_NAME)) throw new IOException("fact publish refused");
                    Files.move(temp, target, options);
                });
        CheckpointManager manager = new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false), failing,
                InstantSource.fixed(Instant.parse("2000-01-01T00:00:00Z")), Files::delete, Duration.ofSeconds(5));
        try {
            manager.start(folder).get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> manager.writeCheckpoint("new", 1L, CheckpointOrigin.USER).get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IOException.class);
            assertThat(Files.readString(fact)).isEqualTo(original);
            assertThat(manager.getCheckpointFiles()).containsExactly(prior);
            assertThat(newest()).isEqualTo(prior.getFileName().toString());
        } finally { manager.stop(); }
    }
}
