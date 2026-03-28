package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that moves a track into or out of a folder track.
 *
 * <p>Executing this action moves the track into the specified folder.
 * Undoing it restores the track to its previous parent (or top-level
 * if it had no parent).</p>
 */
public final class MoveToFolderAction implements UndoableAction {

    private final DawProject project;
    private final Track track;
    private final Track folder;
    private final Track previousParent;

    /**
     * Creates a new move-to-folder action.
     *
     * @param project the project containing the tracks
     * @param track   the track to move into the folder
     * @param folder  the destination folder track
     */
    public MoveToFolderAction(DawProject project, Track track, Track folder) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.folder = Objects.requireNonNull(folder, "folder must not be null");
        this.previousParent = track.getParentTrack();
    }

    @Override
    public String description() {
        return "Move to Folder";
    }

    @Override
    public void execute() {
        project.moveTrackToFolder(track, folder);
    }

    @Override
    public void undo() {
        folder.removeChildTrack(track);
        if (previousParent != null) {
            previousParent.addChildTrack(track);
        }
    }
}
