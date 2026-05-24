package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.clip.LockedClipException;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that nudges a group of {@link AudioClip}s by a
 * common beat delta.
 *
 * <p>"Nudge" is the small, keyboard-driven movement produced by the
 * application's nudge shortcuts. Semantically it is a move by a tiny,
 * precise amount — mechanically identical to a group move — but it is
 * recorded as a dedicated action so the undo stack reads "Nudge Clips"
 * rather than "Move Clips" and so multi-selection nudges collapse into
 * a single undo step (the explicit requirement from the user story).</p>
 *
 * <p>To avoid negative timeline positions, the requested delta is
 * clamped at execution time by the distance from the earliest clip in
 * the group to beat {@code 0}. Relative spacing between clips is
 * therefore always preserved.</p>
 *
 * <p>Story 283 — entries are now {@code (Track, AudioClip)} pairs so
 * {@code execute()}/{@code undo()} can publish
 * {@code ClipEvent.Moved} per leaf. The previous
 * {@code (List<AudioClip>, double)} constructor was removed.</p>
 *
 * <p>Story — Nudge Clips and Selections by Grid and by Sample.</p>
 */
public final class NudgeClipsAction implements UndoableAction {

    private final List<Map.Entry<Track, AudioClip>> entries;
    private final double requestedBeatDelta;

    // Captured at execute() so undo is exact.
    private double appliedBeatDelta;

    /**
     * Creates a new nudge action.
     *
     * @param entries            the (track, clip) pairs to nudge; must
     *                           not be empty
     * @param requestedBeatDelta the requested beat delta to apply to
     *                           every clip; positive moves later, negative
     *                           moves earlier
     * @throws NullPointerException     if {@code entries} is {@code null}
     * @throws IllegalArgumentException if {@code entries} is empty or
     *                                  {@code requestedBeatDelta} is not
     *                                  a finite number
     */
    public NudgeClipsAction(List<Map.Entry<Track, AudioClip>> entries,
                            double requestedBeatDelta) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        if (!Double.isFinite(requestedBeatDelta)) {
            throw new IllegalArgumentException(
                    "requestedBeatDelta must be a finite number: " + requestedBeatDelta);
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.requestedBeatDelta = requestedBeatDelta;
    }

    /** The (track, clip) entries this action nudges (unmodifiable view). */
    public List<Map.Entry<Track, AudioClip>> getEntries() {
        return entries;
    }

    /**
     * The clips this action nudges, in entry order. Convenience accessor
     * for callers that don't need the owning track.
     */
    public List<AudioClip> getClips() {
        List<AudioClip> clips = new ArrayList<>(entries.size());
        for (Map.Entry<Track, AudioClip> e : entries) {
            clips.add(e.getValue());
        }
        return Collections.unmodifiableList(clips);
    }

    /** The beat delta that was requested at construction. */
    public double getRequestedBeatDelta() {
        return requestedBeatDelta;
    }

    /**
     * The beat delta actually applied after boundary clamping. Valid
     * only after {@link #execute()} has been called.
     */
    public double getAppliedBeatDelta() {
        return appliedBeatDelta;
    }

    @Override
    public String description() {
        return "Nudge Clips";
    }

    @Override
    public void execute() {
        // Atomic lock check: refuse the entire group if any clip is locked.
        List<AudioClip> clips = getClips();
        LockedClipException.requireUnlocked("Nudge", clips);
        double minimumStartBeat = Double.POSITIVE_INFINITY;
        for (AudioClip clip : clips) {
            minimumStartBeat = Math.min(minimumStartBeat, clip.getStartBeat());
        }
        // Boundary check: refuse to move any clip below beat 0.
        appliedBeatDelta = Math.max(requestedBeatDelta, -minimumStartBeat);
        if (appliedBeatDelta == 0.0) {
            return;
        }
        for (Map.Entry<Track, AudioClip> entry : entries) {
            Track track = entry.getKey();
            AudioClip clip = entry.getValue();
            clip.setStartBeat(clip.getStartBeat() + appliedBeatDelta);
            EventBusPublisher.publish(new ClipEvent.Moved(
                    UUID.fromString(track.getId()),
                    UUID.fromString(clip.getId()),
                    Instant.now()));
        }
    }

    @Override
    public void undo() {
        if (appliedBeatDelta == 0.0) {
            return;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<Track, AudioClip> entry = entries.get(i);
            Track track = entry.getKey();
            AudioClip clip = entry.getValue();
            clip.setStartBeat(clip.getStartBeat() - appliedBeatDelta);
            EventBusPublisher.publish(new ClipEvent.Moved(
                    UUID.fromString(track.getId()),
                    UUID.fromString(clip.getId()),
                    Instant.now()));
        }
    }
}
