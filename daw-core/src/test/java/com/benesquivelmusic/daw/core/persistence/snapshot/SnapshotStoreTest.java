package com.benesquivelmusic.daw.core.persistence.snapshot;

import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SnapshotStore} publishes a named snapshot through its
 * {@link AtomicFileWriter} (story 331): a forced copy that never replaces a
 * file under its name, so a failed publish leaves neither a snapshot nor its
 * temp, and a name another writer takes first moves the copy to the next
 * free name.
 */
class SnapshotStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneId.of("UTC"));
    private static final String PROJECT = "<daw-project>mix 3</daw-project>";

    @TempDir
    Path projectDir;

    @Test
    void aSnapshotWhosePublishFailsLeavesNoSnapshotAndNoTemp() throws IOException {
        Files.writeString(projectDir.resolve(SnapshotStore.PROJECT_FILE_NAME), PROJECT);
        IOException crash = new IOException("injected failure before the snapshot's publish");
        SnapshotStore store = new SnapshotStore(FIXED, new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    throw crash;
                }));

        assertThatThrownBy(() -> store.createSnapshot(projectDir, "Client Review")).isSameAs(crash);

        try (Stream<Path> entries = Files.list(projectDir.resolve(SnapshotStore.SNAPSHOTS_DIR))) {
            assertThat(entries).as("neither a snapshot nor its temp").isEmpty();
        }
    }

    @Test
    void aSnapshotNameTakenBeforeItsPublishMovesTheCopyToTheNextName() throws IOException {
        Files.writeString(projectDir.resolve(SnapshotStore.PROJECT_FILE_NAME), PROJECT);
        AtomicReference<Path> taken = new AtomicReference<>();
        SnapshotStore store = new SnapshotStore(FIXED, new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (taken.compareAndSet(null, target)) {
                        Files.writeString(target, "another writer's snapshot");
                    }
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                }));

        SnapshotStore.Snapshot snapshot = store.createSnapshot(projectDir, "Client Review");

        assertThat(taken.get().getFileName().toString()).endsWith("-client-review.daw");
        assertThat(taken.get()).as("the other writer's file is never overwritten")
                .hasContent("another writer's snapshot");
        assertThat(snapshot.path()).isEqualTo(
                taken.get().resolveSibling(taken.get().getFileName().toString().replace(".daw", "-1.daw")));
        assertThat(snapshot.path()).hasContent(PROJECT);
        try (Stream<Path> entries = Files.list(projectDir.resolve(SnapshotStore.SNAPSHOTS_DIR))) {
            assertThat(entries).containsExactlyInAnyOrder(taken.get(), snapshot.path());
        }
    }

    @Test
    void aSnapshotCarriesTheProjectFilesLastModifiedTime() throws IOException {
        Path projectFile = Files.writeString(projectDir.resolve(SnapshotStore.PROJECT_FILE_NAME), PROJECT);
        FileTime saved = FileTime.from(Instant.parse("2026-06-21T11:58:00Z"));
        Files.setLastModifiedTime(projectFile, saved);

        SnapshotStore.Snapshot snapshot = new SnapshotStore(FIXED).createSnapshot(projectDir, "Rough Mix");

        assertThat(snapshot.timestamp()).isEqualTo(saved.toInstant());
        assertThat(Files.getLastModifiedTime(snapshot.path())).isEqualTo(saved);
        assertThat(snapshot.sizeBytes()).isEqualTo(Files.size(projectFile));
    }
}
