package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that moves an {@link AudioClip} to a new start beat.
 *
 * <p>Executing this action repositions the clip on the timeline. Undoing it
 * restores the original start beat.</p>
 */
public final class MoveClipAction implements UndoableAction {

    private final AudioClip clip;
    private final double newStartBeat;
    private double previousStartBeat;

    /**
     * Creates a new move-clip action.
     *
     * @param clip         the clip to move
     * @param newStartBeat the new start beat position
     */
    public MoveClipAction(AudioClip clip, double newStartBeat) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newStartBeat = newStartBeat;
    }

    @Override
    public String description() {
        return "Move Clip";
    }

    @Override
    public void execute() {
        previousStartBeat = clip.getStartBeat();
        clip.setStartBeat(newStartBeat);
    }

    @Override
    public void undo() {
        clip.setStartBeat(previousStartBeat);
    }
}
