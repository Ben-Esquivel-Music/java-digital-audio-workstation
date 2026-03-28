package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes a track from the project.
 *
 * <p>Executing this action removes the track from the project's track list
 * and its mixer channel from the mixer. Undoing it re-adds the track at
 * its original position.</p>
 */
public final class RemoveTrackAction implements UndoableAction {

    private final DawProject project;
    private final Track track;
    private int originalIndex;

    /**
     * Creates a new remove-track action.
     *
     * @param project the project to remove the track from
     * @param track   the track to remove
     */
    public RemoveTrackAction(DawProject project, Track track) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.track = Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public String description() {
        return "Remove Track";
    }

    @Override
    public void execute() {
        originalIndex = project.getTracks().indexOf(track);
        project.removeTrack(track);
    }

    @Override
    public void undo() {
        project.addTrack(track);
        // Move the re-added track (which is appended) back to its original position
        int currentIndex = project.getTracks().size() - 1;
        if (currentIndex != originalIndex && originalIndex >= 0
                && originalIndex < project.getTracks().size()) {
            project.moveTrack(currentIndex, originalIndex);
        }
    }
}
