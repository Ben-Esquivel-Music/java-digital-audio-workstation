package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
