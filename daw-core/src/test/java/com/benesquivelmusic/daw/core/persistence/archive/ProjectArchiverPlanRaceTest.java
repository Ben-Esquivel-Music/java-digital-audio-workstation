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
 * {@link ProjectArchiver#saveAsArchive} reads each reference once while it
 * builds the plan (Persistence Integrity Design Book §5.1): a clip re-pointed
 * between that read and the hash of the file it named — a reverse or a
 * normalize finishing on the FX thread while the archive is planned off it —
 * leaves the new reference unplanned: its file is not packaged, and it is
 * reported missing on reopen wherever that path does not resolve. Either way
 * it is never mapped to the old file's audio under the new name.
 * The plan seam ({@link ProjectArchiver.AssetPlanHook}) re-points the clip at
 * exactly that point. Deterministic; one thread; no timing.
 */
class ProjectArchiverPlanRaceTest {

    @TempDir
    Path tmp;

    @Test
    void aClipRePointedWhileItsFileIsHashedIsNotMappedToTheOldFilesAsset() throws IOException {
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
                    clip.setSourceFilePath(reversed.toString());   // the re-point races the hash
                });

        Path archive = tmp.resolve("out").resolve("race.dawz");
        ProjectArchiveSummary summary = archiver.saveAsArchive(project, archive);

        assertThat(hashed)
                .as("non-vacuity: the seam fired for the reference read before the re-point")
                .containsExactly(original.toString());
        assertThat(summary.uniqueAssetCount()).as("the old file was packed").isEqualTo(1);
        String document = projectDocumentOf(archive);
        assertThat(document)
                .as("the re-pointed clip keeps its new reference verbatim (not packaged; "
                        + "reported missing wherever that path does not resolve)")
                .contains(reversed.toString());
        assertThat(document)
                .as("and is never mapped to the old file's asset (wrong media on reopen)")
                .doesNotContain(ArchiveHeader.ASSETS_DIR + "/");
    }

    @Test
    void aSegmentListReplacedWhileAnEarlierSegmentIsHashedNeitherFailsNorMapsTheNewReference()
            throws IOException {
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

        Path archive = tmp.resolve("out").resolve("race-segments.dawz");
        ProjectArchiveSummary summary = archiver.saveAsArchive(project, archive);

        assertThat(hashed)
                .as("non-vacuity: both segments were planned from the one list read, "
                        + "segment 1 after the list was replaced")
                .containsExactly(segment0.toString(), segment1.toString());
        assertThat(summary.uniqueAssetCount()).as("both old segments were packed").isEqualTo(2);
        String document = projectDocumentOf(archive);
        assertThat(document)
                .as("the re-pointed clip keeps its new reference verbatim (not packaged)")
                .contains(reversed.toString());
        assertThat(document)
                .as("and the new reference is mapped to neither old segment's asset")
                .doesNotContain(ArchiveHeader.ASSETS_DIR + "/");
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
}
