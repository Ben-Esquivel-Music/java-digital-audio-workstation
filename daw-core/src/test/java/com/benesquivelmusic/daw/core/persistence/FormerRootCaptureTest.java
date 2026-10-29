package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The former-root rule (story 333, ENG-S10, and the studio engineer's ruling
 * S1 on stage 3; Persistence Integrity book §3.3): after Save As, a reference
 * the live rebase could not reach — a clip held only by the undo history and
 * brought back — that still names a file under the original folder is
 * written project-relative by every capture of the copy (a save, the closing
 * checkpoint, the application's checkpoints through
 * {@link ProjectManager#captureOpenProject}, a further Save As) when, and only
 * when, the copy holds that file. A reference whose file the copy does not
 * hold — one that appeared in the original after the copy — stays absolute,
 * so it still names the original's file; a project opened the ordinary way
 * has no former roots.
 */
class FormerRootCaptureTest {

    @TempDir
    Path tmp;

    @Test
    void aReferenceUnderAFormerRootIsWrittenProjectRelativeOnlyWhenTheCopyHoldsItsFile() throws IOException {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        DawProject project = new DawProject("Song B", AudioFormat.CD_QUALITY);
        project.setMetadata(project.getMetadata().withPath(songB));
        Track vox = project.createAudioTrack("Vox");
        vox.addClip(new AudioClip("redone", 0, 1, songA.resolve("audio/takes/t1/vox/segment-000.wav").toString()));
        vox.addClip(new AudioClip("own", 1, 1, songB.resolve("audio/new.wav").toString()));
        vox.addClip(new AudioClip("held outside audio", 2, 1, songA.resolve("samples/kick.wav").toString()));
        vox.addClip(new AudioClip("external", 3, 1, tmp.resolve("Library/ext.wav").toString()));
        vox.addClip(new AudioClip("not held", 4, 1, songA.resolve("audio/late.wav").toString()));
        // Ruling 3 D-a: one rule for every path, audio/ or not — a non-audio file the copy does not hold.
        vox.addClip(new AudioClip("not held outside audio", 5, 1, songA.resolve("samples/old.wav").toString()));
        FormerRoots roots = new FormerRoots(List.of(songA), Set.of(Path.of("audio/takes/t1/vox/segment-000.wav"),
                Path.of("samples/kick.wav"), Path.of("audio/ext.wav")));

        ProjectDocument withRoots = new ProjectSerializer().capture(project, roots);
        String xml = withRoots.toXml();
        String withoutRoots = new ProjectSerializer().capture(project).toXml();

        assertThat(xml).contains("source-file=\"audio/takes/t1/vox/segment-000.wav\"")
                .contains("source-file=\"audio/new.wav\"")
                .contains("source-file=\"samples/kick.wav\"")
                .contains("source-file=\"" + tmp.resolve("Library/ext.wav") + "\"");
        assertThat(xml).as("a file the copy does not hold keeps the original's absolute path")
                .contains("source-file=\"" + songA.resolve("audio/late.wav") + "\"")
                .contains("source-file=\"" + songA.resolve("samples/old.wav") + "\"")
                .doesNotContain("source-file=\"samples/old.wav\"");
        assertThat(withoutRoots).as("without former roots the rule is the project-relative one alone")
                .contains("source-file=\"" + songA.resolve("audio/takes/t1/vox/segment-000.wav") + "\"");
        assertThat(withRoots.toXml(new ProjectSerializer.AssetReferenceRemap(
                Map.of(tmp.resolve("Library/ext.wav").toString(), songA.resolve("audio/ext.wav").toString()),
                Map.of())))
                .as("a remap's substitute goes through the same rule")
                .contains("source-file=\"audio/ext.wav\"");
    }

    @Test
    void theAdoptedCopysSavesAndClosingCheckpointWriteTheOriginalsHeldFilesProjectRelative() throws IOException {
        ProjectManager manager = manager();
        Path songA = manager.createProject("Song A", tmp).projectPath();
        Files.write(songA.resolve("audio/held.wav"), new byte[16]);
        DawProject project = projectIn(songA);
        saved(manager, project);
        Path songB = saveAsAndAdopt(manager, project, "Song B");

        AudioClip redone = new AudioClip("redone", 8, 1, songA.resolve("audio/held.wav").toString());
        project.getTracks().getFirst().addClip(redone);
        project.markDirty();
        saved(manager, project);
        Path closing = await(manager.writeClosingCheckpoint(project));

        assertThat(Files.readString(songB.resolve("project.daw")))
                .as("the head of the copy names its own audio/held.wav")
                .contains("source-file=\"audio/held.wav\"")
                .doesNotContain(songA.resolve("audio/held.wav").toString());
        assertThat(closing).startsWith(songB.resolve("checkpoints"));
        assertThat(Files.readString(closing)).contains("source-file=\"audio/held.wav\"");
        assertThat(manager.captureOpenProject(project).toXml())
                .as("the application's checkpoints capture through the manager, with the former roots")
                .contains("source-file=\"audio/held.wav\"");
        assertThat(new ProjectSerializer().capture(project).toXml())
                .as("a capture without them would name the original's file")
                .contains("source-file=\"" + songA.resolve("audio/held.wav") + "\"");
    }

    @Test
    void aFileThatAppearedInTheOriginalAfterTheCopyIsSavedAbsolute() throws IOException {
        ProjectManager manager = manager();
        Path songA = manager.createProject("Song A", tmp).projectPath();
        DawProject project = projectIn(songA);
        saved(manager, project);
        Path songB = saveAsAndAdopt(manager, project, "Song B");
        Path late = Files.write(songA.resolve("audio/late.wav"), new byte[16]);

        project.getTracks().getFirst().addClip(new AudioClip("late", 8, 1, late.toString()));
        project.markDirty();
        saved(manager, project);

        assertThat(Files.readString(songB.resolve("project.daw")))
                .as("the copy does not hold audio/late.wav: the reference keeps naming the original's file")
                .contains("source-file=\"" + late + "\"")
                .doesNotContain("source-file=\"audio/late.wav\"");
        assertThat(manager.captureOpenProject(project).toXml()).contains("source-file=\"" + late + "\"");
    }

    @Test
    void aSecondSaveAsKeepsTheFirstOriginalAsAFormerRoot() throws IOException {
        ProjectManager manager = manager();
        Path songA = manager.createProject("Song A", tmp).projectPath();
        Files.write(songA.resolve("audio/a.wav"), new byte[16]);
        DawProject project = projectIn(songA);
        saved(manager, project);
        Path songB = saveAsAndAdopt(manager, project, "Song B");
        Files.write(songB.resolve("audio/b.wav"), new byte[16]);
        Path songC = saveAsAndAdopt(manager, project, "Song C");

        project.getTracks().getFirst().addClip(new AudioClip("from A", 8, 1, songA.resolve("audio/a.wav").toString()));
        project.getTracks().getFirst().addClip(new AudioClip("from B", 9, 1, songB.resolve("audio/b.wav").toString()));
        project.markDirty();
        saved(manager, project);

        assertThat(Files.readString(songC.resolve("project.daw")))
                .contains("source-file=\"audio/a.wav\"")
                .contains("source-file=\"audio/b.wav\"");
    }

    @Test
    void aProjectOpenedTheOrdinaryWayHasNoFormerRoots() throws IOException {
        ProjectManager first = manager();
        Path songA = first.createProject("Song A", tmp).projectPath();
        Files.write(songA.resolve("audio/x.wav"), new byte[16]);
        DawProject project = projectIn(songA);
        saved(first, project);
        Path songB = saveAsAndAdopt(first, project, "Song B");
        await(first.closeProject());

        ProjectManager second = manager();
        second.openProject(songB);
        DawProject reopened = second.getCurrentDawProject();
        reopened.getTracks().getFirst().addClip(new AudioClip("another project's", 8, 1,
                songA.resolve("audio/x.wav").toString()));
        reopened.markDirty();
        saved(second, reopened);

        assertThat(Files.readString(songB.resolve("project.daw")))
                .as("a reference into another project's audio/ stays absolute")
                .contains("source-file=\"" + songA.resolve("audio/x.wav") + "\"");
        await(second.closeProject());
    }

    private static ProjectManager manager() {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    private static DawProject projectIn(Path folder) {
        DawProject project = new DawProject(folder.getFileName().toString(), AudioFormat.CD_QUALITY);
        project.setMetadata(project.getMetadata().withPath(folder));
        project.createAudioTrack("Vox");
        return project;
    }

    /** Save As into a sibling named {@code name}, then the copy opened as the application does it. */
    private Path saveAsAndAdopt(ProjectManager manager, DawProject project, String name) throws IOException {
        Path from = project.getMetadata().projectPath();
        SaveAsCapture capture = manager.captureForSaveAs(project, name);
        SaveAsPlan plan = manager.planSaveAs(capture, tmp.resolve(name), () -> false);
        assertThat(plan).isInstanceOf(SaveAsPlan.Ready.class);
        SaveAsOutcome outcome = manager.publishCopy((SaveAsPlan.Ready) plan, SaveAsProgress.NONE, () -> false);
        assertThat(outcome).isInstanceOf(SaveAsOutcome.Published.class);
        SaveAsOutcome.Published published = (SaveAsOutcome.Published) outcome;
        ProjectManager.PreparedOpen lease = manager.prepareLockedAccess(published.folder());
        manager.closeProject();
        manager.adoptPublishedCopy(published, lease, project);
        ProjectAssetReferences.rebase(project, from, published.folder(), published.formerRoots().heldFiles());
        project.setName(name);
        project.setMetadata(published.metadata());
        project.markCleanIfUnchanged(capture.revision());
        return published.folder();
    }
}
