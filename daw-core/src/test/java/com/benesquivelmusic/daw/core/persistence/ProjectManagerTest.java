package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectManagerTest {

    @TempDir
    Path tempDir;

    private ProjectManager createProjectManager() {
        var config = new AutoSaveConfig(Duration.ofHours(1), 10, true);
        var checkpointManager = new CheckpointManager(config);
        return new ProjectManager(checkpointManager);
    }

    @Test
    void shouldCreateProject() throws IOException {
        var manager = createProjectManager();

        var metadata = manager.createProject("My Song", tempDir);

        assertThat(metadata.name()).isEqualTo("My Song");
        assertThat(metadata.projectPath()).isNotNull();
        assertThat(metadata.projectPath()).isDirectory();
        assertThat(metadata.projectPath().resolve("audio")).isDirectory();
        assertThat(metadata.projectPath().resolve("project.daw")).exists();
    }

    @Test
    void shouldHaveCurrentProjectAfterCreate() throws IOException {
        var manager = createProjectManager();

        manager.createProject("Test", tempDir);

        assertThat(manager.getCurrentProject()).isNotNull();
        assertThat(manager.getCurrentProject().name()).isEqualTo("Test");
    }

    @Test
    void shouldTrackRecentProjects() throws IOException {
        var manager = createProjectManager();

        manager.createProject("Song A", tempDir);
        manager.closeProject();

        assertThat(manager.getRecentProjects()).hasSize(1);
    }

    @Test
    void shouldSaveProject() throws IOException {
        var manager = createProjectManager();
        manager.createProject("Test", tempDir);

        manager.saveProject();

        assertThat(manager.getCheckpointManager().getCheckpointCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectSaveWithoutOpenProject() {
        var manager = createProjectManager();

        assertThatThrownBy(manager::saveProject)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No project is currently open");
    }

    @Test
    void shouldCloseProject() throws IOException {
        var manager = createProjectManager();
        manager.createProject("Test", tempDir);

        manager.closeProject();

        assertThat(manager.getCurrentProject()).isNull();
        assertThat(manager.getCheckpointManager().isRunning()).isFalse();
    }

    @Test
    void shouldCloseProjectIdempotently() throws IOException {
        var manager = createProjectManager();

        manager.closeProject();
        assertThat(manager.getCurrentProject()).isNull();
    }

    @Test
    void shouldOpenExistingProject() throws IOException {
        var manager = createProjectManager();
        var created = manager.createProject("Original", tempDir);
        Path projectDir = created.projectPath();
        manager.closeProject();

        var opened = manager.openProject(projectDir);

        assertThat(opened.name()).isEqualTo("Original");
        assertThat(manager.getCurrentProject()).isNotNull();
    }

    @Test
    void shouldRejectOpeningNonexistentProject() {
        var manager = createProjectManager();

        assertThatThrownBy(() -> manager.openProject(tempDir.resolve("nonexistent")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldWriteProjectFile() throws IOException {
        var manager = createProjectManager();
        var metadata = manager.createProject("Written", tempDir);

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
    void shouldStartCheckpointManagerOnCreate() throws IOException {
        var manager = createProjectManager();

        manager.createProject("Test", tempDir);

        assertThat(manager.getCheckpointManager().isRunning()).isTrue();
        manager.closeProject();
    }
}
