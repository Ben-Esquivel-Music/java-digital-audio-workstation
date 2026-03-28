package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that renames a track.
 *
 * <p>Executing this action sets the track's name to the new value.
 * Undoing it restores the previous name.</p>
 */
public final class RenameTrackAction implements UndoableAction {

    private final Track track;
    private final String newName;
    private String previousName;

    /**
     * Creates a new rename-track action.
     *
     * @param track   the track to rename
     * @param newName the new name for the track
     */
    public RenameTrackAction(Track track, String newName) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.newName = Objects.requireNonNull(newName, "newName must not be null");
    }

    @Override
    public String description() {
        return "Rename Track";
    }

    @Override
    public void execute() {
        previousName = track.getName();
        track.setName(newName);
    }

    @Override
    public void undo() {
        track.setName(previousName);
    }
}
