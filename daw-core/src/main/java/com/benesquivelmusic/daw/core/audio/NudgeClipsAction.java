package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
 * <p>Story — Nudge Clips and Selections by Grid and by Sample.</p>
 */
public final class NudgeClipsAction implements UndoableAction {

    private final List<AudioClip> clips;
    private final double requestedBeatDelta;

    // Captured at execute() so undo is exact.
    private double appliedBeatDelta;

    /**
     * Creates a new nudge action.
     *
     * @param clips              the clips to nudge; must not be empty
     * @param requestedBeatDelta the requested beat delta to apply to
     *                           every clip; positive moves later, negative
     *                           moves earlier
     * @throws NullPointerException     if {@code clips} is {@code null}
     * @throws IllegalArgumentException if {@code clips} is empty or
     *                                  {@code requestedBeatDelta} is not
     *                                  a finite number
     */
    public NudgeClipsAction(List<AudioClip> clips, double requestedBeatDelta) {
        Objects.requireNonNull(clips, "clips must not be null");
        if (clips.isEmpty()) {
            throw new IllegalArgumentException("clips must not be empty");
        }
        if (!Double.isFinite(requestedBeatDelta)) {
            throw new IllegalArgumentException(
                    "requestedBeatDelta must be a finite number: " + requestedBeatDelta);
        }
        this.clips = Collections.unmodifiableList(new ArrayList<>(clips));
        this.requestedBeatDelta = requestedBeatDelta;
    }

    /** The clips this action nudges (unmodifiable view). */
    public List<AudioClip> getClips() {
        return clips;
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
        double minimumStartBeat = Double.POSITIVE_INFINITY;
        for (AudioClip clip : clips) {
            minimumStartBeat = Math.min(minimumStartBeat, clip.getStartBeat());
        }
        // Boundary check: refuse to move any clip below beat 0.
        appliedBeatDelta = Math.max(requestedBeatDelta, -minimumStartBeat);
        if (appliedBeatDelta == 0.0) {
            return;
        }
        for (AudioClip clip : clips) {
            clip.setStartBeat(clip.getStartBeat() + appliedBeatDelta);
        }
    }

    @Override
    public void undo() {
        if (appliedBeatDelta == 0.0) {
            return;
        }
        for (AudioClip clip : clips) {
            clip.setStartBeat(clip.getStartBeat() - appliedBeatDelta);
        }
    }
}
