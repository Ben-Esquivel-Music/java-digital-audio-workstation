package com.benesquivelmusic.daw.app.ui;

/**
 * Tracks the current time selection range in the arrangement view.
 *
 * <p>A selection is defined by a start beat and an end beat. When no
 * selection is active, {@link #hasSelection()} returns {@code false}.</p>
 */
public final class SelectionModel {

    private boolean active;
    private double startBeat;
    private double endBeat;

    /**
     * Creates a selection model with no active selection.
     */
    public SelectionModel() {
        this.active = false;
        this.startBeat = 0.0;
        this.endBeat = 0.0;
    }

    /**
     * Returns {@code true} if a time selection is currently active.
     *
     * @return whether a selection exists
     */
    public boolean hasSelection() {
        return active;
    }

    /**
     * Returns the start beat of the current selection.
     *
     * @return the selection start beat
     */
    public double getStartBeat() {
        return startBeat;
    }

    /**
     * Returns the end beat of the current selection.
     *
     * @return the selection end beat
     */
    public double getEndBeat() {
        return endBeat;
    }

    /**
     * Sets the selection range. The start must be less than the end.
     *
     * @param startBeat the start of the selection in beats
     * @param endBeat   the end of the selection in beats
     * @throws IllegalArgumentException if startBeat &ge; endBeat
     */
    public void setSelection(double startBeat, double endBeat) {
        if (startBeat >= endBeat) {
            throw new IllegalArgumentException(
                    "startBeat must be less than endBeat: " + startBeat + " >= " + endBeat);
        }
        this.startBeat = startBeat;
        this.endBeat = endBeat;
        this.active = true;
    }

    /**
     * Clears the current selection.
     */
    public void clearSelection() {
        this.active = false;
        this.startBeat = 0.0;
        this.endBeat = 0.0;
    }
}
