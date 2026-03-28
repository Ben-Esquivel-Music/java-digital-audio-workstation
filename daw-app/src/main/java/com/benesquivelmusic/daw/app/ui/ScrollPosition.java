package com.benesquivelmusic.daw.app.ui;

/**
 * Manages horizontal and vertical scroll offsets for the arrangement view.
 *
 * <p>The horizontal offset is measured in beats (matching the timeline model)
 * and the vertical offset is measured in pixels (for scrolling through the
 * track list). Both axes are clamped to {@code [0, max]} on every mutation.</p>
 */
public final class ScrollPosition {

    private double horizontalOffsetBeats;
    private double verticalOffsetPixels;
    private double maxHorizontalBeats;
    private double maxVerticalPixels;

    /**
     * Creates a scroll position at the origin with no bounds.
     */
    public ScrollPosition() {
        this.horizontalOffsetBeats = 0.0;
        this.verticalOffsetPixels = 0.0;
        this.maxHorizontalBeats = Double.MAX_VALUE;
        this.maxVerticalPixels = Double.MAX_VALUE;
    }

    /**
     * Returns the horizontal scroll offset in beats.
     *
     * @return horizontal offset in beats, always &ge; 0
     */
    public double getHorizontalOffsetBeats() {
        return horizontalOffsetBeats;
    }

    /**
     * Sets the horizontal scroll offset in beats, clamped to
     * {@code [0, maxHorizontalBeats]}.
     *
     * @param beats the desired horizontal offset
     */
    public void setHorizontalOffsetBeats(double beats) {
        this.horizontalOffsetBeats = clamp(beats, maxHorizontalBeats);
    }

    /**
     * Returns the vertical scroll offset in pixels.
     *
     * @return vertical offset in pixels, always &ge; 0
     */
    public double getVerticalOffsetPixels() {
        return verticalOffsetPixels;
    }

    /**
     * Sets the vertical scroll offset in pixels, clamped to
     * {@code [0, maxVerticalPixels]}.
     *
     * @param pixels the desired vertical offset
     */
    public void setVerticalOffsetPixels(double pixels) {
        this.verticalOffsetPixels = clamp(pixels, maxVerticalPixels);
    }

    /**
     * Returns the maximum horizontal scroll offset in beats.
     *
     * @return maximum horizontal offset
     */
    public double getMaxHorizontalBeats() {
        return maxHorizontalBeats;
    }

    /**
     * Sets the maximum horizontal scroll offset in beats.
     * The current offset is re-clamped if it exceeds the new maximum.
     *
     * @param maxBeats the maximum horizontal offset (must be &ge; 0)
     */
    public void setMaxHorizontalBeats(double maxBeats) {
        this.maxHorizontalBeats = Math.max(0.0, maxBeats);
        this.horizontalOffsetBeats = clamp(horizontalOffsetBeats, this.maxHorizontalBeats);
    }

    /**
     * Returns the maximum vertical scroll offset in pixels.
     *
     * @return maximum vertical offset
     */
    public double getMaxVerticalPixels() {
        return maxVerticalPixels;
    }

    /**
     * Sets the maximum vertical scroll offset in pixels.
     * The current offset is re-clamped if it exceeds the new maximum.
     *
     * @param maxPixels the maximum vertical offset (must be &ge; 0)
     */
    public void setMaxVerticalPixels(double maxPixels) {
        this.maxVerticalPixels = Math.max(0.0, maxPixels);
        this.verticalOffsetPixels = clamp(verticalOffsetPixels, this.maxVerticalPixels);
    }

    /**
     * Scrolls horizontally by the given delta in beats.
     *
     * @param deltaBeats the number of beats to scroll (positive = right, negative = left)
     */
    public void scrollHorizontal(double deltaBeats) {
        setHorizontalOffsetBeats(horizontalOffsetBeats + deltaBeats);
    }

    /**
     * Scrolls vertically by the given delta in pixels.
     *
     * @param deltaPixels the number of pixels to scroll (positive = down, negative = up)
     */
    public void scrollVertical(double deltaPixels) {
        setVerticalOffsetPixels(verticalOffsetPixels + deltaPixels);
    }

    /**
     * Resets both scroll offsets to zero.
     */
    public void reset() {
        this.horizontalOffsetBeats = 0.0;
        this.verticalOffsetPixels = 0.0;
    }

    private static double clamp(double value, double max) {
        return Math.max(0.0, Math.min(max, value));
    }
}
