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
import java.util.HashSet;
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
 * has no former roots. Along a chain of copies each former root answers for
 * itself (PR #989 review 5704604621): a file a copy left out and a later
 * folder acquired at the same path is held for the later folder only, and
 * the newest former root that holds a reference decides.
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
        FormerRoots roots = new FormerRoots(List.of(new FormerRoots.Root(songA, Set.of(
                Path.of("audio/takes/t1/vox/segment-000.wav"), Path.of("samples/kick.wav"), Path.of("audio/ext.wav")))));

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

    @Test
    void aFileACopyLeftOutStaysAbsoluteThoughALaterFolderHoldsAnotherAtTheSamePath() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        Path songC = tmp.resolve("Song C");
        // A to B took audio/kept.wav, not audio/gone.wav; B to C took both, its audio/gone.wav B's own.
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, paths("audio/kept.wav"))
                .thenCopiedFrom(songB, paths("audio/kept.wav", "audio/gone.wav"));

        assertThat(ProjectPaths.relativize(songC, roots, songA.resolve("audio/gone.wav").toString()))
                .as("C's audio/gone.wav is B's file, never A's: A's reference stays absolute")
                .isEqualTo(songA.resolve("audio/gone.wav").toString());
        assertThat(ProjectPaths.relativize(songC, roots, songA.resolve("audio/kept.wav").toString()))
                .isEqualTo("audio/kept.wav");
        assertThat(ProjectPaths.relativize(songC, roots, songB.resolve("audio/gone.wav").toString()))
                .isEqualTo("audio/gone.wav");
    }

    @Test
    void aFileEveryCopyTookIsWrittenRelativeForEachFolderOfTheChain() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        Path songC = tmp.resolve("Song C");
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, paths("audio/x.wav"))
                .thenCopiedFrom(songB, paths("audio/x.wav", "audio/b.wav"));

        assertThat(ProjectPaths.relativize(songC, roots, songA.resolve("audio/x.wav").toString()))
                .as("audio/x.wav went A to B to C at the same path").isEqualTo("audio/x.wav");
        assertThat(ProjectPaths.relativize(songC, roots, songB.resolve("audio/x.wav").toString()))
                .isEqualTo("audio/x.wav");
        assertThat(ProjectPaths.relativize(songC, roots, songB.resolve("audio/b.wav").toString()))
                .isEqualTo("audio/b.wav");
    }

    @Test
    void aFileALaterCopyLeftOutStaysAbsoluteForTheEarlierFolder() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        Path songC = tmp.resolve("Song C");
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, paths("audio/x.wav", "audio/y.wav"))
                .thenCopiedFrom(songB, paths("audio/y.wav"));

        assertThat(ProjectPaths.relativize(songC, roots, songA.resolve("audio/x.wav").toString()))
                .as("B to C left audio/x.wav out: C has no file at that path that came from A")
                .isEqualTo(songA.resolve("audio/x.wav").toString());
        assertThat(ProjectPaths.relativize(songC, roots, songA.resolve("audio/y.wav").toString()))
                .isEqualTo("audio/y.wav");
    }

    @Test
    void theNewestFolderHoldsEveryFileItsCopyTookThoughNoEarlierFolderHadIt() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        Path songC = tmp.resolve("Song C");
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, paths("audio/a.wav"))
                .thenCopiedFrom(songB, paths("audio/a.wav", "audio/recorded-in-b.wav"));

        assertThat(ProjectPaths.relativize(songC, roots, songB.resolve("audio/recorded-in-b.wav").toString()))
                .as("B's own take, which A never had, is C's file").isEqualTo("audio/recorded-in-b.wav");
        assertThat(ProjectPaths.relativize(songC, roots, songA.resolve("audio/recorded-in-b.wav").toString()))
                .isEqualTo(songA.resolve("audio/recorded-in-b.wav").toString());
    }

    @Test
    void aFolderNestedInAnEarlierFolderDecidesFirst() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        Path nested = songA.resolve("sub");
        Path songC = tmp.resolve("Song C");
        // A to B took sub/audio/t.wav; B to A/sub took it and B's audio/t.wav; A/sub to C took both again.
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, paths("sub/audio/t.wav", "sub/only-in-a.wav"))
                .thenCopiedFrom(songB, paths("sub/audio/t.wav", "audio/t.wav", "sub/only-in-a.wav"))
                .thenCopiedFrom(nested, paths("sub/audio/t.wav", "audio/t.wav", "sub/only-in-a.wav"));

        assertThat(ProjectPaths.relativize(songC, roots, nested.resolve("audio/t.wav").toString()))
                .as("A/sub, the newer folder, names the file; under A it would read sub/audio/t.wav")
                .isEqualTo("audio/t.wav");
        assertThat(ProjectPaths.relativize(songC, roots, nested.resolve("only-in-a.wav").toString()))
                .as("A/sub does not hold only-in-a.wav: skipped, and A, which does, decides")
                .isEqualTo("sub/only-in-a.wav");
    }

    @Test
    void aChainOfSaveAsCopiesKeepsEachFoldersFilesApart() throws IOException {
        ProjectManager manager = manager();
        Path songA = manager.createProject("Song A", tmp).projectPath();
        Files.write(songA.resolve("audio/kept.wav"), new byte[16]);
        DawProject project = projectIn(songA);
        saved(manager, project);
        Path songB = saveAsAndAdopt(manager, project, "Song B");
        // After A to B: A gains an audio/gone.wav the copy never took, and B gains a different one of its own.
        Files.write(songA.resolve("audio/gone.wav"), new byte[] {1, 1, 1, 1});
        Files.write(songB.resolve("audio/gone.wav"), new byte[] {2, 2, 2, 2});
        Path songC = saveAsAndAdopt(manager, project, "Song C");

        project.getTracks().getFirst().addClip(new AudioClip("A's gone", 8, 1,
                songA.resolve("audio/gone.wav").toString()));
        project.getTracks().getFirst().addClip(new AudioClip("A's kept", 9, 1,
                songA.resolve("audio/kept.wav").toString()));
        project.getTracks().getFirst().addClip(new AudioClip("B's gone", 10, 1,
                songB.resolve("audio/gone.wav").toString()));
        project.markDirty();
        saved(manager, project);

        assertThat(Files.readAllBytes(songC.resolve("audio/gone.wav"))).as("C's audio/gone.wav is B's")
                .containsExactly(2, 2, 2, 2);
        String head = Files.readString(songC.resolve("project.daw"));
        assertThat(head).as("A's audio/gone.wav keeps naming A's file")
                .contains("source-file=\"" + songA.resolve("audio/gone.wav") + "\"")
                .contains("source-file=\"audio/kept.wav\"");
        assertThat(head.split("source-file=\"audio/gone.wav\"", -1)).as("only B's reference names C's audio/gone.wav")
                .hasSize(2);
    }

    private static Set<Path> paths(String... relative) {
        Set<Path> paths = new HashSet<>();
        for (String path : relative) {
            paths.add(Path.of(path));
        }
        return paths;
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
        ProjectAssetReferences.rebase(project, from, published.folder(), published.formerRoots().heldFrom(from));
        project.setName(name);
        project.setMetadata(published.metadata());
        project.markCleanIfUnchanged(capture.revision());
        return published.folder();
    }
}
