package com.benesquivelmusic.daw.core.reference;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes a reference track from the project.
 *
 * <p>Executing this action removes the reference track. Undoing it re-adds
 * the reference track to the project.</p>
 */
public final class RemoveReferenceTrackAction implements UndoableAction {

    private final DawProject project;
    private final ReferenceTrack referenceTrack;

    /**
     * Creates a new remove-reference-track action.
     *
     * @param project        the project containing the reference track
     * @param referenceTrack the reference track to remove
     */
    public RemoveReferenceTrackAction(DawProject project, ReferenceTrack referenceTrack) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.referenceTrack = Objects.requireNonNull(referenceTrack, "referenceTrack must not be null");
    }

    @Override
    public String description() {
        return "Remove Reference Track";
    }

    @Override
    public void execute() {
        project.removeReferenceTrack(referenceTrack);
    }

    @Override
    public void undo() {
        project.addReferenceTrack(referenceTrack);
    }
}
