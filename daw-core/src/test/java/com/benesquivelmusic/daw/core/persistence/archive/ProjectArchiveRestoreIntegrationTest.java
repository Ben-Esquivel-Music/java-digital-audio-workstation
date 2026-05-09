package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for Story 189 — Project Archive (ZIP With Assets) menu
 * wiring. Archives a small project, restores the {@code .dawz} into a
 * different directory, then loads the restored project via
 * {@link ProjectManager#openProject(Path)} (the same path the
 * "File → Restore from Archive…" menu item drives in the UI). Asserts
 * that the audio-clip source path on the restored project resolves to a
 * regular file inside the new directory — i.e. the archive workflow is
 * fully self-contained and does not depend on the original project root
 * still being present.
 */
class ProjectArchiveRestoreIntegrationTest {

    @TempDir
    Path tmp;

    private ProjectManager newManager() {
        AutoSaveConfig cfg = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        return new ProjectManager(new CheckpointManager(cfg));
    }

    @Test
    void shouldArchiveAndRestoreIntoDifferentDirectoryAndLoadProject() throws IOException {
        // ── Arrange — build a tiny project with one audio clip. ──────────
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        Path wav = mediaDir.resolve("clip.wav");
        Files.write(wav, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        DawProject project = new DawProject("ArchiveMe", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Audio");
        track.addClip(new AudioClip("c", 0, 1.0, wav.toString()));

        // ── Act — archive then restore into a *different* directory. ─────
        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tmp.resolve("session.dawz");
        archiver.saveAsArchive(project, archive);
        assertThat(archive).exists();

        Path restoreDir = tmp.resolve("restored-here");
        ArchivedProject restored = archiver.openArchive(
                archive, restoreDir, MissingAssetResolver.none());
        assertThat(restored.missingAssets()).isEmpty();

        // The UI controller writes the resolved project document back to
        // {destination}/project.daw so that ProjectManager.openProject
        // picks up absolute asset paths. Mirror that step here.
        String resolvedXml = new ProjectSerializer().serialize(restored.project());
        Files.writeString(restoreDir.resolve("project.daw"), resolvedXml);

        // ── Assert — load via ProjectManager.openProject, exactly the path
        // the File → Restore from Archive… menu item takes. ──────────────
        ProjectManager pm = newManager();
        ProjectMetadata meta = pm.openProject(restoreDir);
        assertThat(meta).isNotNull();

        DawProject loaded = pm.getCurrentDawProject();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTracks()).hasSize(1);

        AudioClip clip = loaded.getTracks().getFirst().getClips().getFirst();
        Path resolvedSource = Path.of(clip.getSourceFilePath());
        assertThat(resolvedSource)
                .as("restored audio clip must resolve to a real file under the new directory")
                .isRegularFile();
        assertThat(resolvedSource.toAbsolutePath())
                .startsWith(restoreDir.toAbsolutePath());

        // Tidy up the lock so @TempDir cleanup is well-behaved.
        pm.abandonProject();
    }

    @Test
    void shouldReportMissingAssetWhenReferencedFileWasDeletedBeforeArchiving() throws IOException {
        // ── Arrange — a project that references a file we then delete. ───
        Path wav = tmp.resolve("ghost.wav");
        Files.write(wav, new byte[]{9, 8, 7, 6});
        DawProject project = new DawProject("Ghost", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Audio");
        track.addClip(new AudioClip("ghost", 0, 1.0, wav.toString()));
        Files.delete(wav);
        assertThat(wav).doesNotExist();

        // ── Act — archive should succeed and simply omit the missing asset.
        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tmp.resolve("ghost.dawz");
        ProjectArchiveSummary summary = archiver.saveAsArchive(project, archive);

        // ── Assert — zero unique assets in the archive (the only ref was
        //   missing on disk), and the restored project surfaces the
        //   missing asset path so the UI can list it for the user. ────────
        assertThat(summary.uniqueAssetCount()).isZero();
        assertThat(summary.totalAssetBytes()).isZero();
        assertThat(archive).exists();

        Path restoreDir = tmp.resolve("ghost-restore");
        ArchivedProject restored = archiver.openArchive(
                archive, restoreDir, MissingAssetResolver.none());
        List<String> missing = restored.missingAssets();
        assertThat(missing)
                .as("missing-asset list lets the UI tell the user what was lost")
                .isNotEmpty();
    }
}
