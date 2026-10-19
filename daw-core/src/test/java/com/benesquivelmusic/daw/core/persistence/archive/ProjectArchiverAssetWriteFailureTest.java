package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProjectArchiver#saveAsArchive} never rewrites a live reference: the
 * archived document's {@code assets/…} references are substituted as the
 * serializer writes it, so the project holds its own references for the
 * whole write — while the assets are being packed, when the pack fails
 * part-way through, and afterwards — and a failed pack publishes nothing.
 * The asset-write seam ({@link ProjectArchiver.AssetWriteHook}) observes the
 * project mid-write and injects the failure. Deterministic; one thread; no
 * timing.
 */
class ProjectArchiverAssetWriteFailureTest {

    /** Assets the fixture project packs: the import, then the take's two segments. */
    private static final int ASSETS = 3;

    @TempDir
    Path tmp;

    @ParameterizedTest(name = "the pack fails at asset {0} of 3")
    @ValueSource(ints = {1, 2, 3})
    void aPackThatFailsPartWayThroughLeavesNoArchiveAndEveryReferenceAsItWas(int failingAsset)
            throws IOException {
        Fixture fixture = new Fixture(tmp);
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = archiveDir.resolve("mixed.dawz");
        List<String> entriesReached = new ArrayList<>();
        ProjectArchiver archiver = archiver(entryName -> {
            entriesReached.add(entryName);
            fixture.assertReferencesAreTheProjectsOwn();
            if (entriesReached.size() == failingAsset) {
                throw new IOException("injected asset write failure at " + entryName);
            }
        });

        assertThatThrownBy(() -> archiver.saveAsArchive(fixture.project, archive))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("injected asset write failure at assets/");

        // Non-vacuity: the pack really had begun when it failed.
        assertThat(entriesReached).hasSize(failingAsset);
        fixture.assertReferencesAreTheProjectsOwn();
        assertThat(archive).as("no archive was published").doesNotExist();
        try (Stream<Path> entries = Files.list(archiveDir)) {
            assertThat(entries).as("nor a partial one left beside it").isEmpty();
        }

        // The same project archives at the next attempt.
        ProjectArchiveSummary summary = new ProjectArchiver().saveAsArchive(fixture.project, archive);

        assertThat(summary.uniqueAssetCount()).isEqualTo(ASSETS);
        Map<String, byte[]> assets = assetsOf(archive);
        assertThat(assets).as("the import and every segment of the take").hasSize(ASSETS);
        assertThat(assetEndingWith(assets, "_loop.wav")).isEqualTo(Files.readAllBytes(fixture.imported));
        assertThat(assetEndingWith(assets, "_segment-000.wav")).isEqualTo(Files.readAllBytes(fixture.first));
        assertThat(assetEndingWith(assets, "_segment-001.wav")).isEqualTo(Files.readAllBytes(fixture.second));
        fixture.assertReferencesAreTheProjectsOwn();
    }

    @Test
    void theArchivedDocumentHoldsArchiveRelativeReferencesWhileTheProjectKeepsItsOwn() throws IOException {
        Fixture fixture = new Fixture(tmp);
        Path archive = tmp.resolve("out").resolve("mixed.dawz");
        List<String> entriesReached = new ArrayList<>();
        ProjectArchiver archiver = archiver(entryName -> {
            entriesReached.add(entryName);
            fixture.assertReferencesAreTheProjectsOwn();
        });

        ProjectArchiveSummary summary = archiver.saveAsArchive(fixture.project, archive);

        assertThat(summary.uniqueAssetCount()).isEqualTo(ASSETS);
        assertThat(entriesReached).as("the project was checked at every asset write").hasSize(ASSETS);
        String document = projectDocumentOf(archive);
        assertThat(document).contains("_loop.wav", "_segment-000.wav", "_segment-001.wav");
        assertThat(document)
                .as("the archived document holds archive-relative references only")
                .contains("assets/")
                .doesNotContain(fixture.imported.toString())
                .doesNotContain(fixture.first.toString())
                .doesNotContain(fixture.second.toString());
        fixture.assertReferencesAreTheProjectsOwn();
    }

    private static ProjectArchiver archiver(ProjectArchiver.AssetWriteHook hook) {
        return new ProjectArchiver(new ProjectSerializer(), new ProjectDeserializer(), "test", hook);
    }

    private static String projectDocumentOf(Path archive) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(ArchiveHeader.PROJECT_DOC_NAME)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no " + ArchiveHeader.PROJECT_DOC_NAME + " in " + archive);
    }

    /** Every entry under {@code assets/} of the archive, by entry name. */
    private static Map<String, byte[]> assetsOf(Path archive) throws IOException {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && entry.getName().startsWith(ArchiveHeader.ASSETS_DIR + "/")) {
                    assets.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return assets;
    }

    private static byte[] assetEndingWith(Map<String, byte[]> assets, String suffix) {
        List<String> names = assets.keySet().stream().filter(name -> name.endsWith(suffix)).toList();
        assertThat(names).as("one archived asset ends with %s", suffix).hasSize(1);
        return assets.get(names.getFirst());
    }

    /** An import on an earlier track and a two-segment recorded take on a later one. */
    private static final class Fixture {
        final Path imported;
        final Path first;
        final Path second;
        final AudioClip importClip;
        final AudioClip recorded;
        final DawProject project;

        Fixture(Path root) throws IOException {
            Path mediaDir = Files.createDirectories(root.resolve("media"));
            imported = Files.write(mediaDir.resolve("loop.wav"), new byte[]{7, 7, 7});
            Path trackDir = Files.createDirectories(
                    root.resolve("Song/audio/takes/2026-09-29T10-00-00_take-0001/track-a"));
            first = Files.write(trackDir.resolve("segment-000.wav"), new byte[]{3, 3});
            second = Files.write(trackDir.resolve("segment-001.wav"), new byte[]{4, 4, 4});

            importClip = new AudioClip("Loop", 0, 4, imported.toString());
            recorded = new AudioClip("Take 1", 0, 8, first.toString());
            recorded.setSourceSegmentPaths(List.of(first.toString(), second.toString()));

            project = new DawProject("Mixed", AudioFormat.CD_QUALITY);
            project.createAudioTrack("Loops").addClip(importClip);   // earlier track
            project.createAudioTrack("Vox").addClip(recorded);       // later track
        }

        void assertReferencesAreTheProjectsOwn() {
            assertThat(importClip.getSourceFilePath())
                    .as("the import keeps its own path")
                    .isEqualTo(imported.toString());
            assertThat(importClip.getSourceSegmentPaths()).isEmpty();
            assertThat(recorded.getSourceSegmentPaths())
                    .as("the take keeps its own segments, in order")
                    .containsExactly(first.toString(), second.toString());
            assertThat(recorded.getSourceFilePath()).isEqualTo(first.toString());
        }
    }
}
