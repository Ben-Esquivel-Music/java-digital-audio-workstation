package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;

/**
 * Coordinates zoom, scroll, and minimap state for the arrangement view.
 *
 * <p>This navigator is the central point for handling all navigation
 * interactions in the arrangement:</p>
 * <ul>
 *   <li><b>Horizontal zoom</b> — Ctrl+scroll or pinch gesture (zoom centered
 *       at the mouse cursor position)</li>
 *   <li><b>Vertical zoom</b> — Alt+scroll (changes track height)</li>
 *   <li><b>Keyboard zoom</b> — +/- keys and Ctrl+0 (fit all)</li>
 *   <li><b>Minimap navigation</b> — click or drag on the minimap overview</li>
 * </ul>
 *
 * <p>The navigator owns the {@link ZoomLevel}, {@link TrackHeightZoom},
 * {@link ScrollPosition}, and {@link MinimapModel} instances and keeps
 * them synchronized. It can produce a {@link ViewportState} snapshot for
 * project persistence and restore from one.</p>
 */
public final class ArrangementNavigator {

    /** Default total session duration in beats (e.g. 128 beats = 32 bars of 4/4). */
    static final double DEFAULT_SESSION_BEATS = 128.0;

    /** Base pixels-per-beat at zoom 1.0, matching {@link TimelineRuler}. */
    static final double BASE_PIXELS_PER_BEAT = 40.0;

    private final ZoomLevel horizontalZoom;
    private final TrackHeightZoom verticalZoom;
    private final ScrollPosition scrollPosition;
    private final MinimapModel minimapModel;

    private double viewportWidthPixels;
    private int trackCount;

    /**
     * Creates an arrangement navigator with default state.
     */
    public ArrangementNavigator() {
        this.horizontalZoom = new ZoomLevel();
        this.verticalZoom = new TrackHeightZoom();
        this.scrollPosition = new ScrollPosition();
        this.minimapModel = new MinimapModel(DEFAULT_SESSION_BEATS, 0);
        this.viewportWidthPixels = 0.0;
        this.trackCount = 0;
    }

    /**
     * Returns the horizontal zoom model.
     *
     * @return the horizontal zoom level
     */
    public ZoomLevel getHorizontalZoom() {
        return horizontalZoom;
    }

    /**
     * Returns the vertical zoom (track height) model.
     *
     * @return the track height zoom
     */
    public TrackHeightZoom getVerticalZoom() {
        return verticalZoom;
    }

    /**
     * Returns the scroll position model.
     *
     * @return the scroll position
     */
    public ScrollPosition getScrollPosition() {
        return scrollPosition;
    }

    /**
     * Returns the minimap model.
     *
     * @return the minimap model
     */
    public MinimapModel getMinimapModel() {
        return minimapModel;
    }

    /**
     * Updates the viewport width (the visible arrangement area width in pixels).
     * This is needed to compute the visible beat range and minimap viewport.
     *
     * @param widthPixels the viewport width in pixels
     */
    public void setViewportWidthPixels(double widthPixels) {
        this.viewportWidthPixels = Math.max(0.0, widthPixels);
        syncMinimap();
    }

    /**
     * Updates the total session duration in beats. This updates scroll bounds
     * and the minimap.
     *
     * @param totalBeats the total session duration in beats (must be &gt; 0)
     */
    public void setTotalSessionBeats(double totalBeats) {
        if (totalBeats <= 0) {
            throw new IllegalArgumentException("totalBeats must be positive: " + totalBeats);
        }
        minimapModel.setTotalDurationBeats(totalBeats);
        updateScrollBounds();
        syncMinimap();
    }

    /**
     * Updates the track count. This updates the vertical scroll bounds
     * and the minimap.
     *
     * @param count the number of tracks (must be &ge; 0)
     */
    public void setTrackCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("trackCount must be non-negative: " + count);
        }
        this.trackCount = count;
        minimapModel.setTrackCount(count);
        updateVerticalScrollBounds();
    }

    // ── Horizontal zoom ────────────────────────────────────────────────────

    /**
     * Zooms horizontally, centered at the given cursor position within the
     * viewport. The content under the cursor stays fixed while surrounding
     * content expands or contracts.
     *
     * @param steps           number of zoom steps (positive = zoom in, negative = zoom out)
     * @param cursorXPixels   the cursor X position in viewport pixels
     */
    public void zoomHorizontalAt(int steps, double cursorXPixels) {
        if (steps == 0) {
            return;
        }

        double pixelsPerBeat = currentPixelsPerBeat();
        double cursorBeat = scrollPosition.getHorizontalOffsetBeats()
                + cursorXPixels / pixelsPerBeat;

        for (int i = 0; i < Math.abs(steps); i++) {
            if (steps > 0) {
                horizontalZoom.zoomIn();
            } else {
                horizontalZoom.zoomOut();
            }
        }

        double newPixelsPerBeat = currentPixelsPerBeat();
        double newScrollOffset = cursorBeat - cursorXPixels / newPixelsPerBeat;
        scrollPosition.setMaxHorizontalBeats(computeMaxHorizontalScroll());
        scrollPosition.setHorizontalOffsetBeats(newScrollOffset);
        syncMinimap();
    }

    /**
     * Zooms horizontally without a cursor position (e.g. keyboard shortcuts).
     * The zoom is centered on the current viewport center.
     *
     * @param steps number of zoom steps (positive = zoom in, negative = zoom out)
     */
    public void zoomHorizontal(int steps) {
        zoomHorizontalAt(steps, viewportWidthPixels / 2.0);
    }

    // ── Vertical zoom ──────────────────────────────────────────────────────

    /**
     * Zooms vertically (changes track height) by the given number of steps.
     *
     * @param steps number of zoom steps (positive = zoom in / taller, negative = zoom out / shorter)
     */
    public void zoomVertical(int steps) {
        for (int i = 0; i < Math.abs(steps); i++) {
            if (steps > 0) {
                verticalZoom.zoomIn();
            } else {
                verticalZoom.zoomOut();
            }
        }
        updateVerticalScrollBounds();
    }

    // ── Keyboard zoom actions ──────────────────────────────────────────────

    /**
     * Handles a "zoom in" keyboard shortcut (+ key).
     */
    public void keyboardZoomIn() {
        zoomHorizontal(1);
    }

    /**
     * Handles a "zoom out" keyboard shortcut (- key).
     */
    public void keyboardZoomOut() {
        zoomHorizontal(-1);
    }

    /**
     * Handles the "fit all" keyboard shortcut (Ctrl+0).
     * Resets zoom and scroll to show the entire session.
     */
    public void fitAll() {
        horizontalZoom.zoomToFit();
        verticalZoom.resetToDefault();
        scrollPosition.reset();
        updateScrollBounds();
        syncMinimap();
    }

    // ── Minimap navigation ─────────────────────────────────────────────────

    /**
     * Navigates the arrangement to center the viewport at the given
     * normalized position on the minimap.
     *
     * @param clickFraction the normalized click position in {@code [0, 1]}
     */
    public void navigateToMinimapPosition(double clickFraction) {
        double scrollBeats = minimapModel.clickToScrollOffset(clickFraction);
        scrollPosition.setHorizontalOffsetBeats(scrollBeats);
        syncMinimap();
    }

    /**
     * Applies a drag delta from the minimap to scroll the arrangement.
     *
     * @param deltaFraction the drag delta as a fraction of the minimap width
     */
    public void applyMinimapDrag(double deltaFraction) {
        double deltaBeats = minimapModel.dragToScrollDelta(deltaFraction);
        scrollPosition.scrollHorizontal(deltaBeats);
        syncMinimap();
    }

    // ── Viewport state persistence ─────────────────────────────────────────

    /**
     * Captures the current viewport state for project persistence.
     *
     * @return an immutable snapshot of the current viewport state
     */
    public ViewportState captureState() {
        return new ViewportState(
                horizontalZoom.getLevel(),
                verticalZoom.getTrackHeight(),
                scrollPosition.getHorizontalOffsetBeats(),
                scrollPosition.getVerticalOffsetPixels());
    }

    /**
     * Restores the viewport from a previously captured state.
     *
     * @param state the viewport state to restore (must not be null)
     */
    public void restoreState(ViewportState state) {
        Objects.requireNonNull(state, "state must not be null");
        horizontalZoom.setLevel(state.getHorizontalZoom());
        verticalZoom.setTrackHeight(state.getTrackHeight());
        updateScrollBounds();
        scrollPosition.setHorizontalOffsetBeats(state.getScrollXBeats());
        scrollPosition.setVerticalOffsetPixels(state.getScrollYPixels());
        syncMinimap();
    }

    // ── Status display ─────────────────────────────────────────────────────

    /**
     * Returns the horizontal zoom level as a percentage string for the
     * status bar (e.g. "125%").
     *
     * @return the formatted zoom percentage
     */
    public String getZoomPercentageString() {
        return horizontalZoom.toPercentageString();
    }

    /**
     * Returns the current pixels-per-beat scale (for use by the timeline
     * ruler and track rendering).
     *
     * @return pixels per beat at the current zoom level
     */
    public double currentPixelsPerBeat() {
        return BASE_PIXELS_PER_BEAT * horizontalZoom.getLevel();
    }

    /**
     * Returns the number of beats currently visible in the viewport.
     *
     * @return visible beats
     */
    public double getVisibleBeats() {
        double ppb = currentPixelsPerBeat();
        if (ppb <= 0) {
            return 0.0;
        }
        return viewportWidthPixels / ppb;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void syncMinimap() {
        double visibleBeats = getVisibleBeats();
        minimapModel.updateViewport(
                scrollPosition.getHorizontalOffsetBeats(),
                visibleBeats);
    }

    private void updateScrollBounds() {
        scrollPosition.setMaxHorizontalBeats(computeMaxHorizontalScroll());
        updateVerticalScrollBounds();
    }

    private void updateVerticalScrollBounds() {
        double totalHeight = trackCount * verticalZoom.getTrackHeight();
        double maxVertical = Math.max(0.0, totalHeight - viewportWidthPixels);
        scrollPosition.setMaxVerticalPixels(maxVertical);
    }

    private double computeMaxHorizontalScroll() {
        double totalBeats = minimapModel.getTotalDurationBeats();
        double visibleBeats = getVisibleBeats();
        return Math.max(0.0, totalBeats - visibleBeats);
    }
}
