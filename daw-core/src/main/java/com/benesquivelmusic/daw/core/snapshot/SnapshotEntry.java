package com.benesquivelmusic.daw.core.snapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A single entry in the snapshot history timeline.
 *
 * <p>Entries are produced from three sources — autosaves on disk, explicit
 * user-created checkpoints, and points in the {@link
 * com.benesquivelmusic.daw.core.undo.UndoManager} history — and are surfaced
 * uniformly to the snapshot browser UI.</p>
 *
 * <p>The {@code contentSupplier} returns the serialised project state for
 * the snapshot when invoked. It is modelled as a {@link Supplier} rather
 * than a plain {@code String} so that on-disk autosaves are only loaded
 * lazily when the user actually opens the entry, while in-memory undo and
 * checkpoint snapshots can capture the state eagerly.</p>
 *
 * @param id              a stable identifier for the snapshot
 * @param timestamp       when the snapshot was taken
 * @param kind            the trigger that created it
 * @param label           a short human-readable label (e.g. action description
 *                        for an undo point or filename for an autosave)
 * @param summary         a short description of notable changes since the
 *                        previous snapshot, or {@code null} when unknown
 * @param contentSupplier supplies the serialised project state on demand
 */
public record SnapshotEntry(
        String id,
        Instant timestamp,
        SnapshotKind kind,
        String label,
        String summary,
        Supplier<String> contentSupplier) {

    public SnapshotEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(contentSupplier, "contentSupplier must not be null");
    }

    /**
     * Loads the serialised project state for this snapshot.
     *
     * <p>For in-memory entries (user checkpoints, undo points) the
     * content is captured eagerly and is always returned. For autosave
     * entries the file is read lazily, and an {@link
     * java.io.UncheckedIOException} may be thrown if the underlying file
     * has since been moved, deleted, or become unreadable.</p>
     *
     * @return the serialised content
     */
    public String loadContent() {
        return contentSupplier.get();
    }
}
