package com.benesquivelmusic.daw.sdk.edit;

import java.util.Set;
import java.util.UUID;

/**
 * A frame-based, multi-track time-range selection on the arrangement timeline.
 *
 * <p>This is the SDK contract for the cross-track marquee selection produced by
 * the arrangement-view {@code RANGE} edit tool. A {@code CrossTrackSelection}
 * captures a half-open time interval {@code [startFrames, endFrames)} together
 * with the set of tracks vertically spanned by the marquee, and is the input
 * shape for the range-aware {@code cut}, {@code copy}, {@code paste},
 * {@code duplicate}, and {@code delete} operations.</p>
 *
 * <p>Equivalents in other DAWs:</p>
 * <ul>
 *   <li>Pro Tools — the "Selector" tool dragged across multiple tracks</li>
 *   <li>Reaper — "time selection" combined with selected tracks</li>
 *   <li>Logic Pro — the "Marquee" tool</li>
 *   <li>Cubase — "Range Selection" tool</li>
 * </ul>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>The time range is half-open: {@code startFrames} is included and
 *       {@code endFrames} is excluded, matching every other range type in
 *       the SDK (see {@code PunchRegion}).</li>
 *   <li>The selection spans only the supplied {@code trackIds}; clips on
 *       other tracks are unaffected even if their playback range overlaps.</li>
 *   <li>The marquee is rectangular and contiguous in the UI, but the record
 *       itself stores an opaque {@link Set} of track ids, so callers may
 *       construct programmatic selections of any track membership.</li>
 *   <li>An empty {@code trackIds} set is permitted and represents a "no-op"
 *       selection: range-aware operations should treat it as a do-nothing.
 *       Callers can test this with {@link #isEmpty()}.</li>
 * </ul>
 *
 * <h2>Immutability</h2>
 * <p>The supplied {@code trackIds} set is defensively copied into an
 * unmodifiable snapshot, so the record is safe to share across threads and to
 * use as a key in undo entries (see {@code CompoundUndoableAction}).</p>
 *
 * @param startFrames the inclusive start of the selection, in absolute sample
 *                    frames; must be {@code >= 0}
 * @param endFrames   the exclusive end of the selection, in absolute sample
 *                    frames; must be {@code > startFrames}
 * @param trackIds    the set of tracks the marquee vertically spans;
 *                    must be non-{@code null} but may be empty (the empty
 *                    set means "no-op selection"). Stored as an unmodifiable
 *                    defensive copy.
 */
public record CrossTrackSelection(long startFrames, long endFrames, Set<UUID> trackIds) {

    /**
     * Canonical constructor; validates invariants and defensively copies
     * {@code trackIds} into an unmodifiable snapshot.
     *
     * @throws IllegalArgumentException if {@code startFrames} is negative or
     *                                  {@code endFrames} is not strictly
     *                                  greater than {@code startFrames}
     * @throws NullPointerException     if {@code trackIds} is {@code null} or
     *                                  contains a {@code null} element
     */
    public CrossTrackSelection {
        if (startFrames < 0) {
            throw new IllegalArgumentException(
                    "startFrames must not be negative: " + startFrames);
        }
        if (endFrames <= startFrames) {
            throw new IllegalArgumentException(
                    "endFrames must be greater than startFrames: startFrames="
                            + startFrames + ", endFrames=" + endFrames);
        }
        if (trackIds == null) {
            throw new NullPointerException("trackIds must not be null");
        }
        // Defensive, null-rejecting, unmodifiable snapshot.
        trackIds = Set.copyOf(trackIds);
    }

    /**
     * Convenience factory for a selection with a single track.
     *
     * @param startFrames the inclusive start, in sample frames
     * @param endFrames   the exclusive end, in sample frames
     * @param trackId     the single track in the selection
     * @return a {@code CrossTrackSelection} containing only {@code trackId}
     */
    public static CrossTrackSelection ofSingleTrack(long startFrames, long endFrames, UUID trackId) {
        return new CrossTrackSelection(startFrames, endFrames, Set.of(trackId));
    }

    /**
     * Returns the duration of the selection in sample frames.
     *
     * @return {@code endFrames - startFrames}
     */
    public long durationFrames() {
        return endFrames - startFrames;
    }

    /**
     * Returns {@code true} if no tracks are selected. Range operations
     * (cut/copy/paste/duplicate/delete) MUST treat an empty selection as a
     * no-op.
     *
     * @return {@code true} iff {@code trackIds} is empty
     */
    public boolean isEmpty() {
        return trackIds.isEmpty();
    }

    /**
     * Tests whether the given absolute sample frame lies within the selection's
     * time range. The range is half-open: {@code startFrames} is included and
     * {@code endFrames} is excluded.
     *
     * @param frame the absolute sample frame index
     * @return {@code true} iff {@code startFrames <= frame < endFrames}
     */
    public boolean containsFrame(long frame) {
        return frame >= startFrames && frame < endFrames;
    }

    /**
     * Tests whether the given track is part of this selection.
     *
     * @param trackId the track id to test (may be {@code null}, in which case
     *                {@code false} is returned)
     * @return {@code true} iff {@code trackId} is in {@link #trackIds()}
     */
    public boolean containsTrack(UUID trackId) {
        return trackId != null && trackIds.contains(trackId);
    }

    /**
     * Tests whether this selection's time range overlaps the half-open clip
     * range {@code [clipStartFrames, clipEndFrames)}. This is the core predicate
     * for finding clips that participate in a range operation; clips that
     * intersect but are not fully contained will be split at the selection
     * boundaries (using the existing {@code splitClip} primitive) before
     * cut/copy/delete is applied.
     *
     * @param clipStartFrames inclusive clip start, in sample frames
     * @param clipEndFrames   exclusive clip end, in sample frames; must be
     *                        {@code > clipStartFrames}
     * @return {@code true} iff the half-open ranges overlap
     * @throws IllegalArgumentException if {@code clipEndFrames <= clipStartFrames}
     */
    public boolean intersects(long clipStartFrames, long clipEndFrames) {
        if (clipEndFrames <= clipStartFrames) {
            throw new IllegalArgumentException(
                    "clipEndFrames must be greater than clipStartFrames: clipStartFrames="
                            + clipStartFrames + ", clipEndFrames=" + clipEndFrames);
        }
        return clipStartFrames < endFrames && clipEndFrames > startFrames;
    }

    /**
     * Returns a copy of this selection with the time range replaced.
     *
     * @param newStartFrames the new inclusive start
     * @param newEndFrames   the new exclusive end
     * @return a new {@code CrossTrackSelection} with the same track set and the
     *         supplied range
     */
    public CrossTrackSelection withRange(long newStartFrames, long newEndFrames) {
        return new CrossTrackSelection(newStartFrames, newEndFrames, trackIds);
    }

    /**
     * Returns a copy of this selection with the track set replaced.
     *
     * @param newTrackIds the new track set (defensively copied)
     * @return a new {@code CrossTrackSelection} with the same range and the
     *         supplied track set
     */
    public CrossTrackSelection withTrackIds(Set<UUID> newTrackIds) {
        return new CrossTrackSelection(startFrames, endFrames, newTrackIds);
    }

    /**
     * Translates the selection's time range by {@code deltaFrames}, preserving
     * the track set. Used by {@code paste} and {@code duplicate} to compute
     * the destination range for a previously copied selection while keeping
     * the relative inter-track offsets of its contents intact.
     *
     * @param deltaFrames signed offset in sample frames; the result must remain
     *                    non-negative
     * @return a translated {@code CrossTrackSelection}
     * @throws IllegalArgumentException if the translation would push
     *                                  {@code startFrames} below zero
     */
    public CrossTrackSelection shiftedBy(long deltaFrames) {
        long newStart = startFrames + deltaFrames;
        if (newStart < 0) {
            throw new IllegalArgumentException(
                    "shiftedBy would produce negative startFrames: " + newStart);
        }
        return new CrossTrackSelection(newStart, endFrames + deltaFrames, trackIds);
    }
}
