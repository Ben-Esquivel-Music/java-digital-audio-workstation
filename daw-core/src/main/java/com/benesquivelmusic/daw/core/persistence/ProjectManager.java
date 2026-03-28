package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.project.DawProject;

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
 * <p>Full project state — tracks, clips, mixer settings, transport, and
 * metadata — is serialized to XML via {@link ProjectSerializer} and
 * restored via {@link ProjectDeserializer}.</p>
 *
 * <h2>Project Directory Layout</h2>
 * <pre>
 *   MyProject/
 *     project.daw            — full project state (XML)
 *     audio/                 — recorded audio files
 *     checkpoints/           — auto-save checkpoints
 * </pre>
 */
public final class ProjectManager {

    private static final String PROJECT_FILE_NAME = "project.daw";
    private static final String AUDIO_DIR_NAME = "audio";

    private final Map<String, ProjectMetadata> recentProjects = new LinkedHashMap<>();
    private final CheckpointManager checkpointManager;
    private final RecentProjectsStore recentProjectsStore;
    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    private ProjectMetadata currentProject;
    private DawProject currentDawProject;

    /**
     * Creates a project manager with the given checkpoint manager.
     *
     * @param checkpointManager the checkpoint manager to use for auto-saves
     */
    public ProjectManager(CheckpointManager checkpointManager) {
        this(checkpointManager, null);
    }

    /**
     * Creates a project manager with the given checkpoint manager and
     * recent-projects store for cross-session persistence.
     *
     * @param checkpointManager   the checkpoint manager to use for auto-saves
     * @param recentProjectsStore the store for persisting recent project paths (may be {@code null})
     */
    public ProjectManager(CheckpointManager checkpointManager, RecentProjectsStore recentProjectsStore) {
        this.checkpointManager = Objects.requireNonNull(checkpointManager,
                "checkpointManager must not be null");
        this.recentProjectsStore = recentProjectsStore;
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

        ProjectMetadata metadata = ProjectMetadata.createNew(name).withPath(projectDir);
        writeProjectFile(metadata);

        currentProject = metadata;
        currentDawProject = null;
        recentProjects.put(projectDir.toString(), metadata);
        recordRecentProject(projectDir);
        checkpointManager.start(projectDir);
        return metadata;
    }

    /**
     * Opens an existing project from the specified directory.
     *
     * <p>If the project file contains full XML project state, it is deserialized
     * into a {@link DawProject} accessible via {@link #getCurrentDawProject()}.
     * Legacy project files that contain only metadata are also supported.</p>
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

        if (content.strip().startsWith("<?xml") || content.strip().startsWith("<daw-project")) {
            DawProject dawProject = deserializer.deserialize(content);
            ProjectMetadata metadata = dawProject.getMetadata().withPath(projectDirectory);
            dawProject.setMetadata(metadata);
            dawProject.markClean();
            currentDawProject = dawProject;
            currentProject = metadata;
        } else {
            ProjectMetadata metadata = parseProjectFile(content, projectDirectory);
            currentProject = metadata;
            currentDawProject = null;
        }

        recentProjects.put(projectDirectory.toString(), currentProject);
        recordRecentProject(projectDirectory);
        checkpointManager.start(projectDirectory);
        return currentProject;
    }

    /**
     * Saves the current project state. If a {@link DawProject} has been set
     * via {@link #saveDawProject(DawProject)}, the full project state is
     * serialized to XML. Otherwise, only metadata is written.
     *
     * @throws IOException    if the project cannot be saved
     * @throws IllegalStateException if no project is currently open
     */
    public void saveProject() throws IOException {
        if (currentProject == null) {
            throw new IllegalStateException("No project is currently open");
        }
        currentProject = currentProject.touch();
        if (currentDawProject != null) {
            currentDawProject.setMetadata(currentProject);
            writeDawProjectFile(currentDawProject, currentProject.projectPath());
            currentDawProject.markClean();
        } else {
            writeProjectFile(currentProject);
        }
        checkpointManager.performCheckpoint();
    }

    /**
     * Saves the full state of the given {@link DawProject} to disk. The project
     * is associated with this manager and its metadata is updated.
     *
     * @param dawProject the project to save
     * @throws IOException if the project cannot be saved
     * @throws IllegalStateException if no project is currently open
     */
    public void saveDawProject(DawProject dawProject) throws IOException {
        Objects.requireNonNull(dawProject, "dawProject must not be null");
        if (currentProject == null) {
            throw new IllegalStateException("No project is currently open");
        }
        currentDawProject = dawProject;
        currentProject = currentProject.touch();
        dawProject.setMetadata(currentProject);
        writeDawProjectFile(dawProject, currentProject.projectPath());
        dawProject.markClean();
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
        currentDawProject = null;
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
     * Returns the currently loaded {@link DawProject}, or {@code null} if
     * only metadata is available (e.g., a legacy project file was opened).
     *
     * @return the current DAW project, or {@code null}
     */
    public DawProject getCurrentDawProject() {
        return currentDawProject;
    }

    /**
     * Returns whether the current project has unsaved changes.
     *
     * @return {@code true} if there are unsaved changes
     */
    public boolean hasUnsavedChanges() {
        if (currentDawProject != null) {
            return currentDawProject.isDirty();
        }
        return false;
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

    /**
     * Returns the recent-projects store, or {@code null} if none was configured.
     *
     * @return the recent-projects store
     */
    public RecentProjectsStore getRecentProjectsStore() {
        return recentProjectsStore;
    }

    /**
     * Returns the list of recently opened project paths (most recent first),
     * or an empty list if no {@link RecentProjectsStore} was configured.
     *
     * @return list of recent project paths
     */
    public List<Path> getRecentProjectPaths() {
        if (recentProjectsStore == null) {
            return List.of();
        }
        return recentProjectsStore.getRecentProjectPaths();
    }

    private void recordRecentProject(Path projectDir) {
        if (recentProjectsStore != null) {
            recentProjectsStore.addRecentProject(projectDir);
        }
    }

    private void writeDawProjectFile(DawProject dawProject, Path projectDir) throws IOException {
        if (projectDir == null) {
            return;
        }
        Path projectFile = projectDir.resolve(PROJECT_FILE_NAME);
        String xml = serializer.serialize(dawProject);
        Files.writeString(projectFile, xml);
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
