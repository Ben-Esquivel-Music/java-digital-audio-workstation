package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProjectMetadataReader} — the side-effect-free name/timestamp
 * peek used by the story-296 Project Hub. Covers the XML format, the legacy
 * text format, and the missing-file empty case.
 */
class ProjectMetadataReaderTest {

    @TempDir
    Path projectDir;

    @Test
    void readsXmlMetadata() throws IOException {
        Instant created = Instant.parse("2026-01-02T03:04:05Z");
        Instant modified = Instant.parse("2026-02-03T04:05:06Z");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>My XML Song</name>
                        <created-at>%s</created-at>
                        <last-modified>%s</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(created, modified);
        Files.writeString(projectDir.resolve("project.daw"), xml);

        Optional<ProjectMetadata> result = ProjectMetadataReader.read(projectDir);

        assertThat(result).isPresent();
        ProjectMetadata metadata = result.get();
        assertThat(metadata.name()).isEqualTo("My XML Song");
        assertThat(metadata.createdAt()).isEqualTo(created);
        assertThat(metadata.lastModified()).isEqualTo(modified);
        assertThat(metadata.projectPath()).isEqualTo(projectDir);
    }

    @Test
    void readsLegacyTextMetadata() throws IOException {
        Instant created = Instant.parse("2025-06-01T10:00:00Z");
        Instant modified = Instant.parse("2025-06-15T18:30:00Z");
        String text = """
                # DAW Project File
                name=Legacy Tune
                created_at=%s
                last_modified=%s
                """.formatted(created, modified);
        Files.writeString(projectDir.resolve("project.daw"), text);

        Optional<ProjectMetadata> result = ProjectMetadataReader.read(projectDir);

        assertThat(result).isPresent();
        ProjectMetadata metadata = result.get();
        assertThat(metadata.name()).isEqualTo("Legacy Tune");
        assertThat(metadata.createdAt()).isEqualTo(created);
        assertThat(metadata.lastModified()).isEqualTo(modified);
        assertThat(metadata.projectPath()).isEqualTo(projectDir);
    }

    @Test
    void returnsEmptyWhenProjectFileMissing() {
        // projectDir exists (the @TempDir) but contains no project.daw.
        Optional<ProjectMetadata> result = ProjectMetadataReader.read(projectDir);

        assertThat(result).isEmpty();
    }

    @Test
    void fallsBackToDirectoryNameWhenNameBlank() throws IOException {
        // XML with no <name> element — name should fall back to the dir name.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                    </metadata>
                </daw-project>
                """;
        Files.writeString(projectDir.resolve("project.daw"), xml);

        Optional<ProjectMetadata> result = ProjectMetadataReader.read(projectDir);

        assertThat(result).isPresent();
        assertThat(result.get().name())
                .isEqualTo(projectDir.getFileName().toString());
    }

    @Test
    void lastModifiedFallsBackToFileMtimeNotNowWhenContentHasNoTimestamp() throws IOException {
        // XML with a name but NO <last-modified>/<created-at>: the timestamp must
        // come from the file on disk, not the instant the scan happens to run.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>No Timestamp Song</name>
                    </metadata>
                </daw-project>
                """;
        Path file = projectDir.resolve("project.daw");
        Files.writeString(file, xml);
        // Stamp a known mtime well in the past so a (regressed) Instant.now()
        // fallback would be obviously wrong.
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2024-07-01T12:00:00Z")));

        Optional<ProjectMetadata> result = ProjectMetadataReader.read(projectDir);

        assertThat(result).isPresent();
        // Read the mtime back so the assertion tolerates filesystem time resolution.
        Instant expected = Files.getLastModifiedTime(file).toInstant();
        assertThat(result.get().lastModified())
                .as("missing-timestamp project.daw falls back to the file's mtime")
                .isEqualTo(expected);
        assertThat(result.get().lastModified())
                .as("the past mtime, not a fresh Instant.now()")
                .isBefore(Instant.now().minusSeconds(60));
    }

    @Test
    void returnsBestEffortForMalformedXmlWithoutThrowing() throws IOException {
        // Truncated XML — must not throw; name falls back to the dir name.
        Files.writeString(projectDir.resolve("project.daw"),
                "<?xml version=\"1.0\"?><daw-project><metadata><name>oops");

        Optional<ProjectMetadata> result = ProjectMetadataReader.read(projectDir);

        assertThat(result).isPresent();
        assertThat(result.get().name())
                .isEqualTo(projectDir.getFileName().toString());
    }
}
