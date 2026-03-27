package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that moves a track from one position to another
 * in the arrangement view.
 *
 * <p>Executing this action moves the track at {@code fromIndex} to
 * {@code toIndex}. Undoing it reverses the move.</p>
 */
public final class MoveTrackAction implements UndoableAction {

    private final DawProject project;
    private final int fromIndex;
    private final int toIndex;

    /**
     * Creates a new move-track action.
     *
     * @param project   the project containing the tracks
     * @param fromIndex the current index of the track to move
     * @param toIndex   the target index for the track
     */
    public MoveTrackAction(DawProject project, int fromIndex, int toIndex) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
    }

    @Override
    public String description() {
        return "Move Track";
    }

    @Override
    public void execute() {
        project.moveTrack(fromIndex, toIndex);
    }

    @Override
    public void undo() {
        project.moveTrack(toIndex, fromIndex);
    }
}
