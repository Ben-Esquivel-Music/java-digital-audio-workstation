package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders horizontal track lanes with audio and MIDI clip rectangles
 * for the arrangement view.
 *
 * <p>Drawing is delegated to focused renderer utilities
 * ({@link TrackLaneRenderer}, {@link ClipOverlayRenderer},
 * {@link ClipWaveformRenderer}, {@link ClipMidiPreviewRenderer},
 * {@link AutomationLaneRenderer}, {@link TransportOverlayRenderer}) so
 * this class is limited to state management, layout math, hit-testing,
 * and orchestration.</p>
 *
 * <p>Each track occupies a horizontal lane whose height is determined by
 * the current {@link TrackHeightZoom}. Horizontal and vertical scrolling
 * are controlled via the canvas's scroll and zoom parameters and can be
 * coordinated with higher-level navigation components by the caller.</p>
 */
public final class ArrangementCanvas extends Pane {

    // ── Color constants (retained for existing test references) ───────────
    static final Color LOOP_HIGHLIGHT_COLOR = TransportOverlayRenderer.LOOP_HIGHLIGHT_COLOR;
    static final Color SELECTION_HIGHLIGHT_COLOR = TransportOverlayRenderer.SELECTION_HIGHLIGHT_COLOR;
    static final Color SELECTION_BORDER_COLOR = TransportOverlayRenderer.SELECTION_BORDER_COLOR;
    static final Color SELECTION_HANDLE_COLOR = TransportOverlayRenderer.SELECTION_HANDLE_COLOR;
    static final Color RUBBER_BAND_FILL_COLOR = TransportOverlayRenderer.RUBBER_BAND_FILL_COLOR;
    static final Color RUBBER_BAND_BORDER_COLOR = TransportOverlayRenderer.RUBBER_BAND_BORDER_COLOR;
    static final Color CLIP_SELECTED_BORDER_COLOR = ClipOverlayRenderer.CLIP_SELECTED_BORDER_COLOR;
    static final Color CLIP_SELECTED_OVERLAY_COLOR = ClipOverlayRenderer.CLIP_SELECTED_OVERLAY_COLOR;

    /** Width of the draggable handle zones at each selection edge, in pixels. */
    static final double SELECTION_HANDLE_WIDTH = 6.0;

    /**
     * Beats per grid column — MIDI note columns are 1/16 notes (0.25 beats)
     * at 4/4. Shared with {@link EditorView#BEATS_PER_COLUMN}.
     */
    static final double BEATS_PER_COLUMN = EditorView.BEATS_PER_COLUMN;

    private final Canvas canvas;

    private List<Track> tracks = List.of();
    private double pixelsPerBeat = ArrangementNavigator.BASE_PIXELS_PER_BEAT;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight = TrackHeightZoom.DEFAULT_TRACK_HEIGHT;
    private double playheadBeat = -1.0;
    private boolean autoScroll = true;
    private double trimPreviewBeat = -1.0;
    private int trimPreviewTrackIndex = -1;

    // Loop region overlay state
    private boolean loopEnabled = false;
    private double loopStartBeat = 0.0;
    private double loopEndBeat = 16.0;

    // Time selection overlay state
    private boolean selectionActive = false;
    private double selectionStartBeat = 0.0;
    private double selectionEndBeat = 0.0;

    // Rubber-band selection overlay state (pixel coordinates)
    private boolean rubberBandActive = false;
    private double rubberBandX1;
    private double rubberBandY1;
    private double rubberBandX2;
    private double rubberBandY2;

    /** Selection model used to check which clips are currently selected. */
    private SelectionModel selectionModel;

    /**
     * Tracks which track IDs have their automation lane expanded.
     * Key: track ID, Value: the selected {@link AutomationParameter}.
     */
    private final Map<String, AutomationParameter> automationLaneVisibility = new HashMap<>();

    /** Per-track top-Y offsets in canvas space (scroll applied). */
    private double[] laneYCache = new double[0];

    /** Parallel to {@link #laneYCache}: per-track track + automation height. */
    private double[] effectiveHeightCache = new double[0];

    /**
     * Per-track cumulative slot-bottom boundaries (absolute Y, no scroll),
     * used by {@link #trackIndexAtY(double)} for binary-search hit-testing.
     */
    private double[] slotBottomCache = new double[0];

    /**
     * Creates an empty arrangement canvas.
     */
    public ArrangementCanvas() {
        this.canvas = new Canvas();
        getChildren().add(canvas);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((obs, oldV, newV) -> redraw());
        heightProperty().addListener((obs, oldV, newV) -> redraw());
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Updates the track list and redraws. The list is not copied — the
     * caller must ensure the reference remains valid.
     *
     * @param tracks the current project tracks
     */
    public void setTracks(List<Track> tracks) {
        this.tracks = tracks == null ? List.of() : tracks;
        if (!automationLaneVisibility.isEmpty()) {
            var validIds = this.tracks.stream()
                    .map(Track::getId)
                    .collect(java.util.stream.Collectors.toSet());
            automationLaneVisibility.keySet().retainAll(validIds);
        }
        redraw();
    }

    /** Sets the horizontal scale (pixels per beat) and redraws. */
    public void setPixelsPerBeat(double pixelsPerBeat) {
        if (pixelsPerBeat <= 0) {
            return;
        }
        this.pixelsPerBeat = pixelsPerBeat;
        redraw();
    }

    /** Sets the horizontal scroll offset in beats and redraws. */
    public void setScrollXBeats(double scrollXBeats) {
        this.scrollXBeats = Math.max(0.0, scrollXBeats);
        redraw();
    }

    /** Sets the vertical scroll offset in pixels and redraws. */
    public void setScrollYPixels(double scrollYPixels) {
        this.scrollYPixels = Math.max(0.0, scrollYPixels);
        redraw();
    }

    /** Sets the track lane height in pixels and redraws. */
    public void setTrackHeight(double trackHeight) {
        this.trackHeight = Math.max(TrackHeightZoom.MIN_TRACK_HEIGHT, trackHeight);
        redraw();
    }

    /** Sets the playhead position in beats (negative hides it) and redraws. */
    public void setPlayheadBeat(double beat) {
        if (Double.compare(this.playheadBeat, beat) == 0) {
            return;
        }
        this.playheadBeat = beat;
        if (autoScroll && beat >= 0) {
            ensurePlayheadVisible();
        }
        redraw();
    }

    /** Enables/disables auto horizontal scrolling to keep the playhead visible. */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
    }

    /** Forces a full redraw of the arrangement canvas. */
    public void refresh() {
        redraw();
    }

    /** Updates the loop region overlay state and redraws. */
    public void setLoopRegion(boolean enabled, double startBeat, double endBeat) {
        this.loopEnabled = enabled;
        this.loopStartBeat = startBeat;
        this.loopEndBeat = endBeat;
        redraw();
    }

    /** Returns {@code true} if the loop region overlay is enabled. */
    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    /** Returns the loop start beat for the overlay. */
    public double getLoopStartBeat() {
        return loopStartBeat;
    }

    /** Returns the loop end beat for the overlay. */
    public double getLoopEndBeat() {
        return loopEndBeat;
    }

    // ── Time selection overlay ─────────────────────────────────────────────

    /** Updates the time selection overlay state and redraws. */
    public void setSelectionRange(boolean active, double startBeat, double endBeat) {
        this.selectionActive = active;
        this.selectionStartBeat = startBeat;
        this.selectionEndBeat = endBeat;
        redraw();
    }

    /** Returns {@code true} if the time selection overlay is active. */
    public boolean isSelectionActive() {
        return selectionActive;
    }

    /** Returns the selection start beat for the overlay. */
    public double getSelectionStartBeat() {
        return selectionStartBeat;
    }

    /** Returns the selection end beat for the overlay. */
    public double getSelectionEndBeat() {
        return selectionEndBeat;
    }

    /** Sets the trim preview ghost-line position (negative hides it). */
    void setTrimPreview(double beat, int trackIndex) {
        this.trimPreviewBeat = beat;
        this.trimPreviewTrackIndex = trackIndex;
    }

    // ── Rubber-band selection overlay ──────────────────────────────────────

    /** Sets the rubber-band rectangle state (pixel coords) and redraws. */
    void setRubberBand(boolean active, double x1, double y1, double x2, double y2) {
        this.rubberBandActive = active;
        this.rubberBandX1 = x1;
        this.rubberBandY1 = y1;
        this.rubberBandX2 = x2;
        this.rubberBandY2 = y2;
        redraw();
    }

    /** Returns {@code true} if the rubber-band overlay is currently active. */
    boolean isRubberBandActive() {
        return rubberBandActive;
    }

    /** Sets the selection model used for clip highlighting. */
    void setSelectionModel(SelectionModel selectionModel) {
        this.selectionModel = selectionModel;
    }

    // ── Automation lane visibility ─────────────────────────────────────────

    /**
     * Toggles the visibility of the automation lane for the given track.
     * When shown, the lane defaults to {@link AutomationParameter#VOLUME}.
     */
    void toggleAutomationLane(Track track) {
        String id = track.getId();
        if (automationLaneVisibility.containsKey(id)) {
            automationLaneVisibility.remove(id);
        } else {
            automationLaneVisibility.put(id, AutomationParameter.VOLUME);
        }
        redraw();
    }

    /** Returns whether the automation lane is visible for the given track. */
    boolean isAutomationLaneVisible(Track track) {
        return automationLaneVisibility.containsKey(track.getId());
    }

    /** Sets the automation parameter displayed in the given track's lane. */
    void setAutomationParameter(Track track, AutomationParameter parameter) {
        if (automationLaneVisibility.containsKey(track.getId())) {
            automationLaneVisibility.put(track.getId(), parameter);
            redraw();
        }
    }

    /**
     * Returns the currently selected automation parameter for the given
     * track's lane, or {@code null} if the lane is not visible.
     */
    AutomationParameter getAutomationParameter(Track track) {
        return automationLaneVisibility.get(track.getId());
    }

    // ── Getters (for testing) ──────────────────────────────────────────────

    double getPixelsPerBeat() {
        return pixelsPerBeat;
    }

    double getScrollXBeats() {
        return scrollXBeats;
    }

    double getScrollYPixels() {
        return scrollYPixels;
    }

    double getTrackHeight() {
        return trackHeight;
    }

    double getPlayheadBeat() {
        return playheadBeat;
    }

    double getTrimPreviewBeat() {
        return trimPreviewBeat;
    }

    int getTrimPreviewTrackIndex() {
        return trimPreviewTrackIndex;
    }

    /**
     * Resolves the track index at the given Y pixel coordinate, accounting
     * for expanded automation lanes.
     *
     * @param y the Y pixel coordinate (in canvas space)
     * @return the track index, or {@code -1} if outside all track lanes
     */
    int trackIndexAtY(double y) {
        double adjustedY = y + scrollYPixels;
        if (adjustedY < 0) {
            return -1;
        }
        if (slotBottomCache.length != tracks.size()) {
            rebuildLaneYCache();
        }
        int n = slotBottomCache.length;
        if (n == 0) {
            return -1;
        }
        int lo = 0;
        int hi = n - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (slotBottomCache[mid] > adjustedY) {
                result = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the given Y coordinate falls within an
     * automation sub-lane (below the track lane proper) rather than in
     * the clip area of a track.
     */
    boolean isYInAutomationLane(double y) {
        int trackIndex = trackIndexAtY(y);
        if (trackIndex < 0) {
            return false;
        }
        if (!automationLaneVisibility.containsKey(tracks.get(trackIndex).getId())) {
            return false;
        }
        double adjustedY = y + scrollYPixels;
        double slotTop = trackIndex == 0 ? 0 : slotBottomCache[trackIndex - 1];
        return adjustedY >= slotTop + trackHeight;
    }

    /**
     * Returns the Y pixel coordinate of the automation sub-lane top for
     * the given track index (in canvas space, accounting for scroll).
     */
    double automationLaneY(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return -1;
        }
        if (!automationLaneVisibility.containsKey(tracks.get(trackIndex).getId())) {
            return -1;
        }
        return computeLaneY(trackIndex) + trackHeight;
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    /**
     * Builds the cumulative lane-Y cache, effective-height cache, and
     * slot-bottom cache for all tracks. Called at the start of each
     * {@link #redraw()} invocation.
     */
    private void rebuildLaneYCache() {
        int n = tracks.size();
        if (laneYCache.length != n) {
            laneYCache = new double[n];
            effectiveHeightCache = new double[n];
            slotBottomCache = new double[n];
        }
        double cumulative = 0;
        for (int i = 0; i < n; i++) {
            laneYCache[i] = cumulative - scrollYPixels;
            double effective = trackHeight;
            if (automationLaneVisibility.containsKey(tracks.get(i).getId())) {
                effective += AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            }
            effectiveHeightCache[i] = effective;
            cumulative += effective;
            slotBottomCache[i] = cumulative;
        }
    }

    /**
     * Computes the Y pixel offset for the given track index on demand.
     * Used by hit-testing methods outside the render loop.
     */
    double computeLaneY(int trackIndex) {
        double y = 0;
        for (int i = 0; i < trackIndex; i++) {
            y += trackHeight;
            if (i < tracks.size() && automationLaneVisibility.containsKey(tracks.get(i).getId())) {
                y += AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            }
        }
        return y - scrollYPixels;
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        rebuildLaneYCache();

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        int n = tracks.size();
        double contentBottomY = (n == 0 ? 0 : slotBottomCache[n - 1]) - scrollYPixels;
        TrackLaneRenderer.draw(gc, n, laneYCache, trackHeight,
                effectiveHeightCache, w, h, contentBottomY);

        TransportOverlayRenderer.drawLoopHighlight(gc, loopEnabled,
                loopStartBeat, loopEndBeat, scrollXBeats, pixelsPerBeat, w, h);

        drawClips(gc, w, h);
        drawAutomationLanes(gc, w, h);

        TransportOverlayRenderer.drawSelectionHighlight(gc, selectionActive,
                selectionStartBeat, selectionEndBeat, scrollXBeats, pixelsPerBeat, w, h);
        TransportOverlayRenderer.drawRubberBand(gc, rubberBandActive,
                rubberBandX1, rubberBandY1, rubberBandX2, rubberBandY2, w, h);

        drawTrimPreviewIfVisible(gc, w, h);

        TransportOverlayRenderer.drawPlayhead(gc, playheadBeat,
                scrollXBeats, pixelsPerBeat, w, h);
    }

    private void drawClips(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            double laneY = laneYCache[i];
            if (laneY + trackHeight < 0 || laneY > canvasHeight) {
                continue;
            }

            Color trackColor = parseTrackColor(track);

            for (AudioClip clip : track.getClips()) {
                ClipOverlayRenderer.drawAudioClip(gc, clip, trackColor,
                        laneY, trackHeight, pixelsPerBeat, scrollXBeats,
                        canvasWidth, canvasHeight, selectionModel);
            }

            MidiClip midiClip = track.getMidiClip();
            if (!midiClip.isEmpty() && track.getType() == TrackType.MIDI) {
                ClipOverlayRenderer.drawMidiClip(gc, track, midiClip, trackColor,
                        laneY, trackHeight, pixelsPerBeat, scrollXBeats,
                        canvasWidth, canvasHeight, selectionModel);
            }
        }
    }

    private void drawAutomationLanes(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            AutomationParameter param = automationLaneVisibility.get(track.getId());
            if (param == null) {
                continue;
            }

            double autoLaneY = laneYCache[i] + trackHeight;
            double autoLaneHeight = AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
            if (autoLaneY + autoLaneHeight < 0 || autoLaneY > canvasHeight) {
                continue;
            }

            AutomationData automationData = track.getAutomationData();
            AutomationLane lane = automationData.getLane(param);
            if (lane == null) {
                lane = new AutomationLane(param);
            }
            AutomationLaneRenderer.draw(gc, lane, autoLaneY, canvasWidth,
                    autoLaneHeight, pixelsPerBeat, scrollXBeats);
        }
    }

    private void drawTrimPreviewIfVisible(GraphicsContext gc,
                                          double canvasWidth, double canvasHeight) {
        if (trimPreviewBeat < 0 || trimPreviewTrackIndex < 0
                || trimPreviewTrackIndex >= laneYCache.length) {
            return;
        }
        ClipOverlayRenderer.drawTrimPreview(gc, trimPreviewBeat, scrollXBeats,
                pixelsPerBeat, laneYCache[trimPreviewTrackIndex], trackHeight,
                canvasWidth, canvasHeight);
    }

    private Color parseTrackColor(Track track) {
        String hex = track.getColor().getHexColor();
        try {
            return Color.web(hex);
        } catch (IllegalArgumentException e) {
            return Color.web("#3498DB");
        }
    }

    /**
     * Auto-scrolls the horizontal offset to keep the playhead in view.
     * Uses the same approach as {@link TimelineRuler#ensurePlayheadVisible()}.
     */
    private void ensurePlayheadVisible() {
        double viewWidthBeats = canvas.getWidth() / pixelsPerBeat;
        if (viewWidthBeats <= 0) {
            return;
        }
        if (playheadBeat < scrollXBeats) {
            scrollXBeats = playheadBeat;
        } else if (playheadBeat > scrollXBeats + viewWidthBeats * 0.9) {
            scrollXBeats = playheadBeat - viewWidthBeats * 0.1;
        }
    }
}
