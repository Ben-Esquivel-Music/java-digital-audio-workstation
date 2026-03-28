package com.benesquivelmusic.daw.core.marker;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that edits the name and position of a marker.
 *
 * <p>Executing this action applies the new name and position. Undoing it
 * restores the previous name and position.</p>
 */
public final class EditMarkerAction implements UndoableAction {

    private final MarkerManager markerManager;
    private final Marker marker;
    private final String oldName;
    private final double oldPositionInBeats;
    private final String newName;
    private final double newPositionInBeats;

    /**
     * Creates a new edit-marker action.
     *
     * @param markerManager      the marker manager containing the marker
     * @param marker             the marker to edit
     * @param newName            the new name for the marker
     * @param newPositionInBeats the new position in beats
     */
    public EditMarkerAction(MarkerManager markerManager, Marker marker,
                            String newName, double newPositionInBeats) {
        this.markerManager = Objects.requireNonNull(markerManager, "markerManager must not be null");
        this.marker = Objects.requireNonNull(marker, "marker must not be null");
        this.newName = Objects.requireNonNull(newName, "newName must not be null");
        this.newPositionInBeats = newPositionInBeats;
        this.oldName = marker.getName();
        this.oldPositionInBeats = marker.getPositionInBeats();
    }

    @Override
    public String description() {
        return "Edit Marker";
    }

    @Override
    public void execute() {
        marker.setName(newName);
        marker.setPositionInBeats(newPositionInBeats);
        markerManager.resort();
    }

    @Override
    public void undo() {
        marker.setName(oldName);
        marker.setPositionInBeats(oldPositionInBeats);
        markerManager.resort();
    }
}
