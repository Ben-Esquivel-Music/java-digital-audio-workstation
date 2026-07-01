package com.benesquivelmusic.daw.core.persistence.snapshot;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SnapshotNeverAutoDeletesTest {

    @TempDir
    Path tmp;

    @Test
    void namedSnapshotsSurviveCheckpointAndAutosaveRetentionCleanup() throws IOException {
        Path projectDir = Files.createDirectories(tmp.resolve("song"));
        Path projectFile = projectDir.resolve(SnapshotStore.PROJECT_FILE_NAME);
        Clock fixed = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"),
                ZoneId.of("UTC"));
        SnapshotStore store = new SnapshotStore(fixed);

        List<Path> snapshotFiles = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String content = "<project>snapshot-" + i + "</project>";
            contents.add(content);
            Files.writeString(projectFile, content, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(projectFile,
                    FileTime.from(Instant.parse("2026-06-21T12:00:0" + i + "Z")));
            snapshotFiles.add(store.createSnapshot(projectDir, "Client Review " + i).path());
        }

        CheckpointManager checkpointManager = new CheckpointManager(
                new AutoSaveConfig(Duration.ofHours(1), 2, true));
        checkpointManager.setProjectDataSupplier(() -> "<project>checkpoint</project>");
        checkpointManager.start(projectDir);
        try {
            for (int i = 0; i < 6; i++) {
                checkpointManager.performCheckpoint();
            }
        } finally {
            checkpointManager.stop();
        }

        Path checkpointDir = projectDir.resolve("checkpoints");
        Path staleAutosave = checkpointDir.resolve("autosave-old.daw");
        Files.writeString(staleAutosave, "old", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(staleAutosave,
                FileTime.from(Instant.parse("2026-06-01T12:00:00Z")));

        SnapshotBrowserService browser = new SnapshotBrowserService(
                Duration.ofDays(1), fixed);
        browser.addAutosaveDirectory(checkpointDir);

        assertThat(browser.purgeExpiredAutosaves()).isEqualTo(1);
        assertThat(staleAutosave).doesNotExist();
        try (var checkpointFiles = Files.list(checkpointDir)) {
            assertThat(checkpointFiles
                    .filter(p -> p.getFileName().toString().startsWith("checkpoint"))
                    .toList())
                    .hasSize(2);
        }

        for (Path snapshotFile : snapshotFiles) {
            assertThat(snapshotFile).isRegularFile();
        }

        List<SnapshotStore.Snapshot> listed = store.listSnapshots(projectDir);
        assertThat(listed)
                .extracting(SnapshotStore.Snapshot::path)
                .containsExactlyInAnyOrderElementsOf(snapshotFiles);
        assertThat(listed)
                .extracting(SnapshotStore.Snapshot::name,
                        SnapshotStore.Snapshot::sizeBytes)
                .containsExactlyInAnyOrder(
                        tuple("client review 0", byteLength(contents.get(0))),
                        tuple("client review 1", byteLength(contents.get(1))),
                        tuple("client review 2", byteLength(contents.get(2))),
                        tuple("client review 3", byteLength(contents.get(3))),
                        tuple("client review 4", byteLength(contents.get(4))));

        try (var snapshotDirFiles = Files.list(projectDir.resolve(SnapshotStore.SNAPSHOTS_DIR))) {
            assertThat(snapshotDirFiles
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList())
                    .as("snapshot writes should publish atomically without leftover temp files")
                    .isEmpty();
        }
    }

    private static long byteLength(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
