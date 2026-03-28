package com.benesquivelmusic.daw.core.marker;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds a marker to the marker manager.
 *
 * <p>Executing this action adds the marker. Undoing it removes the marker.</p>
 */
public final class AddMarkerAction implements UndoableAction {

    private final MarkerManager markerManager;
    private final Marker marker;

    /**
     * Creates a new add-marker action.
     *
     * @param markerManager the marker manager to add the marker to
     * @param marker        the marker to add
     */
    public AddMarkerAction(MarkerManager markerManager, Marker marker) {
        this.markerManager = Objects.requireNonNull(markerManager, "markerManager must not be null");
        this.marker = Objects.requireNonNull(marker, "marker must not be null");
    }

    @Override
    public String description() {
        return "Add Marker";
    }

    @Override
    public void execute() {
        markerManager.addMarker(marker);
    }

    @Override
    public void undo() {
        markerManager.removeMarker(marker);
    }
}
