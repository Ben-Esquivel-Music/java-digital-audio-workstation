package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.List;
import java.util.Objects;

/**
 * An undoable action that creates a track group from the given tracks.
 *
 * <p>Executing this action creates the group and adds it to the project.
 * Undoing it removes the group from the project.</p>
 */
public final class CreateTrackGroupAction implements UndoableAction {

    private final DawProject project;
    private final String groupName;
    private final List<Track> tracks;
    private TrackGroup group;

    /**
     * Creates a new create-track-group action.
     *
     * @param project   the project to add the group to
     * @param groupName the name for the new group
     * @param tracks    the tracks to include in the group
     */
    public CreateTrackGroupAction(DawProject project, String groupName, List<Track> tracks) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.groupName = Objects.requireNonNull(groupName, "groupName must not be null");
        this.tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks must not be null"));
    }

    @Override
    public String description() {
        return "Create Track Group";
    }

    @Override
    public void execute() {
        if (group == null) {
            group = project.createTrackGroup(groupName, tracks);
        } else {
            project.addTrackGroup(group);
        }
    }

    @Override
    public void undo() {
        if (group != null) {
            project.removeTrackGroup(group);
        }
    }

    /**
     * Returns the track group created by this action, or {@code null} if
     * the action has not been executed yet.
     *
     * @return the created group, or {@code null}
     */
    public TrackGroup getGroup() {
        return group;
    }
}
