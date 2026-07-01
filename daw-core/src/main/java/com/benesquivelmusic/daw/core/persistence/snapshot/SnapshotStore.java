package com.benesquivelmusic.daw.core.persistence.snapshot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Durable, user-named project snapshots.
 *
 * <p>A snapshot is a full copy of {@code project.daw} under the project's
 * {@code snapshots/} directory. Unlike autosave checkpoints, these files are
 * never rotated by retention policy; deletion is always an explicit user
 * decision outside this store.</p>
 */
public final class SnapshotStore {

    /** Project-relative directory that contains named snapshots. */
    public static final String SNAPSHOTS_DIR = "snapshots";
    /** Project document file copied into each snapshot. */
    public static final String PROJECT_FILE_NAME = "project.daw";

    private static final DateTimeFormatter STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withLocale(Locale.ROOT)
                    .withZone(ZoneId.systemDefault());
    private static final String EXTENSION = ".daw";

    private final Clock clock;

    /** Creates a store using the system clock. */
    public SnapshotStore() {
        this(Clock.systemDefaultZone());
    }

    /** Creates a store using an explicit clock, primarily for tests. */
    public SnapshotStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Metadata for one named snapshot file.
     *
     * @param path      the snapshot file
     * @param name      user-facing name reconstructed from the filename slug
     * @param timestamp the file's last-modified timestamp
     * @param sizeBytes the snapshot size
     */
    public record Snapshot(Path path, String name, Instant timestamp, long sizeBytes) {
        public Snapshot {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must be >= 0: " + sizeBytes);
            }
        }
    }

    /**
     * Copies {@code projectDir/project.daw} into {@code projectDir/snapshots}
     * using temp-file + fsync + atomic move where the host filesystem supports
     * it.
     *
     * @param projectDir project directory containing {@code project.daw}
     * @param name       user-supplied snapshot name
     * @return metadata for the created snapshot
     * @throws IOException if the project file cannot be copied
     */
    public Snapshot createSnapshot(Path projectDir, String name) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        return createSnapshotFromFile(projectDir.resolve(PROJECT_FILE_NAME),
                projectDir.resolve(SNAPSHOTS_DIR), name);
    }

    /**
     * Copies an explicit project file into an explicit snapshot directory.
     * Package callers use {@link #createSnapshot(Path, String)}; this overload
     * keeps tests and recovery tools from needing to synthesize a full project
     * layout.
     */
    public Snapshot createSnapshotFromFile(Path projectFile,
                                           Path snapshotDirectory,
                                           String name) throws IOException {
        Objects.requireNonNull(projectFile, "projectFile must not be null");
        Objects.requireNonNull(snapshotDirectory, "snapshotDirectory must not be null");
        if (!Files.isRegularFile(projectFile)) {
            throw new IOException("Project file not found: " + projectFile);
        }
        String displayName = normalizeName(name);
        Files.createDirectories(snapshotDirectory);

        Path target = uniqueTarget(snapshotDirectory, displayName, Instant.now(clock));
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(projectFile, tmp, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        forceFile(tmp);
        moveAtomically(tmp, target);
        forceDirectory(snapshotDirectory);

        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);
        return new Snapshot(target, displayName,
                attrs.lastModifiedTime().toInstant(), attrs.size());
    }

    /** Lists named snapshots newest-first. */
    public List<Snapshot> listSnapshots(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        return listSnapshotDirectory(projectDir.resolve(SNAPSHOTS_DIR));
    }

    /** Lists snapshot files in the given directory newest-first. */
    public List<Snapshot> listSnapshotDirectory(Path snapshotDirectory) throws IOException {
        Objects.requireNonNull(snapshotDirectory, "snapshotDirectory must not be null");
        if (!Files.isDirectory(snapshotDirectory)) {
            return List.of();
        }
        List<Snapshot> snapshots = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotDirectory, "*" + EXTENSION)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                snapshots.add(new Snapshot(file, nameFromFile(file),
                        attrs.lastModifiedTime().toInstant(), attrs.size()));
            }
        }
        snapshots.sort(Comparator.comparing(Snapshot::timestamp).reversed()
                .thenComparing(s -> s.path().getFileName().toString()));
        return List.copyOf(snapshots);
    }

    private Path uniqueTarget(Path dir, String displayName, Instant timestamp) {
        String base = STAMP_FORMAT.format(timestamp) + "-" + slug(displayName);
        Path target = dir.resolve(base + EXTENSION);
        int suffix = 1;
        while (Files.exists(target)) {
            target = dir.resolve(base + "-" + suffix + EXTENSION);
            suffix++;
        }
        return target;
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "Snapshot" : trimmed;
    }

    private static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "snapshot" : slug;
    }

    private static String nameFromFile(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(EXTENSION)) {
            name = name.substring(0, name.length() - EXTENSION.length());
        }
        if (name.length() > "yyyyMMdd-HHmmss-".length()) {
            name = name.substring("yyyyMMdd-HHmmss-".length());
        }
        return name.replace('-', ' ');
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some platforms/filesystems do not permit opening directories.
            // The file itself has already been forced; directory fsync is best effort.
        }
    }

    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
