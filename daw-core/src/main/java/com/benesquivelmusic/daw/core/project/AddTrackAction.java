package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds an existing track to the project.
 *
 * <p>Executing this action adds the track and its corresponding mixer channel.
 * Undoing it removes the track from the project.</p>
 */
public final class AddTrackAction implements UndoableAction {

    private final DawProject project;
    private final Track track;

    /**
     * Creates a new add-track action.
     *
     * @param project the project to add the track to
     * @param track   the track to add
     */
    public AddTrackAction(DawProject project, Track track) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.track = Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public String description() {
        return "Add Track";
    }

    @Override
    public void execute() {
        project.addTrack(track);
    }

    @Override
    public void undo() {
        project.removeTrack(track);
    }
}
