package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 323 fault-injection probe (fix round 1b, item 1; re-based on a fault
 * seam in fix round 2): {@link ProjectArchiver#saveAsArchive} rewrites every
 * planned reference to its archive-relative {@code assets/…} path
 * <em>inside</em> the {@code try} whose {@code finally} restores them, so a
 * rewrite that throws part-way through leaves the in-memory project exactly
 * as it was.
 *
 * <p>No production reference can throw from its update any more (a recorded
 * take contributes one segment reference per element, and none of them trips
 * the head invariant), so every other archiver test passes with the rewrite
 * moved back outside the {@code try}. This test injects the failure the
 * {@code try} exists for through {@link ProjectArchiver#failRewriteAfter(int)}:
 * the rewrite throws right after the n-th reference was re-pointed. The
 * project holds three references in walk order — the import on the earlier
 * track, then the take's two segments. The seam is consumed by the rewrite
 * that throws, which the same test pins by archiving the same project with
 * the same archiver straight afterwards. Deterministic; one thread; no
 * timing.</p>
 */
class ProjectArchiverRewriteRollbackTest {

    /** References the fixture project holds, in the order the archiver walks them. */
    private static final int REFERENCES = 3;

    @TempDir
    Path tmp;

    @ParameterizedTest(name = "the rewrite throws after {0} of 3 references")
    @ValueSource(ints = {1, 2, 3})
    void aRewriteThatThrowsPartWayThroughLeavesEveryReferenceAsItWas(int rewrittenBeforeTheThrow)
            throws IOException {
        Fixture fixture = new Fixture(tmp);
        Path archiveDir = Files.createDirectories(tmp.resolve("out"));
        Path archive = archiveDir.resolve("mixed.dawz");
        ProjectArchiver archiver = new ProjectArchiver();
        archiver.failRewriteAfter(rewrittenBeforeTheThrow);

        // Non-vacuity: the rewrite really had begun when it threw — the
        // message names the archive-relative path the last reference had
        // already been re-pointed to.
        String lastRepointed = List.of("_loop.wav", "_segment-000.wav", "_segment-001.wav")
                .get(rewrittenBeforeTheThrow - 1);
        assertThatThrownBy(() -> archiver.saveAsArchive(fixture.project, archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected archive rewrite failure")
                .hasMessageContaining("after " + rewrittenBeforeTheThrow + " reference(s)")
                .hasMessageContaining("re-pointed to assets/")
                .hasMessageEndingWith(lastRepointed);

        assertThat(fixture.importClip.getSourceFilePath())
                .as("the import on the earlier track has its path back")
                .isEqualTo(fixture.imported.toString());
        assertThat(fixture.importClip.getSourceSegmentPaths()).isEmpty();
        assertThat(fixture.recorded.getSourceSegmentPaths())
                .as("the take references its own segments again, in order")
                .containsExactly(fixture.first.toString(), fixture.second.toString());
        assertThat(fixture.recorded.getSourceFilePath()).isEqualTo(fixture.first.toString());
        assertThat(archive).as("no archive was published").doesNotExist();
        try (Stream<Path> entries = Files.list(archiveDir)) {
            assertThat(entries).as("nor a partial one left beside it").isEmpty();
        }

        // The seam is consumed by the rewrite that threw: the same archiver
        // archives the same project at the next attempt.
        ProjectArchiveSummary summary = archiver.saveAsArchive(fixture.project, archive);

        assertThat(summary.uniqueAssetCount()).isEqualTo(REFERENCES);
        assertThat(archive).isRegularFile();
        Map<String, byte[]> assets = assetsOf(archive);
        assertThat(assets).as("the import and every segment of the take").hasSize(REFERENCES);
        assertThat(assetEndingWith(assets, "_loop.wav")).isEqualTo(Files.readAllBytes(fixture.imported));
        assertThat(assetEndingWith(assets, "_segment-000.wav")).isEqualTo(Files.readAllBytes(fixture.first));
        assertThat(assetEndingWith(assets, "_segment-001.wav")).isEqualTo(Files.readAllBytes(fixture.second));
        assertThat(fixture.importClip.getSourceFilePath())
                .as("and the project's references are its own again after the save that succeeded")
                .isEqualTo(fixture.imported.toString());
        assertThat(fixture.recorded.getSourceSegmentPaths())
                .containsExactly(fixture.first.toString(), fixture.second.toString());
    }

    @Test
    void theSameProjectArchivesAndIsRestoredWhenNoRewriteThrows() throws IOException {
        // Non-vacuity of the probe above: the seam changes nothing but the
        // one injected throw. Armed one reference past the last, it never
        // fires, and the rewrite it would have interrupted is the one that
        // puts assets/… into the archived document.
        Fixture fixture = new Fixture(tmp);
        Path archive = tmp.resolve("out").resolve("mixed.dawz");
        ProjectArchiver archiver = new ProjectArchiver();
        archiver.failRewriteAfter(REFERENCES + 1);

        ProjectArchiveSummary summary = archiver.saveAsArchive(fixture.project, archive);

        assertThat(summary.uniqueAssetCount()).isEqualTo(REFERENCES);
        assertThat(archive).isRegularFile();
        String document = projectDocumentOf(archive);
        assertThat(document).contains("_loop.wav", "_segment-000.wav", "_segment-001.wav");
        assertThat(document)
                .as("the archived document holds archive-relative references only")
                .contains("assets/")
                .doesNotContain(fixture.imported.toString())
                .doesNotContain(fixture.first.toString());
        assertThat(fixture.importClip.getSourceFilePath()).isEqualTo(fixture.imported.toString());
        assertThat(fixture.recorded.getSourceSegmentPaths())
                .containsExactly(fixture.first.toString(), fixture.second.toString());
        assertThat(fixture.recorded.getSourceFilePath()).isEqualTo(fixture.first.toString());
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
    }
}
