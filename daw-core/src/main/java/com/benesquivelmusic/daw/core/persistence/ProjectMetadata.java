package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata for a DAW project.
 *
 * @param name         the project name
 * @param createdAt    when the project was first created
 * @param lastModified when the project was last modified
 * @param projectPath  the filesystem path to the project directory (may be {@code null} if not yet saved)
 */
public record ProjectMetadata(
        String name,
        Instant createdAt,
        Instant lastModified,
        Path projectPath
) {
    public ProjectMetadata {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(lastModified, "lastModified must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Creates metadata for a new project that has not been saved to disk.
     *
     * @param name the project name
     * @return fresh project metadata
     */
    public static ProjectMetadata createNew(String name) {
        Instant now = Instant.now();
        return new ProjectMetadata(name, now, now, null);
    }

    /**
     * Returns a copy of this metadata with an updated modification timestamp.
     *
     * @return updated metadata
     */
    public ProjectMetadata touch() {
        return new ProjectMetadata(name, createdAt, Instant.now(), projectPath);
    }

    /**
     * Returns a copy of this metadata with the specified project path.
     *
     * @param path the project directory
     * @return updated metadata
     */
    public ProjectMetadata withPath(Path path) {
        return new ProjectMetadata(name, createdAt, lastModified, path);
    }

    /**
     * Returns a copy of this metadata with the specified name.
     *
     * @param newName the new project name
     * @return updated metadata
     */
    public ProjectMetadata withName(String newName) {
        return new ProjectMetadata(newName, createdAt, Instant.now(), projectPath);
    }
}
