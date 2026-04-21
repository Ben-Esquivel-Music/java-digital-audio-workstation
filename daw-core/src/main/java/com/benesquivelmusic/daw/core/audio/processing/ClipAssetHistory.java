package com.benesquivelmusic.daw.core.audio.processing;

import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory manifest of prior {@code sourceAsset} files produced by
 * destructive clip operations (see {@link ClipProcessingService}).
 *
 * <p>The history is keyed by {@link com.benesquivelmusic.daw.core.audio.AudioClip}
 * id and retains the chronologically ordered list of previous asset
 * files that were superseded by a destructive operation on that clip,
 * so that undo can restore the clip's original asset reference for as
 * long as this in-memory manifest exists (typically the current session;
 * project-bundle serialization is out of scope for this story and would
 * be layered on without API changes).</p>
 *
 * <p><b>Retention policy.</b> {@link #purgeUnused()} keeps the
 * {@link #retention()} most recent prior assets per clip <em>plus</em>
 * any asset currently in the {@linkplain #pinnedAssets() pin set}
 * (typically because an {@link UndoableAction} on the undo or redo
 * stack references it — see {@link #syncPinsFromHistory(UndoManager)}).
 * Only files previously {@linkplain #markManaged(Path) marked as
 * managed} by {@link ClipProcessingService} are ever deleted from disk;
 * external/original user-imported source files are retained in the
 * manifest but never unlinked, eliminating the risk of deleting files
 * the DAW did not itself create.</p>
 *
 * <p>Instances are thread-safe via intrinsic locking.</p>
 */
public final class ClipAssetHistory {

    /** Default number of most-recent prior assets kept per clip. */
    public static final int DEFAULT_RETENTION = 5;

    private final int retention;
    private final Map<String, Deque<Path>> perClip = new HashMap<>();
    private final Set<Path> pinned = new HashSet<>();
    private final Set<Path> managed = new HashSet<>();

    /** Creates a history with the {@link #DEFAULT_RETENTION default} retention window. */
    public ClipAssetHistory() {
        this(DEFAULT_RETENTION);
    }

    /**
     * Creates a history with a custom retention window.
     *
     * @param retention the number of most-recent prior assets to keep per clip
     * @throws IllegalArgumentException if {@code retention &lt; 1}
     */
    public ClipAssetHistory(int retention) {
        if (retention < 1) {
            throw new IllegalArgumentException("retention must be >= 1: " + retention);
        }
        this.retention = retention;
    }

    /** Returns the per-clip retention window. */
    public int retention() {
        return retention;
    }

    /**
     * Records {@code priorAsset} as a previous asset of {@code clipId}.
     *
     * <p>The recorded path is considered external by default and will
     * <em>not</em> be deleted by {@link #purgeUnused()}. To allow
     * {@code purgeUnused()} to unlink the file, also call
     * {@link #markManaged(Path)} (which {@link ClipProcessingService}
     * does for files it generated itself).</p>
     *
     * @param clipId     the {@link com.benesquivelmusic.daw.core.audio.AudioClip#getId() clip id}
     * @param priorAsset the file path that was replaced
     */
    public synchronized void recordPriorAsset(String clipId, Path priorAsset) {
        Objects.requireNonNull(clipId, "clipId must not be null");
        Objects.requireNonNull(priorAsset, "priorAsset must not be null");
        perClip.computeIfAbsent(clipId, k -> new ArrayDeque<>()).addLast(priorAsset);
    }

    /** Returns the chronologically ordered prior assets for {@code clipId}. */
    public synchronized List<Path> priorAssets(String clipId) {
        Deque<Path> q = perClip.get(clipId);
        return q == null ? List.of() : List.copyOf(q);
    }

    /** Returns an immutable snapshot of all tracked clip ids. */
    public synchronized List<String> clipIds() {
        return List.copyOf(perClip.keySet());
    }

    /**
     * Marks {@code asset} as DAW-generated and therefore eligible for
     * deletion by {@link #purgeUnused()}. Paths that are never marked
     * managed (typically: original user-imported source files) are
     * tracked in the manifest but preserved on disk forever.
     */
    public synchronized void markManaged(Path asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        managed.add(asset);
    }

    /** Returns whether {@code asset} was {@linkplain #markManaged marked managed}. */
    public synchronized boolean isManaged(Path asset) {
        return managed.contains(asset);
    }

    /**
     * Rebuilds the pin set from the live contents of {@code undoManager}'s
     * history, pinning exactly those assets referenced by actions that
     * implement {@link ClipAssetReferencing} (including members of any
     * {@link CompoundUndoableAction}). Actions that were discarded via
     * {@code UndoManager.trimHistory()} or a redo-stack clear are
     * automatically released — there are no leaked pins.
     *
     * <p>Typical usage: attach a
     * {@link com.benesquivelmusic.daw.core.undo.UndoHistoryListener}
     * that calls this method after every history mutation.</p>
     *
     * @return the number of assets currently pinned
     */
    public synchronized int syncPinsFromHistory(UndoManager undoManager) {
        Objects.requireNonNull(undoManager, "undoManager must not be null");
        pinned.clear();
        for (UndoableAction action : undoManager.getHistory()) {
            collectReferencedAssets(action, pinned);
        }
        return pinned.size();
    }

    private static void collectReferencedAssets(UndoableAction action, Set<Path> out) {
        if (action instanceof ClipAssetReferencing ref) {
            out.addAll(ref.referencedAssets());
        } else if (action instanceof CompoundUndoableAction compound) {
            for (UndoableAction child : compound.getActions()) {
                collectReferencedAssets(child, out);
            }
        }
    }

    /** Returns whether {@code asset} is currently pinned. */
    public synchronized boolean isPinned(Path asset) {
        return pinned.contains(asset);
    }

    /** Returns an immutable snapshot of the current pin set. */
    public synchronized Set<Path> pinnedAssets() {
        return Set.copyOf(pinned);
    }

    /**
     * Deletes and forgets {@linkplain #markManaged managed} prior-asset
     * files that lie outside the retention window and are not currently
     * {@linkplain #isPinned pinned}. Un-managed (external) paths are
     * left in place on disk but dropped from the manifest once they
     * leave the retention window.
     *
     * @return the paths whose files were deleted from disk
     * @throws IOException if an underlying filesystem deletion fails
     */
    public synchronized List<Path> purgeUnused() throws IOException {
        List<Path> deleted = new ArrayList<>();
        for (Map.Entry<String, Deque<Path>> e : perClip.entrySet()) {
            List<Path> all = new ArrayList<>(e.getValue());
            int size = all.size();
            int evictBelow = Math.max(0, size - retention);
            Deque<Path> keep = new ArrayDeque<>();
            for (int i = 0; i < size; i++) {
                Path p = all.get(i);
                if (i >= evictBelow || isPinned(p)) {
                    keep.addLast(p);
                } else if (isManaged(p)) {
                    if (Files.deleteIfExists(p)) {
                        deleted.add(p);
                    }
                    managed.remove(p);
                }
                // else: un-managed external file — drop from manifest but keep on disk.
            }
            e.setValue(keep);
        }
        return Collections.unmodifiableList(deleted);
    }
}

