package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.persistence.AudioFileFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path semantics of an imported (single-file) clip reference through
 * {@link ProjectManager}, the deserializer and the clip rehydrator
 * (Persistence Integrity book §3.3): a legacy absolute path inside the
 * project directory loads, plays and is written project-relative on the next
 * save; a reference outside the project directory stays absolute. The
 * recorded-take form of the same rules is
 * {@code ProjectRelativePersistenceContractTest} and
 * {@code ProjectManagerTest.saveWritesARecordedTakeProjectRelativeAndOpenProjectResolvesItAbsoluteAgain}.
 */
class ClipReferencePathNormalizationTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 512);

    @TempDir
    Path tempDir;

    @Test
    void aLegacyAbsoluteReferenceInsideTheProjectDirectoryLoadsRehydratesAndIsSavedProjectRelative()
            throws Exception {
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Legacy Paths", tempDir).projectPath();
        Path inside = wav(projectDir.resolve("audio").resolve("imports").resolve("tone.wav"), 44_100, 22_050, 2);
        DawProject project = new DawProject("Legacy Paths", FORMAT);
        float[][] beforeSave = new AudioFileImporter(project).importFile(inside, 0.0).clip().getAudioData();
        saved(manager, project);
        manager.abandonProject();
        // An old build wrote the absolute path verbatim: reproduce that file.
        Path projectFile = projectDir.resolve("project.daw");
        String relative = "source-file=\"audio/imports/tone.wav\"";
        String absolute = "source-file=\"" + inside + "\"";
        String saved = Files.readString(projectFile);
        assertThat(saved).as("fixture: today's save writes it relative").contains(relative);
        Files.writeString(projectFile, saved.replace(relative, absolute));
        assertThat(Files.readString(projectFile)).as("fixture: the legacy form").contains(absolute)
                .doesNotContain(relative);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        AudioClip clip = onlyClip(loaded);
        assertThat(clip.getSourceFilePath()).as("kept as written").isEqualTo(inside.toString());
        assertThat(reopened.getLastMissingAssets()).isEqualTo(MissingAssetsReport.EMPTY);
        rehydrate(loaded, reopened);
        assertThat(Arrays.deepEquals(clip.getAudioData(), beforeSave)).as("it loads and plays").isTrue();

        saved(reopened, reopened.getCurrentDawProject());
        assertThat(Files.readString(projectFile))
                .contains(relative)
                .doesNotContain(inside.toString())
                .doesNotContain(projectDir.toString());
        reopened.abandonProject();
    }

    @Test
    void aReferenceOutsideTheProjectDirectoryStaysAbsoluteAcrossSaveOpenAndSave() throws Exception {
        Path outside = wav(tempDir.resolve("library").resolve("tone.wav"), 44_100, 22_050, 2);
        ProjectManager manager = newManager();
        Path projectDir = manager.createProject("Library Paths", tempDir).projectPath();
        assertThat(outside.startsWith(projectDir)).as("fixture: the file is outside the project").isFalse();
        DawProject project = new DawProject("Library Paths", FORMAT);
        float[][] beforeSave = new AudioFileImporter(project).importFile(outside, 0.0).clip().getAudioData();
        saved(manager, project);
        manager.abandonProject();
        Path projectFile = projectDir.resolve("project.daw");
        String absolute = "source-file=\"" + outside + "\"";
        assertThat(Files.readString(projectFile)).contains(absolute);

        ProjectManager reopened = newManager();
        reopened.openProject(projectDir);
        DawProject loaded = reopened.getCurrentDawProject();
        AudioClip clip = onlyClip(loaded);
        assertThat(clip.getSourceFilePath()).isEqualTo(outside.toString());
        rehydrate(loaded, reopened);
        assertThat(Arrays.deepEquals(clip.getAudioData(), beforeSave)).isTrue();

        saved(reopened, reopened.getCurrentDawProject());
        assertThat(Files.readString(projectFile)).contains(absolute);
        reopened.abandonProject();
    }

    private static void rehydrate(DawProject loaded, ProjectManager manager) throws InterruptedException {
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = new ClipRehydrator().start(loaded, manager.getLastMissingAssets(), 0.0,
                listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);
        assertThat(result.rows()).isEmpty();
        assertThat(loaded.isDirty()).as("loading is not an edit").isFalse();
    }

    private static ProjectManager newManager() {
        return new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    private static AudioClip onlyClip(DawProject project) {
        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getTracks().getFirst().getClips()).hasSize(1);
        return project.getTracks().getFirst().getClips().getFirst();
    }
}
