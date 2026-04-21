package com.benesquivelmusic.daw.core.audio.processing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manifest of prior {@code sourceAsset} files produced by destructive
 * clip operations (see {@link ClipProcessingService}).
 *
 * <p>The history is keyed by {@link com.benesquivelmusic.daw.core.audio.AudioClip}
 * id and retains the chronologically ordered list of previous asset
 * files that were superseded by a destructive operation on that clip,
 * so that undo can restore the clip's original asset reference even
 * across session reloads.</p>
 *
 * <p><b>Retention policy.</b> {@link #purgeUnused()} keeps the
 * {@link #retention()} most recent prior assets per clip <em>plus</em>
 * any asset currently {@linkplain #pin(Path) pinned} (typically
 * because an {@link com.benesquivelmusic.daw.core.undo.UndoableAction}
 * on the undo or redo stack references it). All other tracked files
 * are deleted from disk and removed from the manifest.</p>
 *
 * <p>Instances are thread-safe via intrinsic locking.</p>
 */
public final class ClipAssetHistory {

    /** Default number of most-recent prior assets kept per clip. */
    public static final int DEFAULT_RETENTION = 5;

    private final int retention;
    private final Map<String, Deque<Path>> perClip = new HashMap<>();
    private final Map<Path, Integer> pinCounts = new HashMap<>();

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
     * Marks {@code asset} as referenced by an undo or redo stack entry,
     * preventing {@link #purgeUnused()} from deleting it. Pins are
     * reference-counted: multiple {@code pin} calls must be balanced by
     * an equal number of {@link #unpin} calls.
     */
    public synchronized void pin(Path asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        pinCounts.merge(asset, 1, Integer::sum);
    }

    /** Releases a pin previously taken by {@link #pin(Path)}. */
    public synchronized void unpin(Path asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        pinCounts.computeIfPresent(asset, (p, c) -> c <= 1 ? null : c - 1);
    }

    /** Returns whether {@code asset} is currently pinned. */
    public synchronized boolean isPinned(Path asset) {
        return pinCounts.containsKey(asset);
    }

    /**
     * Deletes and forgets prior-asset files that lie outside the
     * retention window and are not currently {@linkplain #pin pinned}.
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
                } else {
                    if (Files.deleteIfExists(p)) {
                        deleted.add(p);
                    }
                }
            }
            e.setValue(keep);
        }
        return Collections.unmodifiableList(deleted);
    }
}
