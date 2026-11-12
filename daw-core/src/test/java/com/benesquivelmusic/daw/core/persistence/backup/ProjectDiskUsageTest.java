package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.core.persistence.CheckpointOrder;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The disk usage of a project (story 335, ruling R2): checkpoints are exactly
 * the files a retention policy keeps or deletes — names the checkpoint
 * grammar accepts, directly in {@code checkpoints/} — counted and summed;
 * snapshots and archives are their trees; everything else, including the
 * ordering fact, a writer's temp and a stray file in {@code checkpoints/},
 * is other.
 */
class ProjectDiskUsageTest {

    @Test
    void zeroForMissingDirectory() throws IOException {
        ProjectDiskUsage u = ProjectDiskUsage.compute(Path.of("nonexistent-" + System.nanoTime()));
        assertThat(u.totalBytes()).isZero();
        assertThat(u.checkpointCount()).isZero();
    }

    @Test
    void splitsBytesAcrossFourCategoriesQuickCheck(@TempDir Path tempDir) throws IOException {
        Path checkpoints = Files.createDirectories(tempDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR));
        Path snapshots = Files.createDirectories(tempDir.resolve(SnapshotStore.SNAPSHOTS_DIR).resolve("take-1"));
        Path archives = Files.createDirectories(tempDir.resolve(ProjectDiskUsage.ARCHIVES_DIR));
        Files.writeString(checkpoints.resolve("checkpoint-001-20260101T120000.daw"), "ABCDE"); // 5
        Files.writeString(snapshots.resolve("project.daw"), "ABCD");                         // 4
        Files.writeString(archives.resolve("a1.zip"), "ABC");                                // 3
        Files.writeString(tempDir.resolve("project.daw"), "ABCDEFG");                        // 7
        ProjectDiskUsage u = ProjectDiskUsage.compute(tempDir);
        assertThat(u).isEqualTo(new ProjectDiskUsage(5, 1, 4, 3, 7));
        assertThat(u.totalBytes()).isEqualTo(19);
    }

    @Test
    void countsOnlyCheckpointNamesAsCheckpointsAndEverythingElseAsOther(@TempDir Path tempDir) throws IOException {
        Path checkpoints = Files.createDirectories(tempDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR));
        Path archives = Files.createDirectories(tempDir.resolve(ProjectDiskUsage.ARCHIVES_DIR));
        Path audio = Files.createDirectories(tempDir.resolve("audio"));
        Files.writeString(checkpoints.resolve("checkpoint-001-20260101T120000.daw"), "ABCDE");    // 5
        Files.writeString(checkpoints.resolve("checkpoint-002-20260101T120500.daw"), "ABCDEF");   // 6
        Files.writeString(checkpoints.resolve(CheckpointOrder.FILE_NAME), "fact");               // 4, other
        Files.writeString(checkpoints.resolve(".checkpoint-003-20260101T121000.daw.0123456789abcdef.tmp"),
                "tmp");                                                                          // 3, other
        Files.writeString(checkpoints.resolve("notes.txt"), "no");                              // 2, other
        Files.createDirectories(checkpoints.resolve("checkpoint-004-20260101T121500.daw")); // a directory, no checkpoint
        Files.writeString(archives.resolve("a1.zip"), "ABC");                                    // 3
        Files.writeString(tempDir.resolve("project.daw"), "1234567");                            // 7, other
        Files.writeString(audio.resolve("clip.wav"), "12");                                     // 2, other
        ProjectDiskUsage u = ProjectDiskUsage.compute(tempDir);
        assertThat(u.checkpointBytes()).isEqualTo(11);
        assertThat(u.checkpointCount()).isEqualTo(2);
        assertThat(u.snapshotBytes()).isZero();
        assertThat(u.archiveBytes()).isEqualTo(3);
        assertThat(u.otherBytes()).isEqualTo(4 + 3 + 2 + 7 + 2);
        assertThat(u.totalBytes()).isEqualTo(32);
    }

    /**
     * A checkpoint write or prune racing the scan is normal: a checkpoint a
     * prune deletes and a writer's temp renamed into place vanish between the
     * listing of their folder and the read of their attributes, and a folder
     * goes after its attributes were read. Each counts as absent; the scan
     * succeeds with what is still there.
     */
    @Test
    void aFileOrFolderThatVanishesDuringTheWalkCountsAsAbsent(@TempDir Path tempDir) throws IOException {
        Path checkpoints = Files.createDirectories(tempDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR));
        Path audio = Files.createDirectories(tempDir.resolve("audio"));
        Path pruned = Files.writeString(checkpoints.resolve("checkpoint-001-20260101T120000.daw"), "ABCDE");
        Files.writeString(checkpoints.resolve("checkpoint-002-20260101T120500.daw"), "ABCDEF");          // 6
        Path renamed = Files.writeString(
                checkpoints.resolve(".checkpoint-003-20260101T121000.daw.0123456789abcdef.tmp"), "tmp");
        Path clip = Files.writeString(audio.resolve("clip.wav"), "12");
        Files.writeString(tempDir.resolve("project.daw"), "1234567");                                    // 7
        Set<Path> vanishing = Set.of(pruned, renamed);
        List<Path> read = new ArrayList<>();
        ProjectDiskUsage.AttributeReader racing = entry -> {
            read.add(entry);
            if (vanishing.contains(entry)) {
                Files.delete(entry);
            }
            BasicFileAttributes attributes =
                    Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (entry.equals(audio)) {
                Files.delete(clip);
                Files.delete(audio);
            }
            return attributes;
        };

        ProjectDiskUsage u = ProjectDiskUsage.compute(tempDir, racing);

        assertThat(read).as("the walk reached every entry that vanished").contains(pruned, renamed, audio);
        assertThat(u).isEqualTo(new ProjectDiskUsage(6, 1, 0, 0, 7));
    }

    @Test
    void anEntryWhoseAttributesCannotBeReadFailsTheScan(@TempDir Path tempDir) throws IOException {
        Path checkpoints = Files.createDirectories(tempDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR));
        Path checkpoint = Files.writeString(checkpoints.resolve("checkpoint-001-20260101T120000.daw"), "ABCDE");
        AccessDeniedException denied = new AccessDeniedException(checkpoint.toString());
        ProjectDiskUsage.AttributeReader refusing = entry -> {
            if (entry.equals(checkpoint)) {
                throw denied;
            }
            return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        };

        assertThatThrownBy(() -> ProjectDiskUsage.compute(tempDir, refusing))
                .as("a file that is there but cannot be read is no zero").isSameAs(denied);
    }
}
