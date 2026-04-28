package com.benesquivelmusic.daw.core.persistence.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDiskUsageTest {

    @Test
    void zeroForMissingDirectory() throws IOException {
        ProjectDiskUsage u = ProjectDiskUsage.compute(Path.of("nonexistent-" + System.nanoTime()));
        assertThat(u.totalBytes()).isZero();
    }

    @Test
    void splitsBytesAcrossThreeCategoriesQuickCheck(@TempDir Path tempDir) throws IOException {
        Path checkpoints = tempDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR);
        Path archives = tempDir.resolve(ProjectDiskUsage.ARCHIVES_DIR);
        Files.createDirectories(checkpoints);
        Files.createDirectories(archives);
        Files.writeString(checkpoints.resolve("c1.daw"), "ABCDE"); // 5
        Files.writeString(archives.resolve("a1.zip"), "ABC");       // 3
        Files.writeString(tempDir.resolve("project.daw"), "ABCDEFG"); // 7
        ProjectDiskUsage u = ProjectDiskUsage.compute(tempDir);
        assertThat(u.autosavesBytes()).isEqualTo(5);
        assertThat(u.archivesBytes()).isEqualTo(3);
        assertThat(u.assetsBytes()).isEqualTo(7);
    }

    @Test
    void countsAssetsAsEverythingOutsideCheckpointsAndArchives(@TempDir Path tempDir) throws IOException {
        Path checkpoints = tempDir.resolve(ProjectDiskUsage.AUTOSAVES_DIR);
        Path archives = tempDir.resolve(ProjectDiskUsage.ARCHIVES_DIR);
        Path audio = tempDir.resolve("audio");
        Files.createDirectories(checkpoints);
        Files.createDirectories(archives);
        Files.createDirectories(audio);
        Files.writeString(checkpoints.resolve("c1.daw"), "ABCDE"); // 5
        Files.writeString(archives.resolve("a1.zip"), "ABC");       // 3
        Files.writeString(tempDir.resolve("project.daw"), "1234567"); // 7
        Files.writeString(audio.resolve("clip.wav"), "12");          // 2
        ProjectDiskUsage u = ProjectDiskUsage.compute(tempDir);
        assertThat(u.autosavesBytes()).isEqualTo(5);
        assertThat(u.archivesBytes()).isEqualTo(3);
        assertThat(u.assetsBytes()).isEqualTo(9);
        assertThat(u.totalBytes()).isEqualTo(17);
    }
}
