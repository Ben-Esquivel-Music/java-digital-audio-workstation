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
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ProjectManagerTest {

    @TempDir
    Path tempDir;

    private ProjectManager createProjectManager() {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager checkpointManager = new CheckpointManager(config);
        return new ProjectManager(checkpointManager);
    }

    private ProjectManager createProjectManagerWithRecentStore() throws Exception {
        AutoSaveConfig config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        CheckpointManager checkpointManager = new CheckpointManager(config);
        Preferences prefs = Preferences.userRoot().node("/com/benesquivelmusic/daw/test/pm-recent");
        prefs.clear();
        RecentProjectsStore store = new RecentProjectsStore(prefs, 5);
        return new ProjectManager(checkpointManager, store);
    }

    @Test
    void shouldCreateProject() throws IOException {
        ProjectManager manager = createProjectManager();

        ProjectMetadata metadata = manager.createProject("My Song", tempDir);

        assertThat(metadata.name()).isEqualTo("My Song");
        assertThat(metadata.projectPath()).isNotNull();
        assertThat(metadata.projectPath()).isDirectory();
        assertThat(metadata.projectPath().resolve("audio")).isDirectory();
        assertThat(metadata.projectPath().resolve("project.daw")).exists();
    }

    @Test
    void shouldHaveCurrentProjectAfterCreate() throws IOException {
        ProjectManager manager = createProjectManager();

        manager.createProject("Test", tempDir);

        assertThat(manager.getCurrentProject()).isNotNull();
        assertThat(manager.getCurrentProject().name()).isEqualTo("Test");
    }

    @Test
    void shouldTrackRecentProjects() throws IOException {
        ProjectManager manager = createProjectManager();

        manager.createProject("Song A", tempDir);
        manager.closeProject();

        assertThat(manager.getRecentProjects()).hasSize(1);
    }

    @Test
    void shouldSaveProject() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Test", tempDir);

        manager.saveProject();

        assertThat(manager.getCheckpointManager().getCheckpointCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectSaveWithoutOpenProject() {
        ProjectManager manager = createProjectManager();

        assertThatThrownBy(manager::saveProject)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No project is currently open");
    }

    @Test
    void shouldCloseProject() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Test", tempDir);

        manager.closeProject();

        assertThat(manager.getCurrentProject()).isNull();
        assertThat(manager.getCheckpointManager().isRunning()).isFalse();
    }

    @Test
    void shouldCloseProjectIdempotently() throws IOException {
        ProjectManager manager = createProjectManager();

        manager.closeProject();
        assertThat(manager.getCurrentProject()).isNull();
    }

    @Test
    void shouldOpenExistingProject() throws IOException {
        ProjectManager manager = createProjectManager();
        ProjectMetadata created = manager.createProject("Original", tempDir);
        Path projectDir = created.projectPath();
        manager.closeProject();

        ProjectMetadata opened = manager.openProject(projectDir);

        assertThat(opened.name()).isEqualTo("Original");
        assertThat(manager.getCurrentProject()).isNotNull();
    }

    @Test
    void shouldRejectOpeningNonexistentProject() {
        ProjectManager manager = createProjectManager();

        assertThatThrownBy(() -> manager.openProject(tempDir.resolve("nonexistent")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldWriteProjectFile() throws IOException {
        ProjectManager manager = createProjectManager();
        ProjectMetadata metadata = manager.createProject("Written", tempDir);

        String content = Files.readString(metadata.projectPath().resolve("project.daw"));

        assertThat(content).contains("name=Written");
        assertThat(content).contains("created_at=");
        assertThat(content).contains("last_modified=");
    }

    @Test
    void shouldSanitizeDirectoryName() {
        assertThat(ProjectManager.sanitizeDirectoryName("My Song!@#$%"))
                .isEqualTo("My Song_____");
        assertThat(ProjectManager.sanitizeDirectoryName("Simple Name"))
                .isEqualTo("Simple Name");
    }

    @Test
    void shouldRejectBlankSanitizedDirectoryName() {
        // All spaces strip to blank after sanitization
        assertThatThrownBy(() -> ProjectManager.sanitizeDirectoryName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid directory name");
    }

    @Test
    void shouldRejectDotSanitizedDirectoryName() {
        assertThatThrownBy(() -> ProjectManager.sanitizeDirectoryName("."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid directory name");
    }

    @Test
    void shouldRejectDotDotSanitizedDirectoryName() {
        assertThatThrownBy(() -> ProjectManager.sanitizeDirectoryName(".."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid directory name");
    }

    @Test
    void shouldOpenProjectWithMalformedDates() throws IOException {
        ProjectManager manager = createProjectManager();
        Path projectDir = tempDir.resolve("malformed");
        Files.createDirectories(projectDir);

        // Write a project file with corrupted timestamp values
        String content = """
                # DAW Project File
                name=Corrupted
                created_at=not-a-date
                last_modified=also-not-a-date
                """;
        Files.writeString(projectDir.resolve("project.daw"), content);

        // Should not throw — falls back to defaults
        ProjectMetadata metadata = manager.openProject(projectDir);
        assertThat(metadata.name()).isEqualTo("Corrupted");
        assertThat(metadata.createdAt()).isNotNull();
        assertThat(metadata.lastModified()).isNotNull();
        manager.closeProject();
    }

    @Test
    void shouldStartCheckpointManagerOnCreate() throws IOException {
        ProjectManager manager = createProjectManager();

        manager.createProject("Test", tempDir);

        assertThat(manager.getCheckpointManager().isRunning()).isTrue();
        manager.closeProject();
    }

    @Test
    void shouldPersistRecentProjectOnCreate() throws Exception {
        ProjectManager manager = createProjectManagerWithRecentStore();

        manager.createProject("Song A", tempDir);

        assertThat(manager.getRecentProjectPaths()).hasSize(1);
        manager.closeProject();
    }

    @Test
    void shouldPersistRecentProjectOnOpen() throws Exception {
        ProjectManager manager = createProjectManagerWithRecentStore();
        ProjectMetadata created = manager.createProject("Song B", tempDir);
        Path projectDir = created.projectPath();
        manager.closeProject();

        manager.openProject(projectDir);

        // Created + opened = same project, so still 1 entry
        assertThat(manager.getRecentProjectPaths()).hasSize(1);
        manager.closeProject();
    }

    @Test
    void shouldReturnEmptyRecentPathsWithoutStore() {
        ProjectManager manager = createProjectManager();
        assertThat(manager.getRecentProjectPaths()).isEmpty();
    }

    @Test
    void shouldReturnRecentProjectsStore() throws Exception {
        ProjectManager manager = createProjectManagerWithRecentStore();
        assertThat(manager.getRecentProjectsStore()).isNotNull();
    }

    @Test
    void shouldReturnNullStoreWhenNotConfigured() {
        ProjectManager manager = createProjectManager();
        assertThat(manager.getRecentProjectsStore()).isNull();
    }

    // ── Full project save/load tests ────────────────────────────────────────

    @Test
    void shouldSaveDawProject() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Full Save", tempDir);

        DawProject dawProject = new DawProject("Full Save", AudioFormat.CD_QUALITY);
        dawProject.createAudioTrack("Vocals");
        dawProject.createMidiTrack("Synth");
        dawProject.getTransport().setTempo(140.0);
        dawProject.setMetadata(manager.getCurrentProject());

        manager.saveDawProject(dawProject);

        Path projectFile = manager.getCurrentProject().projectPath().resolve("project.daw");
        String content = Files.readString(projectFile);
        assertThat(content).contains("<daw-project");
        assertThat(content).contains("<name>Full Save</name>");
        assertThat(content).contains("name=\"Vocals\"");
        assertThat(content).contains("name=\"Synth\"");
        assertThat(content).contains("tempo=\"140.0\"");
    }

    @Test
    void shouldOpenSavedDawProject() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Round Trip", tempDir);

        DawProject original = new DawProject("Round Trip", AudioFormat.STUDIO_QUALITY);
        Track track = original.createAudioTrack("Drums");
        track.setVolume(0.9);
        track.setPan(-0.2);
        track.addClip(new AudioClip("Kick", 0.0, 8.0, "/audio/kick.wav"));
        original.getTransport().setTempo(95.0);
        original.getTransport().setTimeSignature(3, 4);
        original.getMixer().getMasterChannel().setVolume(0.85);
        original.setMetadata(manager.getCurrentProject());

        manager.saveDawProject(original);
        Path projectDir = manager.getCurrentProject().projectPath();
        manager.closeProject();

        // Re-open and verify
        manager.openProject(projectDir);
        DawProject restored = manager.getCurrentDawProject();

        assertThat(restored).isNotNull();
        assertThat(restored.getName()).isEqualTo("Round Trip");
        assertThat(restored.getTracks()).hasSize(1);
        assertThat(restored.getTracks().get(0).getName()).isEqualTo("Drums");
        assertThat(restored.getTracks().get(0).getVolume()).isCloseTo(0.9, within(0.001));
        assertThat(restored.getTracks().get(0).getClips()).hasSize(1);
        assertThat(restored.getTransport().getTempo()).isEqualTo(95.0);
        assertThat(restored.getTransport().getTimeSignatureNumerator()).isEqualTo(3);
        assertThat(restored.getMixer().getMasterChannel().getVolume()).isCloseTo(0.85, within(0.001));
        assertThat(restored.isDirty()).isFalse();
        manager.closeProject();
    }

    @Test
    void shouldReturnNullDawProjectForLegacyFile() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Legacy", tempDir);
        Path projectDir = manager.getCurrentProject().projectPath();
        manager.closeProject();

        // The default create writes a legacy text file
        manager.openProject(projectDir);
        assertThat(manager.getCurrentDawProject()).isNull();
        manager.closeProject();
    }

    @Test
    void shouldTrackUnsavedChanges() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Dirty Test", tempDir);

        DawProject dawProject = new DawProject("Dirty Test", AudioFormat.CD_QUALITY);
        dawProject.setMetadata(manager.getCurrentProject());
        manager.saveDawProject(dawProject);

        assertThat(manager.hasUnsavedChanges()).isFalse();

        dawProject.markDirty();
        assertThat(manager.hasUnsavedChanges()).isTrue();

        manager.saveProject();
        assertThat(manager.hasUnsavedChanges()).isFalse();
        manager.closeProject();
    }

    @Test
    void shouldClearDawProjectOnClose() throws IOException {
        ProjectManager manager = createProjectManager();
        manager.createProject("Close Test", tempDir);

        DawProject dawProject = new DawProject("Close Test", AudioFormat.CD_QUALITY);
        dawProject.setMetadata(manager.getCurrentProject());
        manager.saveDawProject(dawProject);

        manager.closeProject();

        assertThat(manager.getCurrentDawProject()).isNull();
        assertThat(manager.hasUnsavedChanges()).isFalse();
    }

    @Test
    void shouldRejectSaveDawProjectWithoutOpenProject() {
        ProjectManager manager = createProjectManager();
        DawProject dawProject = new DawProject("Test", AudioFormat.CD_QUALITY);

        assertThatThrownBy(() -> manager.saveDawProject(dawProject))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No project is currently open");
    }
}
