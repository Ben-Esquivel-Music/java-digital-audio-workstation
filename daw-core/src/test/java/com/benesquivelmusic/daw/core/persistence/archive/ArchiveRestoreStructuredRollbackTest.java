package com.benesquivelmusic.daw.core.persistence.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveRestoreStructuredRollbackTest {

    @TempDir
    Path tmp;

    @Test
    void restoreFailureLeavesNoTargetDirectoryOrStagingDebrisWhenTargetDidNotExist()
            throws IOException {
        byte[] invalidProject = """
                <project>
                  <audio-format sample-rate="-1" channels="2" bit-depth="16" buffer-size="512"/>
                </project>
                """.getBytes(StandardCharsets.UTF_8);
        Path archive = tmp.resolve("malformed.dawz");
        writeArchive(archive, invalidProject);

        Path targetDir = tmp.resolve("restored-project");
        Path stagingDir = tmp.resolve(".restored-project.restoring.tmp");

        assertThat(targetDir).doesNotExist();
        assertThat(stagingDir).doesNotExist();

        assertThatThrownBy(() -> new ProjectArchiver().openArchive(
                archive, targetDir, MissingAssetResolver.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");

        assertThat(targetDir).doesNotExist();
        assertThat(stagingDir).doesNotExist();
    }

    private static void writeArchive(Path archive, byte[] projectBytes) throws IOException {
        try (var out = Files.newOutputStream(archive);
             var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(ArchiveHeader.FILE_NAME));
            zip.write(headerFor(projectBytes).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ArchiveHeader.PROJECT_DOC_NAME));
            zip.write(projectBytes);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ArchiveHeader.ASSETS_DIR + "/partial.wav"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
    }

    private static String headerFor(byte[] projectBytes) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("projectName", "Broken Restore");
        properties.setProperty("archiveDate", Instant.EPOCH.toString());
        properties.setProperty("assetCount", "1");
        properties.setProperty("originalRoot", "");
        properties.setProperty("dawVersion", "test");
        properties.setProperty("projectDocSha256",
                ProjectArchiver.sha256Hex(projectBytes));
        StringWriter writer = new StringWriter();
        properties.store(writer, "test archive");
        return writer.toString();
    }
}
