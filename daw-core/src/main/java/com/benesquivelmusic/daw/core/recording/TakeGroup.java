package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A stack of recorded {@link Take}s captured at the same clip slot during a
 * loop-record session.
 *
 * <p>During loop-record, every loop lap produces a new {@link Take}; all takes
 * belonging to the same capture session are collected here. Only the take at
 * {@code activeIndex} is played back on the track lane, mirroring how modern
 * DAWs present stacked takes. Later, the comping UI
 * ({@link com.benesquivelmusic.daw.core.comping.TakeComping}) can select sub-ranges
 * from multiple takes to assemble a composite.</p>
 *
 * <p>This record is immutable — use {@link #withActiveIndex(int)} /
 * {@link #withTakes(List)} to derive a new group.</p>
 *
 * @param id          stable identifier for this group (persisted with the project)
 * @param takes       the takes in capture order (index 0 = first loop lap)
 * @param activeIndex index of the take currently routed to the track lane
 */
public record TakeGroup(UUID id, List<Take> takes, int activeIndex) {

    public TakeGroup {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(takes, "takes must not be null");
        // Defensive copy so the record is truly immutable.
        takes = List.copyOf(takes);
        if (!takes.isEmpty() && (activeIndex < 0 || activeIndex >= takes.size())) {
            throw new IllegalArgumentException(
                    "activeIndex out of range: " + activeIndex + " (takes: " + takes.size() + ")");
        }
        if (takes.isEmpty() && activeIndex != 0) {
            throw new IllegalArgumentException(
                    "activeIndex must be 0 for an empty take group, was " + activeIndex);
        }
    }

    /**
     * Creates an empty take group with a fresh {@link UUID}.
     */
    public static TakeGroup empty() {
        return new TakeGroup(UUID.randomUUID(), List.of(), 0);
    }

    /**
     * Creates a group containing the given takes with the first take active
     * and a fresh {@link UUID}.
     */
    public static TakeGroup of(List<Take> takes) {
        return new TakeGroup(UUID.randomUUID(), takes, 0);
    }

    /** Returns the number of takes currently stacked in this group. */
    public int size() {
        return takes.size();
    }

    /** Returns whether this group has no takes. */
    public boolean isEmpty() {
        return takes.isEmpty();
    }

    /**
     * Returns the currently active {@link Take}, i.e. the one routed to the
     * track lane for playback.
     *
     * @throws IllegalStateException if the group is empty
     */
    public Take activeTake() {
        if (takes.isEmpty()) {
            throw new IllegalStateException("TakeGroup is empty");
        }
        return takes.get(activeIndex);
    }

    /** Returns the {@link AudioClip} backing the currently active take. */
    public AudioClip activeClip() {
        return activeTake().clip();
    }

    /**
     * Returns a new {@link TakeGroup} with the given take appended. The
     * {@code activeIndex} is preserved unless this group is empty, in which
     * case the new take becomes active.
     */
    public TakeGroup withTakeAppended(Take take) {
        Objects.requireNonNull(take, "take must not be null");
        var next = new java.util.ArrayList<Take>(takes.size() + 1);
        next.addAll(takes);
        next.add(take);
        int nextActive = takes.isEmpty() ? 0 : activeIndex;
        return new TakeGroup(id, next, nextActive);
    }

    /** Returns a copy of this group with the given {@code activeIndex}. */
    public TakeGroup withActiveIndex(int newActiveIndex) {
        return new TakeGroup(id, takes, newActiveIndex);
    }

    /**
     * Returns a copy of this group with the given takes. The active index is
     * clamped into the new take list; the caller may use
     * {@link #withActiveIndex(int)} afterwards to override.
     */
    public TakeGroup withTakes(List<Take> newTakes) {
        Objects.requireNonNull(newTakes, "newTakes must not be null");
        int clamped = newTakes.isEmpty()
                ? 0
                : Math.min(Math.max(0, activeIndex), newTakes.size() - 1);
        return new TakeGroup(id, newTakes, clamped);
    }

    /**
     * Returns a new group advancing the active take by one (wrapping around).
     * Equivalent to the "click to cycle active take" gesture on the take-stack
     * overlay. Returns {@code this} if the group has fewer than two takes.
     */
    public TakeGroup cycleActive() {
        if (takes.size() < 2) {
            return this;
        }
        return withActiveIndex((activeIndex + 1) % takes.size());
    }
}
