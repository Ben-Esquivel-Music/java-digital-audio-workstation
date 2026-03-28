package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes a track from its parent folder,
 * making it a top-level track.
 *
 * <p>Executing this action removes the track from its folder.
 * Undoing it restores the track to the folder.</p>
 */
public final class RemoveFromFolderAction implements UndoableAction {

    private final Track track;
    private final Track folder;

    /**
     * Creates a new remove-from-folder action.
     *
     * @param track  the track to remove from its folder
     * @param folder the folder the track is currently in
     */
    public RemoveFromFolderAction(Track track, Track folder) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.folder = Objects.requireNonNull(folder, "folder must not be null");
    }

    @Override
    public String description() {
        return "Remove from Folder";
    }

    @Override
    public void execute() {
        folder.removeChildTrack(track);
    }

    @Override
    public void undo() {
        folder.addChildTrack(track);
    }
}
