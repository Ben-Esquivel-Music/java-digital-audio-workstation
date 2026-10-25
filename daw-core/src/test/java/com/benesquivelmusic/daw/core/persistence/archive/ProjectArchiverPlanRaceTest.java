package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An archive plans from its capture, never from the live project (story 331;
 * Persistence Integrity book §2.3, §4.2): a clip re-pointed while the plan
 * runs — a reverse or a normalize finishing on the FX thread while the
 * archive is planned off it — changes nothing in the archive. The captured
 * reference is packaged and the archived document points at its asset; the
 * new reference appears nowhere in the archive, and the live clip keeps the
 * edit. Before story 331 the plan read the live clips and the document was
 * serialized from them afterwards, so the edit reached the archived document
 * verbatim; this class pinned that and now pins the opposite. The plan seam
 * ({@link ProjectArchiver.AssetPlanHook}) re-points the clip while a captured
 * reference is hashed. Deterministic; one thread; no timing.
 */
class ProjectArchiverPlanRaceTest {

    @TempDir
    Path tmp;

    @Test
    void aClipRePointedWhileThePlanRunsIsArchivedAsItWasCaptured() throws IOException {
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        Path original = Files.write(mediaDir.resolve("vocal.wav"), new byte[]{1, 1, 1});
        Path reversed = Files.write(mediaDir.resolve("vocal-reversed.wav"), new byte[]{9, 9});
        AudioClip clip = new AudioClip("Vocal", 0, 4, original.toString());
        DawProject project = new DawProject("Race", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox").addClip(clip);

        List<String> hashed = new ArrayList<>();
        ProjectArchiver archiver = new ProjectArchiver(
                new ProjectSerializer(), new ProjectDeserializer(), "test",
                ProjectArchiver.AssetWriteHook.NONE,
                reference -> {
                    hashed.add(reference);
                    clip.setSourceFilePath(reversed.toString());   // the re-point races the plan
                });

        ArchiveCapture capture = archiver.capture(project, ArchiveOptions.defaults(), List.of());
        Path archive = tmp.resolve("out").resolve("race.dawz");
        ProjectArchiveSummary summary = archiver.write(capture, archive);

        assertThat(hashed)
                .as("non-vacuity: the seam fired for the captured reference")
                .containsExactly(original.toString());
        assertThat(clip.getSourceFilePath()).as("the edit stands in the live project").isEqualTo(reversed.toString());
        assertThat(summary.uniqueAssetCount()).as("the captured file was packed").isEqualTo(1);
        assertThat(assetNamesOf(archive)).singleElement()
                .satisfies(name -> assertThat(name).endsWith("_vocal.wav"));
        String document = projectDocumentOf(archive);
        assertThat(document)
                .as("the archived clip points at the captured file's asset")
                .contains("source-file=\"" + ArchiveHeader.ASSETS_DIR + "/")
                .contains("_vocal.wav\"");
        assertThat(document)
                .as("and the reference the edit set never reaches the archive")
                .doesNotContain(reversed.toString())
                .doesNotContain("vocal-reversed");
    }

    @Test
    void aSegmentListReplacedWhileThePlanRunsIsArchivedAsItWasCaptured() throws IOException {
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        Path segment0 = Files.write(mediaDir.resolve("take-000.wav"), new byte[]{1, 1, 1});
        Path segment1 = Files.write(mediaDir.resolve("take-001.wav"), new byte[]{2, 2});
        Path reversed = Files.write(mediaDir.resolve("take-reversed.wav"), new byte[]{9, 9, 9, 9});
        AudioClip clip = new AudioClip("Take", 0, 4, segment0.toString());
        clip.setSourceSegmentPaths(List.of(segment0.toString(), segment1.toString()));
        DawProject project = new DawProject("Race", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox").addClip(clip);

        List<String> hashed = new ArrayList<>();
        ProjectArchiver archiver = new ProjectArchiver(
                new ProjectSerializer(), new ProjectDeserializer(), "test",
                ProjectArchiver.AssetWriteHook.NONE,
                reference -> {
                    hashed.add(reference);
                    if (reference.equals(segment0.toString())) {
                        // What a reverse or normalize does on the FX thread.
                        clip.setSourceSegmentPaths(List.of());
                        clip.setSourceFilePath(reversed.toString());
                    }
                });

        ArchiveCapture capture = archiver.capture(project, ArchiveOptions.defaults(), List.of());
        Path archive = tmp.resolve("out").resolve("race-segments.dawz");
        ProjectArchiveSummary summary = archiver.write(capture, archive);

        assertThat(hashed)
                .as("non-vacuity: both captured segments were planned, segment 1 after the list was replaced")
                .containsExactly(segment0.toString(), segment1.toString());
        assertThat(clip.getSourceSegmentPaths()).as("the edit stands in the live project").isEmpty();
        assertThat(summary.uniqueAssetCount()).as("both captured segments were packed").isEqualTo(2);
        assertThat(assetNamesOf(archive)).hasSize(2)
                .anyMatch(name -> name.endsWith("_take-000.wav"))
                .anyMatch(name -> name.endsWith("_take-001.wav"));
        String document = projectDocumentOf(archive);
        assertThat(document)
                .as("the archived take keeps both captured segments, as assets")
                .contains("<source-segment path=\"" + ArchiveHeader.ASSETS_DIR + "/")
                .contains("_take-000.wav\"/>")
                .contains("_take-001.wav\"/>");
        assertThat(document)
                .as("and the reference the edit set never reaches the archive")
                .doesNotContain(reversed.toString())
                .doesNotContain("take-reversed");
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

    private static List<String> assetNamesOf(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().startsWith(ArchiveHeader.ASSETS_DIR + "/")) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }
}
