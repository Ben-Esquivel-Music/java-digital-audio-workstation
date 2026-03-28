package com.benesquivelmusic.daw.app.ui;

/**
 * Manages vertical zoom via the track height for the arrangement view.
 *
 * <p>Each track row is rendered at a configurable height in pixels.
 * Zooming in increases the track height (showing more detail, e.g.
 * waveform amplitude), while zooming out decreases it (fitting more
 * tracks on screen). The height is clamped to
 * [{@link #MIN_TRACK_HEIGHT}, {@link #MAX_TRACK_HEIGHT}] on every
 * mutation.</p>
 */
public final class TrackHeightZoom {

    /** Minimum track height in pixels. */
    public static final double MIN_TRACK_HEIGHT = 20.0;

    /** Maximum track height in pixels. */
    public static final double MAX_TRACK_HEIGHT = 300.0;

    /** Default track height in pixels. */
    public static final double DEFAULT_TRACK_HEIGHT = 80.0;

    /** Multiplicative factor applied on each vertical zoom step. */
    public static final double ZOOM_FACTOR = 1.15;

    private double trackHeight;

    /**
     * Creates a track height zoom at the default height.
     */
    public TrackHeightZoom() {
        this.trackHeight = DEFAULT_TRACK_HEIGHT;
    }

    /**
     * Creates a track height zoom at the given initial height, clamped to
     * [{@link #MIN_TRACK_HEIGHT}, {@link #MAX_TRACK_HEIGHT}].
     *
     * @param initialHeight the initial track height in pixels
     */
    public TrackHeightZoom(double initialHeight) {
        this.trackHeight = clamp(initialHeight);
    }

    /**
     * Returns the current track height in pixels.
     *
     * @return track height in [{@link #MIN_TRACK_HEIGHT}, {@link #MAX_TRACK_HEIGHT}]
     */
    public double getTrackHeight() {
        return trackHeight;
    }

    /**
     * Sets the track height, clamping to the valid range.
     *
     * @param height the desired track height in pixels
     */
    public void setTrackHeight(double height) {
        this.trackHeight = clamp(height);
    }

    /**
     * Zooms in (increases track height) by one step.
     */
    public void zoomIn() {
        setTrackHeight(trackHeight * ZOOM_FACTOR);
    }

    /**
     * Zooms out (decreases track height) by one step.
     */
    public void zoomOut() {
        setTrackHeight(trackHeight / ZOOM_FACTOR);
    }

    /**
     * Resets the track height to the default value.
     */
    public void resetToDefault() {
        this.trackHeight = DEFAULT_TRACK_HEIGHT;
    }

    /**
     * Returns {@code true} if the track height can be increased further.
     *
     * @return whether zoom in (taller tracks) is possible
     */
    public boolean canZoomIn() {
        return trackHeight < MAX_TRACK_HEIGHT;
    }

    /**
     * Returns {@code true} if the track height can be decreased further.
     *
     * @return whether zoom out (shorter tracks) is possible
     */
    public boolean canZoomOut() {
        return trackHeight > MIN_TRACK_HEIGHT;
    }

    /**
     * Returns the current track height as a percentage of the default height.
     *
     * @return formatted percentage string (e.g. "100%")
     */
    public String toPercentageString() {
        return String.format("%.0f%%", (trackHeight / DEFAULT_TRACK_HEIGHT) * 100);
    }

    private static double clamp(double value) {
        return Math.max(MIN_TRACK_HEIGHT, Math.min(MAX_TRACK_HEIGHT, value));
    }
}
