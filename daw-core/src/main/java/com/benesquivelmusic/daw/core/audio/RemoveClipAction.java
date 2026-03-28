package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes an {@link AudioClip} from a {@link Track}.
 *
 * <p>Executing this action removes the clip from the track. Undoing it
 * re-adds the clip.</p>
 */
public final class RemoveClipAction implements UndoableAction {

    private final Track track;
    private final AudioClip clip;

    /**
     * Creates a new remove-clip action.
     *
     * @param track the track containing the clip
     * @param clip  the audio clip to remove
     */
    public RemoveClipAction(Track track, AudioClip clip) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
    }

    @Override
    public String description() {
        return "Remove Clip";
    }

    @Override
    public void execute() {
        track.removeClip(clip);
    }

    @Override
    public void undo() {
        track.addClip(clip);
    }
}
