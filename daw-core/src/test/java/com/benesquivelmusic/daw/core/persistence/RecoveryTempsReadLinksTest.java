package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryTempsReadLinksTest {
    private static final String PROJECT = """
            <daw-project version="1">
              <metadata><name>Song</name></metadata>
              <transport loop-enabled="false" position="0.0" tempo="120.0"/>
              <tracks/>
            </daw-project>
            """;

    @TempDir
    Path directory;

    @Test
    void ordinaryOpenInspectsALinkToXmlWithoutApplyingWriteRestrictions() throws IOException {
        Path folder = linkedProject();
        ProjectManager manager = new ProjectManager(new CheckpointManager(
                new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        manager.setRecoveryGate((target, _, _) -> assertThat(RecoveryTemps.inspect(target).newerHead()).isNull());
        try {
            assertThat(manager.openProject(folder).name()).isEqualTo("Song");
            assertThat(manager.getCurrentDawProject()).isNotNull();
        } finally {
            manager.closeProject().join();
        }
    }

    @Test
    void inspectionFindsNewerXmlTargetTempsButReplacementStillRefusesTheLink() throws IOException {
        Path folder = linkedProject();
        Path xml = directory.resolve("Song.xml");
        Path temp = directory.resolve(".Song.xml.0123456789abcdef.tmp");
        Files.writeString(temp, PROJECT);
        Files.setLastModifiedTime(xml, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(temp, FileTime.from(Instant.parse("2026-01-01T00:01:00Z")));

        RecoveryTemps inspected = RecoveryTemps.inspect(folder);

        assertThat(inspected.newerHead()).isEqualTo(temp);
        assertThat(inspected.files()).containsExactly(temp);
        assertThatThrownBy(() -> AtomicFileWriter.followLinkToDawFile(folder.resolve("project.daw")))
                .isInstanceOf(IOException.class).hasMessageContaining("does not lead to a .daw file");
        assertThat(Files.readString(xml)).isEqualTo(PROJECT);
        assertThat(temp).exists();
    }

    private Path linkedProject() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(directory);
        Path folder = Files.createDirectory(directory.resolve("project"));
        Files.writeString(directory.resolve("Song.xml"), PROJECT);
        Files.createSymbolicLink(folder.resolve("project.daw"), Path.of("..", "Song.xml"));
        return folder;
    }
}
