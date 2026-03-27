package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that trims an {@link AudioClip} to a new beat range.
 *
 * <p>Executing this action adjusts the clip's start, duration, and source
 * offset so that only the audio between {@code newStartBeat} and
 * {@code newEndBeat} is retained. Undoing restores the original values.</p>
 */
public final class TrimClipAction implements UndoableAction {

    private final AudioClip clip;
    private final double newStartBeat;
    private final double newEndBeat;

    // Snapshot for undo
    private double originalStartBeat;
    private double originalDuration;
    private double originalSourceOffset;

    /**
     * Creates a new trim-clip action.
     *
     * @param clip         the clip to trim
     * @param newStartBeat the new start beat (must be &ge; clip's current start)
     * @param newEndBeat   the new end beat (must be &le; clip's current end and &gt; newStartBeat)
     */
    public TrimClipAction(AudioClip clip, double newStartBeat, double newEndBeat) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newStartBeat = newStartBeat;
        this.newEndBeat = newEndBeat;
    }

    @Override
    public String description() {
        return "Trim Clip";
    }

    @Override
    public void execute() {
        originalStartBeat = clip.getStartBeat();
        originalDuration = clip.getDurationBeats();
        originalSourceOffset = clip.getSourceOffsetBeats();
        clip.trimTo(newStartBeat, newEndBeat);
    }

    @Override
    public void undo() {
        clip.setSourceOffsetBeats(originalSourceOffset);
        clip.setStartBeat(originalStartBeat);
        clip.setDurationBeats(originalDuration);
    }
}
