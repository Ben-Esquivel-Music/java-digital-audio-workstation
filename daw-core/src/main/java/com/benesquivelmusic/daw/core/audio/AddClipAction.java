package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds an {@link AudioClip} to a {@link Track}.
 *
 * <p>Executing this action adds the clip to the track. Undoing it removes
 * the clip.</p>
 */
public final class AddClipAction implements UndoableAction {

    private final Track track;
    private final AudioClip clip;

    /**
     * Creates a new add-clip action.
     *
     * @param track the track to add the clip to
     * @param clip  the audio clip to add
     */
    public AddClipAction(Track track, AudioClip clip) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
    }

    @Override
    public String description() {
        return "Add Clip";
    }

    @Override
    public void execute() {
        track.addClip(clip);
    }

    @Override
    public void undo() {
        track.removeClip(clip);
    }
}
