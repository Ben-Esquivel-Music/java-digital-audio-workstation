package com.benesquivelmusic.daw.core.reference;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds a reference track to the project.
 *
 * <p>Executing this action adds the reference track. Undoing it removes
 * the reference track from the project.</p>
 */
public final class AddReferenceTrackAction implements UndoableAction {

    private final DawProject project;
    private final ReferenceTrack referenceTrack;

    /**
     * Creates a new add-reference-track action.
     *
     * @param project        the project to add the reference track to
     * @param referenceTrack the reference track to add
     */
    public AddReferenceTrackAction(DawProject project, ReferenceTrack referenceTrack) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.referenceTrack = Objects.requireNonNull(referenceTrack, "referenceTrack must not be null");
    }

    @Override
    public String description() {
        return "Add Reference Track";
    }

    @Override
    public void execute() {
        project.addReferenceTrack(referenceTrack);
    }

    @Override
    public void undo() {
        project.removeReferenceTrack(referenceTrack);
    }
}
