package com.benesquivelmusic.daw.core.marker;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes a marker from the marker manager.
 *
 * <p>Executing this action removes the marker. Undoing it adds the marker back.</p>
 */
public final class RemoveMarkerAction implements UndoableAction {

    private final MarkerManager markerManager;
    private final Marker marker;

    /**
     * Creates a new remove-marker action.
     *
     * @param markerManager the marker manager to remove the marker from
     * @param marker        the marker to remove
     */
    public RemoveMarkerAction(MarkerManager markerManager, Marker marker) {
        this.markerManager = Objects.requireNonNull(markerManager, "markerManager must not be null");
        this.marker = Objects.requireNonNull(marker, "marker must not be null");
    }

    @Override
    public String description() {
        return "Remove Marker";
    }

    @Override
    public void execute() {
        markerManager.removeMarker(marker);
    }

    @Override
    public void undo() {
        markerManager.addMarker(marker);
    }
}
