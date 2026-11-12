package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Computes per-project disk usage for the Backups settings and the project
 * hub (story 335, ruling R2).
 *
 * <p>Breaks the project directory into four categories:</p>
 * <ul>
 *   <li><b>checkpoints</b> — the regular files directly in
 *       {@code checkpoints/} whose names {@link CheckpointFileName#parse}
 *       accepts: exactly the files a checkpoint retention policy keeps or
 *       deletes and whose bytes its space cap measures;</li>
 *   <li><b>snapshots</b> — the {@code snapshots/} tree
 *       ({@link SnapshotStore#SNAPSHOTS_DIR}), which no retention deletes;</li>
 *   <li><b>archives</b> — the {@code archives/} tree written by the project
 *       archiver;</li>
 *   <li><b>other</b> — everything else under the project directory: the
 *       project document, audio, assets, the journal, and every file in
 *       {@code checkpoints/} that is no checkpoint (its ordering fact, a
 *       writer's temp, a stray file).</li>
 * </ul>
 *
 * <p>Sizes are computed by summing file lengths, in one walk of the project
 * directory that classifies each file once. The project directory itself may
 * be a symbolic link, and the walk goes through it; no link under it is
 * followed — each entry is read without following links, so a linked folder
 * is not descended into and a linked file is not counted. A file or folder
 * that disappears while the walk runs — a checkpoint a prune deletes, a
 * writer's temp renamed into place — counts as absent; any other failure to
 * read the tree fails the computation.</p>
 *
 * @param checkpointBytes total bytes of the checkpoints
 * @param checkpointCount the number of checkpoints
 * @param snapshotBytes   total bytes under {@code snapshots/}
 * @param archiveBytes    total bytes under {@code archives/}
 * @param otherBytes      total bytes elsewhere under the project directory
 */
public record ProjectDiskUsage(long checkpointBytes, int checkpointCount, long snapshotBytes,
                               long archiveBytes, long otherBytes) {

    /** The {@code checkpoints/} subdirectory, where {@code CheckpointManager} writes a project's checkpoints. */
    public static final String AUTOSAVES_DIR = "checkpoints";
    /** Subdirectory holding packaged project archives ({@link ProjectManager#ARCHIVES_DIR_NAME}). */
    public static final String ARCHIVES_DIR = ProjectManager.ARCHIVES_DIR_NAME;

    public ProjectDiskUsage {
        if (checkpointBytes < 0 || checkpointCount < 0 || snapshotBytes < 0 || archiveBytes < 0 || otherBytes < 0) {
            throw new IllegalArgumentException("byte counts and the checkpoint count must be >= 0");
        }
    }

    /** Returns the total size of all four categories. */
    public long totalBytes() {
        return checkpointBytes + snapshotBytes + archiveBytes + otherBytes;
    }

    /**
     * Computes disk usage for a project directory.
     *
     * @param projectDirectory the project root
     * @return the breakdown, or zeros if the directory does not exist
     * @throws IOException if the project directory, a folder under it or a
     *                     file's attributes cannot be read — except a file or
     *                     folder that disappeared while the walk ran, which
     *                     counts as absent
     */
    public static ProjectDiskUsage compute(Path projectDirectory) throws IOException {
        return compute(projectDirectory, ATTRIBUTES_NOT_FOLLOWING_LINKS);
    }

    /**
     * Reads one entry's attributes during the walk, without following links:
     * the walk's seam, so a test can make an entry vanish between the listing
     * of its folder and the read of its attributes.
     */
    @FunctionalInterface
    interface AttributeReader {
        BasicFileAttributes read(Path entry) throws IOException;
    }

    private static final AttributeReader ATTRIBUTES_NOT_FOLLOWING_LINKS =
            entry -> Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

    /** As {@link #compute(Path)}, reading each entry's attributes with {@code attributes}. */
    static ProjectDiskUsage compute(Path projectDirectory, AttributeReader attributes) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        if (!Files.isDirectory(projectDirectory)) {
            return new ProjectDiskUsage(0L, 0, 0L, 0L, 0L);
        }
        Tally tally = new Tally(projectDirectory);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(projectDirectory)) {
            walk(entries, attributes, tally);
        }
        return new ProjectDiskUsage(tally.checkpointBytes, tally.checkpointCount, tally.snapshotBytes,
                tally.archiveBytes, tally.otherBytes);
    }

    /**
     * Adds every regular file listed by {@code entries}, and by the folders
     * under them, to {@code tally}. An entry or folder that is gone by the
     * time it is read counts as absent: a prune or a writer's rename racing
     * the scan is normal, not a failure.
     */
    private static void walk(DirectoryStream<Path> entries, AttributeReader attributes, Tally tally)
            throws IOException {
        try {
            for (Path entry : entries) {
                BasicFileAttributes read;
                try {
                    read = attributes.read(entry);
                } catch (NoSuchFileException vanished) {
                    // Gone since its folder was listed: a pruned checkpoint, a renamed temp.
                    continue;
                }
                if (read.isDirectory()) {
                    DirectoryStream<Path> children;
                    try {
                        children = Files.newDirectoryStream(entry);
                    } catch (NoSuchFileException vanished) {
                        // The folder went after its attributes were read: it holds nothing now.
                        continue;
                    }
                    try (children) {
                        walk(children, attributes, tally);
                    }
                } else if (read.isRegularFile()) {
                    tally.add(entry, read.size());
                }
            }
        } catch (DirectoryIteratorException failed) {
            if (!(failed.getCause() instanceof NoSuchFileException)) {
                throw failed.getCause();
            }
            // The folder went while it was listed: what was not listed yet is gone.
        }
    }

    /** The four categories' running totals of one walk. */
    private static final class Tally {

        private final Path checkpoints;
        private final Path snapshots;
        private final Path archives;
        private long checkpointBytes;
        private int checkpointCount;
        private long snapshotBytes;
        private long archiveBytes;
        private long otherBytes;

        Tally(Path projectDirectory) {
            this.checkpoints = projectDirectory.resolve(AUTOSAVES_DIR);
            this.snapshots = projectDirectory.resolve(SnapshotStore.SNAPSHOTS_DIR);
            this.archives = projectDirectory.resolve(ARCHIVES_DIR);
        }

        /** Counts one regular file, never followed through a link, in its one category. */
        void add(Path file, long size) {
            if (checkpoints.equals(file.getParent())
                    && CheckpointFileName.parse(file.getFileName().toString()).isPresent()) {
                checkpointBytes += size;
                checkpointCount++;
            } else if (file.startsWith(snapshots)) {
                snapshotBytes += size;
            } else if (file.startsWith(archives)) {
                archiveBytes += size;
            } else {
                otherBytes += size;
            }
        }
    }

    /**
     * Returns the usable bytes on the file store backing {@code directory} —
     * the §1.8 disk-space contract input for the Session Status Strip (story
     * 295). A "plain value" the JavaFX-free core supplies; the UI's 30&nbsp;s
     * background disk scan calls this off the FX thread (the FX thread must
     * never call {@link FileStore#getUsableSpace()} —
     * {@code javafx-application-design} §11).
     *
     * <p>When {@code directory} does not exist yet (e.g. a not-yet-saved
     * project) the nearest existing ancestor's file store is measured, so a
     * fresh project still reports a meaningful free-space figure. Returns
     * {@code -1} when no ancestor exists or the store cannot be queried.</p>
     *
     * @param directory the directory whose file store to measure
     * @return usable bytes on that file store, or {@code -1} if unknown
     */
    public static long usableSpaceBytes(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        Path probe = directory.toAbsolutePath();
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return -1L;
        }
        try {
            return Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return -1L;
        }
    }
}
