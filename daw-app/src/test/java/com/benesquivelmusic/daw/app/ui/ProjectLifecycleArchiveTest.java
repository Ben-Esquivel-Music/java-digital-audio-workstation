package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveAssetDecision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless tests for {@link ProjectLifecycleController}'s pure-logic
 * helpers — mainly the missing-asset walker that powers the pre-archive
 * confirmation dialog (Story 189).
 *
 * <p>The full archive / restore flow drives a JavaFX {@code FileChooser}
 * and is therefore covered by the byte-identical round-trip test in
 * {@code daw-core/.../ProjectArchiverTest} (engine-level) plus a higher
 * integration test in {@code ProjectLifecycleArchiveIntegrationTest}.</p>
 */
class ProjectLifecycleArchiveTest {

    @TempDir
    Path tmp;

    @Test
    void collectMissingAssetPathsReturnsEmptyForFullyResolvedProject() throws IOException {
        DawProject project = new DawProject("Resolved", AudioFormat.CD_QUALITY);
        Path wav = tmp.resolve("clip.wav");
        Files.write(wav, new byte[]{1, 2, 3});
        Track t = project.createAudioTrack("T1");
        t.addClip(new AudioClip("c", 0, 1.0, wav.toString()));

        List<String> missing = ProjectLifecycleController.collectMissingAssetPaths(project);
        assertThat(missing).isEmpty();
    }

    @Test
    void collectMissingAssetPathsListsDeletedAudioAndSoundFonts() throws IOException {
        DawProject project = new DawProject("Missing", AudioFormat.CD_QUALITY);
        Path wav = tmp.resolve("present.wav");
        Files.write(wav, new byte[]{1, 2, 3});
        Path goneWav = tmp.resolve("gone.wav");
        Path goneSf = tmp.resolve("gone.sf2");

        Track audio = project.createAudioTrack("A");
        audio.addClip(new AudioClip("present", 0, 1.0, wav.toString()));
        audio.addClip(new AudioClip("gone", 1.0, 1.0, goneWav.toString()));

        Track midi = project.createMidiTrack("M");
        midi.setSoundFontAssignment(new SoundFontAssignment(goneSf, 0, 0, "Missing piano"));

        List<String> missing = ProjectLifecycleController.collectMissingAssetPaths(project);
        assertThat(missing)
                .containsExactlyInAnyOrder(goneWav.toString(), goneSf.toString());
    }

    @Test
    void collectMissingAssetPathsIgnoresBlankReferences() {
        DawProject project = new DawProject("Empty", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("T");
        // A clip with no source file (e.g. a freshly-recorded clip not yet
        // committed to disk) must not be reported as a missing asset.
        t.addClip(new AudioClip("scratch", 0, 1.0, ""));

        assertThat(ProjectLifecycleController.collectMissingAssetPaths(project)).isEmpty();
    }

    @Test
    void collectMissingAssetPathsChecksEverySegmentOfARecordedTakeAndReportsEachOnce()
            throws IOException {
        // Story 323: a recorded take references an ordered segment list; the
        // archiver walks every element, so the pre-archive dialog must too.
        DawProject project = new DawProject("Rotated", AudioFormat.CD_QUALITY);
        Path present = tmp.resolve("segment-000.wav");
        Files.write(present, new byte[]{1, 2, 3});
        Path goneMiddle = tmp.resolve("segment-001.wav");
        Path goneLast = tmp.resolve("segment-002.wav");

        Track t = project.createAudioTrack("Vox");
        AudioClip take = new AudioClip("Take 1", 0, 8.0, present.toString());
        take.setSourceSegmentPaths(List.of(
                present.toString(), goneMiddle.toString(), goneLast.toString()));
        t.addClip(take);

        List<String> missing = ProjectLifecycleController.collectMissingAssetPaths(project);

        assertThat(missing).containsExactly(goneMiddle.toString(), goneLast.toString());
    }

    @Test
    void collectMissingAssetPathsReportsAMissingHeadSegmentOnce() {
        DawProject project = new DawProject("Lost", AudioFormat.CD_QUALITY);
        Path goneHead = tmp.resolve("segment-000.wav");
        Track t = project.createAudioTrack("Vox");
        AudioClip take = new AudioClip("Take 1", 0, 8.0, goneHead.toString());
        take.setSourceSegmentPaths(List.of(goneHead.toString()));
        t.addClip(take);

        assertThat(ProjectLifecycleController.collectMissingAssetPaths(project))
                .containsExactly(goneHead.toString());
    }

    @Test
    void collectMissingAssetPathsListsAClipReferenceThatIsNotAnAbsolutePathEvenWhereTheWorkingDirectoryHasTheFile()
            throws IOException {
        // Story 323: resolved against the JVM's working directory this
        // relative reference names a real file, but a clip reference that is
        // not an absolute path names no file, so it is listed all the same.
        Path found = Files.write(tmp.resolve("found.wav"), new byte[]{1, 2, 3});
        Path present = Files.write(tmp.resolve("segment-000.wav"), new byte[]{4, 5});
        String relative = relativeFromTheWorkingDirectory(found);
        DawProject project = new DawProject("Relative", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("T");
        t.addClip(new AudioClip("single", 0, 1.0, relative));
        AudioClip take = new AudioClip("Take 1", 1.0, 8.0, present.toString());
        take.setSourceSegmentPaths(List.of(present.toString(), relative));
        t.addClip(take);

        assertThat(ProjectLifecycleController.collectMissingAssetPaths(project))
                .containsExactly(relative, relative);
    }

    @Test
    void everyListedPathCanBecomeAnArchiveDecisionSoABlankClipReferenceIsNotListed() {
        // The dialog turns every listed path into an ArchiveAssetDecision,
        // which refuses a blank path; a whitespace-only clip reference (the
        // setter and the project reader keep one) must therefore not be listed.
        DawProject project = new DawProject("Blank", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("T");
        t.addClip(new AudioClip("whitespace", 0, 1.0, "  "));
        AudioClip take = new AudioClip("Take 1", 1.0, 8.0, "  ");
        take.setSourceSegmentPaths(List.of("  ", "gone/segment-001.wav"));
        t.addClip(take);

        List<String> missing = ProjectLifecycleController.collectMissingAssetPaths(project);

        assertThat(missing).containsExactly("gone/segment-001.wav");
        for (String path : missing) {
            assertThatCode(() -> ArchiveAssetDecision.skip(path)).doesNotThrowAnyException();
        }
    }

    /**
     * A relative clip reference that names {@code file} when it is resolved
     * against the JVM's working directory — what a pre-flight that ignored
     * story 323's rule would find there. Both sides are real paths, so a
     * symbolic link on the way cannot break the join; the test is skipped
     * when no relative path joins them (different drives).
     */
    private static String relativeFromTheWorkingDirectory(Path file) throws IOException {
        Path cwd = Path.of("").toAbsolutePath().toRealPath();
        Path target = file.toRealPath();
        assumeTrue(cwd.getRoot().equals(target.getRoot()),
                "fixture: the temp directory and the working directory share a root");
        String relative = cwd.relativize(target).toString().replace('\\', '/');
        assertThat(Path.of(relative).isAbsolute()).as("fixture: %s is relative", relative).isFalse();
        assertThat(Files.isRegularFile(Path.of(relative)))
                .as("fixture: %s names the file from the working directory", relative).isTrue();
        return relative;
    }
}
