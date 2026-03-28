package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;

/**
 * Immutable snapshot of the arrangement viewport state, suitable for
 * per-project persistence.
 *
 * <p>Captures the horizontal zoom level, vertical track height, and both
 * scroll offsets so they can be saved with the project and restored when
 * the project is reopened.</p>
 */
public final class ViewportState {

    private final double horizontalZoom;
    private final double trackHeight;
    private final double scrollXBeats;
    private final double scrollYPixels;

    /**
     * Creates a viewport state snapshot.
     *
     * @param horizontalZoom the horizontal zoom level (from {@link ZoomLevel#getLevel()})
     * @param trackHeight    the vertical track height in pixels (from {@link TrackHeightZoom#getTrackHeight()})
     * @param scrollXBeats   the horizontal scroll offset in beats
     * @param scrollYPixels  the vertical scroll offset in pixels
     */
    public ViewportState(double horizontalZoom, double trackHeight,
                         double scrollXBeats, double scrollYPixels) {
        this.horizontalZoom = horizontalZoom;
        this.trackHeight = trackHeight;
        this.scrollXBeats = scrollXBeats;
        this.scrollYPixels = scrollYPixels;
    }

    /**
     * Returns the default viewport state (default zoom, default track height,
     * scroll at origin).
     *
     * @return the default viewport state
     */
    public static ViewportState defaultState() {
        return new ViewportState(
                ZoomLevel.DEFAULT_ZOOM,
                TrackHeightZoom.DEFAULT_TRACK_HEIGHT,
                0.0,
                0.0);
    }

    /**
     * Returns the horizontal zoom level.
     *
     * @return the horizontal zoom level
     */
    public double getHorizontalZoom() {
        return horizontalZoom;
    }

    /**
     * Returns the vertical track height in pixels.
     *
     * @return the track height
     */
    public double getTrackHeight() {
        return trackHeight;
    }

    /**
     * Returns the horizontal scroll offset in beats.
     *
     * @return the horizontal scroll offset
     */
    public double getScrollXBeats() {
        return scrollXBeats;
    }

    /**
     * Returns the vertical scroll offset in pixels.
     *
     * @return the vertical scroll offset
     */
    public double getScrollYPixels() {
        return scrollYPixels;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ViewportState other)) {
            return false;
        }
        return Double.compare(horizontalZoom, other.horizontalZoom) == 0
                && Double.compare(trackHeight, other.trackHeight) == 0
                && Double.compare(scrollXBeats, other.scrollXBeats) == 0
                && Double.compare(scrollYPixels, other.scrollYPixels) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(horizontalZoom, trackHeight, scrollXBeats, scrollYPixels);
    }

    @Override
    public String toString() {
        return String.format("ViewportState[zoom=%.2f, trackHeight=%.1f, scrollX=%.2f, scrollY=%.1f]",
                horizontalZoom, trackHeight, scrollXBeats, scrollYPixels);
    }
}
