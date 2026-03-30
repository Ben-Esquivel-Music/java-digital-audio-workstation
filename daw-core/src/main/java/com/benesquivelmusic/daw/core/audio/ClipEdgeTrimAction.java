package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adjusts an {@link AudioClip}'s start, duration,
 * and source offset to achieve an interactive edge-trim result.
 *
 * <p>Unlike {@link TrimClipAction}, this action directly sets the clip's
 * properties without the bounds validation of {@link AudioClip#trimTo}.
 * This allows both extending and shrinking the clip boundaries, which is
 * needed for interactive edge dragging where a previously trimmed clip
 * may be extended back toward its original source boundary.</p>
 */
public final class ClipEdgeTrimAction implements UndoableAction {

    private final AudioClip clip;
    private final double newStartBeat;
    private final double newDurationBeats;
    private final double newSourceOffsetBeats;

    // Snapshot for undo
    private double originalStartBeat;
    private double originalDuration;
    private double originalSourceOffset;

    /**
     * Creates a new clip edge trim action.
     *
     * @param clip                the clip to trim
     * @param newStartBeat        the new start beat (must be &ge; 0)
     * @param newDurationBeats    the new duration in beats (must be &gt; 0)
     * @param newSourceOffsetBeats the new source offset in beats (must be &ge; 0)
     */
    public ClipEdgeTrimAction(AudioClip clip, double newStartBeat,
                               double newDurationBeats, double newSourceOffsetBeats) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        if (newStartBeat < 0) {
            throw new IllegalArgumentException("newStartBeat must not be negative: " + newStartBeat);
        }
        if (newDurationBeats <= 0) {
            throw new IllegalArgumentException("newDurationBeats must be positive: " + newDurationBeats);
        }
        if (newSourceOffsetBeats < 0) {
            throw new IllegalArgumentException("newSourceOffsetBeats must not be negative: " + newSourceOffsetBeats);
        }
        this.newStartBeat = newStartBeat;
        this.newDurationBeats = newDurationBeats;
        this.newSourceOffsetBeats = newSourceOffsetBeats;
    }

    @Override
    public String description() {
        return "Trim Clip Edge";
    }

    @Override
    public void execute() {
        originalStartBeat = clip.getStartBeat();
        originalDuration = clip.getDurationBeats();
        originalSourceOffset = clip.getSourceOffsetBeats();
        clip.setSourceOffsetBeats(newSourceOffsetBeats);
        clip.setStartBeat(newStartBeat);
        clip.setDurationBeats(newDurationBeats);
    }

    @Override
    public void undo() {
        clip.setSourceOffsetBeats(originalSourceOffset);
        clip.setStartBeat(originalStartBeat);
        clip.setDurationBeats(originalDuration);
    }
}
