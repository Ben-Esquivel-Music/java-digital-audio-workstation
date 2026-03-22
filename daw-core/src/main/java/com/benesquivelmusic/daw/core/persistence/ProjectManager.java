package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the lifecycle of DAW projects on disk.
 *
 * <p>Provides create, open, save, and close operations. Each project is
 * stored in its own directory. The project manager coordinates with a
 * {@link CheckpointManager} to automatically save project state during
 * long-running recording sessions.</p>
 *
 * <h2>Project Directory Layout</h2>
 * <pre>
 *   MyProject/
 *     project.daw            — project metadata and state
 *     audio/                 — recorded audio files
 *     checkpoints/           — auto-save checkpoints
 * </pre>
 */
public final class ProjectManager {

    private static final String PROJECT_FILE_NAME = "project.daw";
    private static final String AUDIO_DIR_NAME = "audio";

    private final Map<String, ProjectMetadata> recentProjects = new LinkedHashMap<>();
    private final CheckpointManager checkpointManager;

    private ProjectMetadata currentProject;

    /**
     * Creates a project manager with the given checkpoint manager.
     *
     * @param checkpointManager the checkpoint manager to use for auto-saves
     */
    public ProjectManager(CheckpointManager checkpointManager) {
        this.checkpointManager = Objects.requireNonNull(checkpointManager,
                "checkpointManager must not be null");
    }

    /**
     * Creates a new project in the specified directory.
     *
     * @param name             the project name
     * @param parentDirectory  the parent directory where the project folder will be created
     * @return metadata for the new project
     * @throws IOException if the project directory cannot be created
     */
    public ProjectMetadata createProject(String name, Path parentDirectory) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(parentDirectory, "parentDirectory must not be null");

        Path projectDir = parentDirectory.resolve(sanitizeDirectoryName(name));
        Files.createDirectories(projectDir);
        Files.createDirectories(projectDir.resolve(AUDIO_DIR_NAME));

        var metadata = ProjectMetadata.createNew(name).withPath(projectDir);
        writeProjectFile(metadata);

        currentProject = metadata;
        recentProjects.put(projectDir.toString(), metadata);
        checkpointManager.start(projectDir);
        return metadata;
    }

    /**
     * Opens an existing project from the specified directory.
     *
     * @param projectDirectory the project directory
     * @return the project metadata
     * @throws IOException if the project cannot be read
     */
    public ProjectMetadata openProject(Path projectDirectory) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");

        Path projectFile = projectDirectory.resolve(PROJECT_FILE_NAME);
        if (!Files.exists(projectFile)) {
            throw new IOException("Project file not found: " + projectFile);
        }

        String content = Files.readString(projectFile);
        var metadata = parseProjectFile(content, projectDirectory);

        currentProject = metadata;
        recentProjects.put(projectDirectory.toString(), metadata);
        checkpointManager.start(projectDirectory);
        return metadata;
    }

    /**
     * Saves the current project state.
     *
     * @throws IOException    if the project cannot be saved
     * @throws IllegalStateException if no project is currently open
     */
    public void saveProject() throws IOException {
        if (currentProject == null) {
            throw new IllegalStateException("No project is currently open");
        }
        currentProject = currentProject.touch();
        writeProjectFile(currentProject);
        checkpointManager.performCheckpoint();
    }

    /**
     * Closes the current project.
     *
     * @throws IOException if the final save fails
     */
    public void closeProject() throws IOException {
        if (currentProject == null) {
            return;
        }
        saveProject();
        checkpointManager.stop();
        currentProject = null;
    }

    /**
     * Returns the metadata for the currently open project, or {@code null}.
     *
     * @return the current project metadata
     */
    public ProjectMetadata getCurrentProject() {
        return currentProject;
    }

    /**
     * Returns a map of recent project paths to their metadata.
     *
     * @return unmodifiable map of recent projects
     */
    public Map<String, ProjectMetadata> getRecentProjects() {
        return Collections.unmodifiableMap(recentProjects);
    }

    /**
     * Returns the checkpoint manager.
     *
     * @return the checkpoint manager
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    private void writeProjectFile(ProjectMetadata metadata) throws IOException {
        if (metadata.projectPath() == null) {
            return;
        }
        Path projectFile = metadata.projectPath().resolve(PROJECT_FILE_NAME);
        String content = String.format("""
                # DAW Project File
                name=%s
                created_at=%s
                last_modified=%s
                """,
                metadata.name(),
                metadata.createdAt().toString(),
                metadata.lastModified().toString());
        Files.writeString(projectFile, content);
    }

    private ProjectMetadata parseProjectFile(String content, Path projectDir) {
        String name = "Untitled";
        Instant createdAt = Instant.now();
        Instant lastModified = Instant.now();

        for (String line : content.split("\n")) {
            if (line.startsWith("name=")) {
                name = line.substring("name=".length()).strip();
            } else if (line.startsWith("created_at=")) {
                try {
                    createdAt = Instant.parse(line.substring("created_at=".length()).strip());
                } catch (DateTimeParseException ignored) {
                    // keep default
                }
            } else if (line.startsWith("last_modified=")) {
                try {
                    lastModified = Instant.parse(line.substring("last_modified=".length()).strip());
                } catch (DateTimeParseException ignored) {
                    // keep default
                }
            }
        }
        return new ProjectMetadata(name, createdAt, lastModified, projectDir);
    }

    static String sanitizeDirectoryName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9._\\- ]", "_").strip();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Project name results in an invalid directory name: " + name);
        }
        return sanitized;
    }
}
