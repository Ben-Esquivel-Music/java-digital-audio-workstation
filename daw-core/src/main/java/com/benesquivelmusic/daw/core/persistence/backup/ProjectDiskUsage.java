package com.benesquivelmusic.daw.core.persistence.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Computes per-project disk-usage for the backup-settings UI.
 *
 * <p>Breaks the project directory into the three categories that the issue
 * asks the pie chart to display: <b>autosaves</b> (the
 * {@code checkpoints/} subdirectory used by {@code CheckpointManager}),
 * <b>archives</b> (the {@code archives/} subdirectory written by the
 * project archiver), and <b>assets</b> (everything else under the
 * project directory — audio files, project XML, etc.).</p>
 *
 * <p>Sizes are computed by recursively summing file lengths. Symlinks are
 * not followed.</p>
 *
 * @param autosavesBytes total bytes under {@code checkpoints/}
 * @param archivesBytes  total bytes under {@code archives/}
 * @param assetsBytes    total bytes elsewhere under the project directory
 */
public record ProjectDiskUsage(long autosavesBytes, long archivesBytes, long assetsBytes) {

    /** Subdirectory holding rotating checkpoints / autosaves. */
    public static final String AUTOSAVES_DIR = "checkpoints";
    /** Subdirectory holding packaged project archives. */
    public static final String ARCHIVES_DIR = "archives";

    public ProjectDiskUsage {
        if (autosavesBytes < 0 || archivesBytes < 0 || assetsBytes < 0) {
            throw new IllegalArgumentException("byte counts must be >= 0");
        }
    }

    /** Returns the total size of all three categories. */
    public long totalBytes() {
        return autosavesBytes + archivesBytes + assetsBytes;
    }

    /**
     * Computes disk usage for a project directory.
     *
     * @param projectDirectory the project root
     * @return the breakdown, or zeros if the directory does not exist
     * @throws IOException if a directory walk fails
     */
    public static ProjectDiskUsage compute(Path projectDirectory) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        if (!Files.isDirectory(projectDirectory)) {
            return new ProjectDiskUsage(0L, 0L, 0L);
        }
        long autosaves = sizeOf(projectDirectory.resolve(AUTOSAVES_DIR));
        long archives = sizeOf(projectDirectory.resolve(ARCHIVES_DIR));
        long total = sizeOf(projectDirectory);
        long assets = Math.max(0L, total - autosaves - archives);
        return new ProjectDiskUsage(autosaves, archives, assets);
    }

    private static long sizeOf(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0L;
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            return Files.size(root);
        }
        // Files.walk without FOLLOW_LINKS does not traverse symlinked
        // subdirectories, and the NOFOLLOW_LINKS check below excludes
        // symlinked files — so the total is bounded by the project tree.
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        }
    }
}
