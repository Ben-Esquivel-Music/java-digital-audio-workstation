package com.benesquivelmusic.daw.app.ui;

/**
 * Manages the zoom level for an arrangement or editor view.
 *
 * <p>The zoom level is represented as a normalized value between
 * {@link #MIN_ZOOM} and {@link #MAX_ZOOM}, where {@code 1.0} is the default
 * (100%) zoom. Values less than 1.0 zoom out (overview), and values greater
 * than 1.0 zoom in (detail). Each zoom step multiplies or divides by the
 * configured {@link #ZOOM_FACTOR}.</p>
 *
 * <p>The zoom level is clamped to the valid range on every mutation.</p>
 */
public final class ZoomLevel {

    /** Minimum zoom level — full project overview. */
    public static final double MIN_ZOOM = 0.01;

    /** Maximum zoom level — per-sample detail. */
    public static final double MAX_ZOOM = 100.0;

    /** Default zoom level — 100%. */
    public static final double DEFAULT_ZOOM = 1.0;

    /** Multiplicative factor applied on each zoom in/out step. */
    public static final double ZOOM_FACTOR = 1.25;

    private double level;

    /**
     * Creates a zoom level at the default (100%) value.
     */
    public ZoomLevel() {
        this.level = DEFAULT_ZOOM;
    }

    /**
     * Creates a zoom level at the given initial value, clamped to
     * [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}].
     *
     * @param initialLevel the initial zoom level
     */
    public ZoomLevel(double initialLevel) {
        this.level = clamp(initialLevel);
    }

    /**
     * Returns the current zoom level.
     *
     * @return the zoom level in [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}]
     */
    public double getLevel() {
        return level;
    }

    /**
     * Sets the zoom level, clamping to the valid range.
     *
     * @param newLevel the desired zoom level
     */
    public void setLevel(double newLevel) {
        this.level = clamp(newLevel);
    }

    /**
     * Zooms in by one step (multiplies by {@link #ZOOM_FACTOR}).
     */
    public void zoomIn() {
        setLevel(level * ZOOM_FACTOR);
    }

    /**
     * Zooms out by one step (divides by {@link #ZOOM_FACTOR}).
     */
    public void zoomOut() {
        setLevel(level / ZOOM_FACTOR);
    }

    /**
     * Resets the zoom to the default level ({@link #DEFAULT_ZOOM}).
     * This is used by the "Zoom to Fit" action to show all content.
     */
    public void zoomToFit() {
        this.level = DEFAULT_ZOOM;
    }

    /**
     * Returns {@code true} if the zoom level can be increased further.
     *
     * @return whether zoom in is possible
     */
    public boolean canZoomIn() {
        return level < MAX_ZOOM;
    }

    /**
     * Returns {@code true} if the zoom level can be decreased further.
     *
     * @return whether zoom out is possible
     */
    public boolean canZoomOut() {
        return level > MIN_ZOOM;
    }

    /**
     * Returns the zoom level as a percentage string (e.g. "125%").
     *
     * @return formatted zoom percentage
     */
    public String toPercentageString() {
        return String.format("%.0f%%", level * 100);
    }

    private static double clamp(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }
}
