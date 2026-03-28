package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes a track group from the project.
 *
 * <p>Executing this action removes the group. Undoing it re-adds the
 * same group instance to the project.</p>
 */
public final class RemoveTrackGroupAction implements UndoableAction {

    private final DawProject project;
    private final TrackGroup group;

    /**
     * Creates a new remove-track-group action.
     *
     * @param project the project containing the group
     * @param group   the track group to remove
     */
    public RemoveTrackGroupAction(DawProject project, TrackGroup group) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.group = Objects.requireNonNull(group, "group must not be null");
    }

    @Override
    public String description() {
        return "Remove Track Group";
    }

    @Override
    public void execute() {
        project.removeTrackGroup(group);
    }

    @Override
    public void undo() {
        project.addTrackGroup(group);
    }
}
