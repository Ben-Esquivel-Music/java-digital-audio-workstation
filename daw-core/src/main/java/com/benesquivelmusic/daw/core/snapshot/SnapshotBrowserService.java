package com.benesquivelmusic.daw.core.snapshot;

import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates project snapshots from three sources — autosaves on disk,
 * explicit user-created checkpoints, and undo points held in memory — and
 * exposes them as a single time-ordered timeline for the snapshot browser
 * UI.
 *
 * <h2>Retention policy</h2>
 * <ul>
 *   <li>Autosaves: retained for 7 days rolling.</li>
 *   <li>User checkpoints: retained for the lifetime of the service
 *       (intended to be persisted in the project archive — see story
 *       <em>Project Archive Format</em>).</li>
 *   <li>Undo-point snapshots: retained for the current session only and
 *       discarded when {@link #clearSession()} is called.</li>
 * </ul>
 *
 * <p>The service is purely a data layer: it does not load JavaFX classes
 * and can therefore be exercised in pure unit tests on a headless
 * machine.</p>
 */
public final class SnapshotBrowserService {

    /** Default rolling retention for autosaves: 7 days. */
    public static final Duration DEFAULT_AUTOSAVE_RETENTION = Duration.ofDays(7);

    /**
     * Filename glob applied to autosave directories. Restricts the service
     * to files matching the DAW's own autosave / checkpoint naming
     * conventions so that registering a misconfigured directory cannot
     * cause unrelated user files to appear in the browser or be deleted by
     * the cleanup actions.
     */
    private static final String AUTOSAVE_GLOB = "{autosave*,checkpoint*,*.daw}";

    private final List<SnapshotEntry> userCheckpoints = new ArrayList<>();
    private final List<SnapshotEntry> undoSnapshots = new ArrayList<>();
    private final List<Path> autosaveDirectories = new CopyOnWriteArrayList<>();
    private final Duration autosaveRetention;
    private final Clock clock;

    /** Creates a service with the default 7-day autosave retention. */
    public SnapshotBrowserService() {
        this(DEFAULT_AUTOSAVE_RETENTION, Clock.systemDefaultZone());
    }

    /**
     * Creates a service with the given retention and clock.
     *
     * @param autosaveRetention how long autosaves are retained
     * @param clock             the clock used for retention checks
     */
    public SnapshotBrowserService(Duration autosaveRetention, Clock clock) {
        this.autosaveRetention = Objects.requireNonNull(autosaveRetention,
                "autosaveRetention must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (autosaveRetention.isNegative() || autosaveRetention.isZero()) {
            throw new IllegalArgumentException(
                    "autosaveRetention must be positive: " + autosaveRetention);
        }
    }

    /**
     * Registers a directory whose files are treated as autosave snapshots.
     * Typical layout: {@code ~/.daw/autosaves/<project>/} or the
     * {@code checkpoints} directory of a {@link CheckpointManager}.
     *
     * @param directory the directory to watch
     */
    public void addAutosaveDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        if (!autosaveDirectories.contains(directory)) {
            autosaveDirectories.add(directory);
        }
    }

    /** Removes a previously registered autosave directory. */
    public void removeAutosaveDirectory(Path directory) {
        autosaveDirectories.remove(directory);
    }

    /** Returns the registered autosave directories. */
    public List<Path> getAutosaveDirectories() {
        return List.copyOf(autosaveDirectories);
    }

    /**
     * Records an explicit user-created checkpoint. The supplied content is
     * captured immediately. Returns the entry that was created.
     *
     * @param label   a short label such as {@code "Before mastering"}
     * @param content the serialised project state
     * @return the new entry
     */
    public SnapshotEntry createUserCheckpoint(String label, String content) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(content, "content must not be null");
        SnapshotEntry entry = new SnapshotEntry(
                "user-" + UUID.randomUUID(),
                Instant.now(clock),
                SnapshotKind.USER_CHECKPOINT,
                label,
                null,
                () -> content);
        userCheckpoints.add(entry);
        return entry;
    }

    /**
     * Records a snapshot tied to an entry on the {@link UndoManager}'s
     * history. The supplied content is captured immediately and is
     * discarded by {@link #clearSession()}.
     *
     * @param action  the undoable action this snapshot is paired with
     * @param content the serialised project state
     * @return the new entry
     */
    public SnapshotEntry recordUndoSnapshot(UndoableAction action, String content) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(content, "content must not be null");
        SnapshotEntry entry = new SnapshotEntry(
                "undo-" + UUID.randomUUID(),
                Instant.now(clock),
                SnapshotKind.UNDO_POINT,
                action.description(),
                null,
                () -> content);
        undoSnapshots.add(entry);
        return entry;
    }

    /**
     * Removes a previously recorded user checkpoint.
     *
     * @param entry the entry to remove
     * @return {@code true} if the entry was removed
     */
    public boolean removeUserCheckpoint(SnapshotEntry entry) {
        if (entry == null || entry.kind() != SnapshotKind.USER_CHECKPOINT) {
            return false;
        }
        return userCheckpoints.removeIf(e -> e.id().equals(entry.id()));
    }

    /**
     * Drops all undo-point snapshots — typically called when a project is
     * closed, since per the issue these are session-only.
     */
    public void clearSession() {
        undoSnapshots.clear();
    }

    /**
     * Permanently deletes autosave files older than the configured
     * retention. Only files matching the DAW's autosave / checkpoint
     * naming pattern are touched, so registering a directory containing
     * unrelated files is safe.
     *
     * @return the number of files actually deleted
     */
    public int purgeExpiredAutosaves() {
        int deleted = 0;
        Instant cutoff = Instant.now(clock).minus(autosaveRetention);
        for (Path dir : autosaveDirectories) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, AUTOSAVE_GLOB)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) continue;
                    BasicFileAttributes attr =
                            Files.readAttributes(file, BasicFileAttributes.class);
                    Instant t = attr.lastModifiedTime().toInstant();
                    if (t.isBefore(cutoff)) {
                        try {
                            if (Files.deleteIfExists(file)) {
                                deleted++;
                            }
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    }
                }
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
        return deleted;
    }

    /**
     * Deletes <em>all</em> autosave files in registered directories
     * regardless of age. Used by the cleanup UI's "purge all" action.
     * Only files matching the autosave / checkpoint naming pattern are
     * touched.
     *
     * @return the number of files actually deleted
     */
    public int purgeAllAutosaves() {
        int deleted = 0;
        for (Path dir : autosaveDirectories) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, AUTOSAVE_GLOB)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) continue;
                    try {
                        if (Files.deleteIfExists(file)) {
                            deleted++;
                        }
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                }
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
        return deleted;
    }

    /**
     * Returns the timeline of all snapshot entries currently visible to the
     * browser, sorted by timestamp ascending (oldest first).
     *
     * <p>Autosave entries that have already aged out of the retention
     * window are filtered out — purging the files themselves can be
     * triggered separately via {@link #purgeExpiredAutosaves()}.</p>
     *
     * @return the sorted, immutable list of entries
     */
    public List<SnapshotEntry> getEntries() {
        List<SnapshotEntry> all = new ArrayList<>();
        all.addAll(userCheckpoints);
        all.addAll(undoSnapshots);
        all.addAll(loadAutosaves());
        all.sort(Comparator.comparing(SnapshotEntry::timestamp));
        return Collections.unmodifiableList(all);
    }

    private List<SnapshotEntry> loadAutosaves() {
        List<SnapshotEntry> list = new ArrayList<>();
        Instant cutoff = Instant.now(clock).minus(autosaveRetention);
        for (Path dir : autosaveDirectories) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, AUTOSAVE_GLOB)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) continue;
                    BasicFileAttributes attr;
                    try {
                        attr = Files.readAttributes(file, BasicFileAttributes.class);
                    } catch (IOException e) {
                        continue;
                    }
                    Instant t = attr.lastModifiedTime().toInstant();
                    if (t.isBefore(cutoff)) continue;
                    Path filePath = file;
                    // Include the absolute path hash so entries from
                    // different directories with the same filename
                    // (e.g. checkpoint-001-...) get distinct IDs.
                    String idSuffix = Integer.toHexString(
                            filePath.toAbsolutePath().toString().hashCode());
                    list.add(new SnapshotEntry(
                            "autosave-" + idSuffix + "-" + filePath.getFileName(),
                            t,
                            SnapshotKind.AUTOSAVE,
                            filePath.getFileName().toString(),
                            null,
                            () -> {
                                try {
                                    return Files.readString(filePath);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }));
                }
            } catch (IOException ignored) {
                // best-effort listing
            }
        }
        return list;
    }
}
