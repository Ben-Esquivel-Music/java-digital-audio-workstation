package com.benesquivelmusic.daw.app.ui;

/**
 * Model for the session minimap/overview bar.
 *
 * <p>The minimap shows a compressed view of the entire session, with a
 * viewport rectangle indicating the currently visible portion of the
 * arrangement. It supports click-to-navigate (clicking anywhere on the
 * minimap sets the viewport center) and drag-to-navigate (dragging the
 * viewport rectangle scrolls the arrangement).</p>
 *
 * <p>All positions are expressed as normalized fractions in {@code [0, 1]},
 * making the model independent of the minimap's pixel dimensions.</p>
 */
public final class MinimapModel {

    private double totalDurationBeats;
    private int trackCount;

    private double viewportStartFraction;
    private double viewportEndFraction;

    /**
     * Creates a minimap model with the given session dimensions.
     *
     * @param totalDurationBeats the total session length in beats (must be &gt; 0)
     * @param trackCount         the number of tracks in the session (must be &ge; 0)
     */
    public MinimapModel(double totalDurationBeats, int trackCount) {
        if (totalDurationBeats <= 0) {
            throw new IllegalArgumentException("totalDurationBeats must be positive: " + totalDurationBeats);
        }
        if (trackCount < 0) {
            throw new IllegalArgumentException("trackCount must be non-negative: " + trackCount);
        }
        this.totalDurationBeats = totalDurationBeats;
        this.trackCount = trackCount;
        this.viewportStartFraction = 0.0;
        this.viewportEndFraction = 1.0;
    }

    /**
     * Returns the total session duration in beats.
     *
     * @return total duration in beats
     */
    public double getTotalDurationBeats() {
        return totalDurationBeats;
    }

    /**
     * Updates the total session duration in beats.
     *
     * @param totalDurationBeats the new total duration (must be &gt; 0)
     */
    public void setTotalDurationBeats(double totalDurationBeats) {
        if (totalDurationBeats <= 0) {
            throw new IllegalArgumentException("totalDurationBeats must be positive: " + totalDurationBeats);
        }
        this.totalDurationBeats = totalDurationBeats;
    }

    /**
     * Returns the number of tracks in the session.
     *
     * @return track count
     */
    public int getTrackCount() {
        return trackCount;
    }

    /**
     * Updates the number of tracks.
     *
     * @param trackCount the new track count (must be &ge; 0)
     */
    public void setTrackCount(int trackCount) {
        if (trackCount < 0) {
            throw new IllegalArgumentException("trackCount must be non-negative: " + trackCount);
        }
        this.trackCount = trackCount;
    }

    /**
     * Returns the viewport start position as a fraction of the total
     * session duration in {@code [0, 1]}.
     *
     * @return viewport start fraction
     */
    public double getViewportStartFraction() {
        return viewportStartFraction;
    }

    /**
     * Returns the viewport end position as a fraction of the total
     * session duration in {@code [0, 1]}.
     *
     * @return viewport end fraction
     */
    public double getViewportEndFraction() {
        return viewportEndFraction;
    }

    /**
     * Returns the viewport width as a fraction of the total session duration.
     *
     * @return viewport width fraction in {@code (0, 1]}
     */
    public double getViewportWidthFraction() {
        return viewportEndFraction - viewportStartFraction;
    }

    /**
     * Updates the viewport rectangle from the current arrangement view state.
     *
     * @param scrollOffsetBeats   the horizontal scroll offset in beats
     * @param visibleWidthBeats   the visible width of the arrangement in beats
     */
    public void updateViewport(double scrollOffsetBeats, double visibleWidthBeats) {
        double start = scrollOffsetBeats / totalDurationBeats;
        double end = (scrollOffsetBeats + visibleWidthBeats) / totalDurationBeats;
        this.viewportStartFraction = clampFraction(start);
        this.viewportEndFraction = clampFraction(Math.max(end, this.viewportStartFraction));
    }

    /**
     * Converts a normalized click position on the minimap (in {@code [0, 1]})
     * to the corresponding scroll offset in beats. The viewport is centered
     * on the clicked position.
     *
     * @param clickFraction the normalized click position in {@code [0, 1]}
     * @return the scroll offset in beats that centers the viewport at the clicked position
     */
    public double clickToScrollOffset(double clickFraction) {
        double fraction = clampFraction(clickFraction);
        double viewportWidth = getViewportWidthFraction();
        double centeredStart = fraction - viewportWidth / 2.0;
        double maxStart = 1.0 - viewportWidth;
        double clampedStart = Math.max(0.0, Math.min(maxStart, centeredStart));
        return clampedStart * totalDurationBeats;
    }

    /**
     * Converts a drag delta on the minimap (as a fraction of the minimap width)
     * to the corresponding scroll delta in beats.
     *
     * @param deltaFraction the drag delta as a fraction of minimap width
     * @return the scroll delta in beats
     */
    public double dragToScrollDelta(double deltaFraction) {
        return deltaFraction * totalDurationBeats;
    }

    private static double clampFraction(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
