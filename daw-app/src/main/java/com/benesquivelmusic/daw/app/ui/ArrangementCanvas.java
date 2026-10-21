package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackFoldState;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

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
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
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

    /**
     * Height of one take lane drawn under a track whose
     * {@link TakeComping#isActive() comping is active} (story 328, book
     * §5.6): a 4 px-grid multiple, half the default track height. The
     * track's slot grows by {@code laneCount × TAKE_LANE_HEIGHT} through
     * the one slot-height computation ({@link #rebuildLaneYCache()}).
     */
    static final double TAKE_LANE_HEIGHT = 40.0;

    /** Inset of a take-lane clip body and its name label from the lane edge. */
    private static final double TAKE_LANE_INSET = 4.0;

    private final Canvas canvas;

    private List<Track> tracks = List.of();
    private double pixelsPerBeat = ArrangementNavigator.BASE_PIXELS_PER_BEAT;
    private double samplesPerBeat = 0.0;
    /**
     * Active session sample rate in Hz used to detect rate-mismatched
     * clips and render the "↻ native→session" badge (story 126). A
     * value of {@code 0} disables the badge entirely; the canvas falls
     * back to {@code 0} until the controller wires the live rate.
     */
    private int sessionRateHz = 0;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight = TrackHeightZoom.DEFAULT_TRACK_HEIGHT;
    private double playheadBeat = -1.0;
    private boolean autoScroll = true;
    private double trimPreviewBeat = -1.0;
    private int trimPreviewTrackIndex = -1;

    // Slip preview overlay state — set by SlipToolHandler during Ctrl+Alt drag.
    // Exactly one of {@code slipAudioClip}, {@code slipMidiClip} is non-null
    // when a slip preview is active; both null hides the overlay.
    // Story 139 — docs/user-stories/139-slip-edit-within-clip.md.
    private AudioClip slipAudioClip;
    private MidiClip slipMidiClip;
    private double slipBeatDelta;
    private boolean slipHitEdge;

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
     * Story 329 (decision D18) — what each audio clip's body shows: its
     * waveform, or the {@code Loading…} / {@code Offline} placeholder.
     * {@code MainController} wires the open project's owner
     * ({@link OpenProjectAudio#stateOf}); until then nothing loads, so a
     * clip without audio that reads from a file is offline.
     */
    private Function<AudioClip, ClipAudioState> clipAudioStates = ClipAudioState::unmanaged;

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

    /**
     * Sets where each audio clip's body state comes from (story 329,
     * decision D18) and redraws.
     *
     * @param states answers a clip's {@link ClipAudioState}; must not be {@code null}
     */
    void setClipAudioStates(Function<AudioClip, ClipAudioState> states) {
        this.clipAudioStates = Objects.requireNonNull(states, "states must not be null");
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

    /**
     * Sets the session's samples-per-beat (= sample rate &times; 60 / tempo)
     * used to position per-clip gain-envelope breakpoints. Pass {@code 0.0}
     * (the default) to disable the envelope overlay.
     */
    public void setSamplesPerBeat(double samplesPerBeat) {
        this.samplesPerBeat = Math.max(0.0, samplesPerBeat);
        redraw();
    }

    /**
     * Sets the active session sample rate in Hz used to render the
     * SRC-mismatch badge on clips whose native rate differs (story 126).
     * Pass {@code 0} to disable the badge.
     */
    public void setSessionRateHz(int sessionRateHz) {
        this.sessionRateHz = Math.max(0, sessionRateHz);
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

    /**
     * Updates the slip preview overlay. Pass {@code (null, null, ...)} to
     * hide the overlay. Exactly one of {@code audioClip} or {@code midiClip}
     * should be non-null when a slip is in progress.
     *
     * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
     */
    void setSlipPreview(AudioClip audioClip, MidiClip midiClip,
                        double appliedBeatDelta, boolean hitEdge) {
        this.slipAudioClip = audioClip;
        this.slipMidiClip = midiClip;
        this.slipBeatDelta = appliedBeatDelta;
        this.slipHitEdge = hitEdge;
    }

    /** Returns the audio clip currently being slipped, or {@code null}. */
    AudioClip getSlipAudioClip() {
        return slipAudioClip;
    }

    /** Returns the MIDI clip currently being slipped, or {@code null}. */
    MidiClip getSlipMidiClip() {
        return slipMidiClip;
    }

    /** Returns the current slip preview beat delta. */
    double getSlipBeatDelta() {
        return slipBeatDelta;
    }

    /** Returns whether the slip preview has hit the source window edge. */
    boolean isSlipHitEdge() {
        return slipHitEdge;
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

    // ── Lane folding (Issue 568) ───────────────────────────────────────────

    /**
     * Listeners notified after any fold-state change applied through this
     * canvas. Track-strip controllers use this to refresh their disclosure
     * triangles when fold state is mutated by a shortcut, menu action, or
     * "fold all" toggle (rather than by clicking the strip's own button).
     */
    private final List<Runnable> foldChangeListeners = new ArrayList<>();

    /**
     * Registers a callback fired whenever any fold-state change is
     * applied through this canvas (single track, multi-track, or master
     * toggle). Used to keep external UI such as track-header disclosure
     * triangles in sync.
     *
     * @return an unsubscribe handle — callers <strong>must</strong> invoke
     *         {@link Runnable#run()} when the dependent UI is disposed
     *         (e.g. when its track strip is removed) so the listener and
     *         the nodes it captures can be garbage-collected. Returns a
     *         no-op handle when {@code listener} is {@code null}.
     */
    Runnable addFoldChangeListener(Runnable listener) {
        if (listener == null) {
            return () -> { };
        }
        foldChangeListeners.add(listener);
        return () -> foldChangeListeners.remove(listener);
    }

    /**
     * Explicitly unregisters a previously-added fold-change listener.
     * Equivalent to invoking the handle returned by
     * {@link #addFoldChangeListener(Runnable)}; provided for callers that
     * prefer to hold a reference to the listener directly.
     */
    void removeFoldChangeListener(Runnable listener) {
        if (listener != null) {
            foldChangeListeners.remove(listener);
        }
    }

    private void fireFoldChanged() {
        for (Runnable r : foldChangeListeners) {
            r.run();
        }
    }

    /**
     * Toggles the {@code automationFolded} flag on the given track.
     * When folded, the automation sub-lane (if visible) collapses to a
     * thin summary strip but the underlying automation data is left
     * untouched.
     */
    void toggleAutomationFold(Track track) {
        TrackFoldState s = track.getFoldState();
        track.setFoldState(s.withAutomationFolded(!s.automationFolded()));
        invalidateLaneCache();
        redraw();
        fireFoldChanged();
    }

    /**
     * Toggles every foldable lane group ({@code automation}, {@code takes},
     * {@code midi}) on the given track. When already partly folded this
     * collapses everything; when fully folded this expands everything.
     * Used by the {@code Shift+F} per-track shortcut.
     */
    void toggleAllFoldsForTrack(Track track) {
        TrackFoldState s = track.getFoldState();
        boolean targetFolded = !s.isFullyFolded();
        track.setFoldState(new TrackFoldState(
                targetFolded, targetFolded, targetFolded, s.headerHeightOverride()));
        invalidateLaneCache();
        redraw();
        fireFoldChanged();
    }

    /**
     * Toggles every foldable lane group on each given track. The target
     * fold state mirrors {@link #toggleAllFoldsForTrack(Track)}: when all
     * given tracks are already fully folded they unfold, otherwise they
     * fold. Used by the {@code Alt+Shift+F} multi-track shortcut so the
     * lane caches stay consistent with single-track toggles.
     *
     * @return {@code true} if the resulting state is "folded", {@code
     *         false} if "unfolded"; {@code false} when {@code tracks} is
     *         empty
     */
    boolean toggleAllFoldsForTracks(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return false;
        }
        boolean allFullyFolded = tracks.stream()
                .allMatch(t -> t.getFoldState().isFullyFolded());
        boolean targetFolded = !allFullyFolded;
        for (Track t : tracks) {
            t.setFoldState(new TrackFoldState(
                    targetFolded, targetFolded, targetFolded,
                    t.getFoldState().headerHeightOverride()));
        }
        invalidateLaneCache();
        redraw();
        fireFoldChanged();
        return targetFolded;
    }

    /**
     * Folds (or, when already folded, unfolds) the automation lane group
     * on every track. Used by the master "Fold all automation" toolbar
     * button. If at least one track is currently expanded, all are
     * folded; otherwise all are unfolded.
     */
    void toggleFoldAllAutomation() {
        boolean anyExpanded = tracks.stream()
                .anyMatch(t -> !t.getFoldState().automationFolded());
        for (Track t : tracks) {
            t.setFoldState(t.getFoldState().withAutomationFolded(anyExpanded));
        }
        invalidateLaneCache();
        redraw();
        fireFoldChanged();
    }

    /**
     * Forces the next layout query to rebuild {@link #laneYCache} and
     * {@link #slotBottomCache}. Needed because fold-state changes alter
     * lane heights but {@link #redraw()} short-circuits on a zero-size
     * canvas (e.g. before the stage has laid out the canvas) without
     * rebuilding the caches — leaving subsequent hit-tests stale.
     */
    private void invalidateLaneCache() {
        laneYCache = new double[0];
        effectiveHeightCache = new double[0];
        slotBottomCache = new double[0];
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
        return adjustedY >= slotTop + trackHeight + takeLanesHeight(tracks.get(trackIndex));
    }

    /**
     * Resolves the take lane under the pointer for the COMP tool (story
     * 328, book §5.6): the track's take lanes are stacked under its main
     * lane, {@link #TAKE_LANE_HEIGHT} each, in {@link TakeComping} order.
     * Every other tool uses it to stay inert over a take lane rather than
     * act on the main-lane clip at the same beat.
     *
     * @param trackIndex the track the pointer is over ({@link #trackIndexAtY(double)})
     * @param y          the Y pixel coordinate (in canvas space)
     * @return the zero-based take lane index, or {@code -1} for the main
     *         lane, a Y outside the take lanes (the automation lane below),
     *         a track without active comping, or an invalid track index
     */
    int takeLaneIndexAtY(int trackIndex, double y) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return -1;
        }
        TakeComping comping = tracks.get(trackIndex).getTakeComping();
        if (!comping.isActive()) {
            return -1;
        }
        if (slotBottomCache.length != tracks.size()) {
            rebuildLaneYCache();
        }
        double adjustedY = y + scrollYPixels;
        double slotTop = trackIndex == 0 ? 0 : slotBottomCache[trackIndex - 1];
        double offset = adjustedY - slotTop - trackHeight;
        if (offset < 0) {
            return -1;
        }
        int lane = (int) Math.floor(offset / TAKE_LANE_HEIGHT);
        return lane < comping.getTakeLaneCount() ? lane : -1;
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
        return computeLaneY(trackIndex) + trackHeight + takeLanesHeight(tracks.get(trackIndex));
    }

    /**
     * Returns the height the take lanes of {@code track} add to its slot:
     * {@code laneCount × }{@link #TAKE_LANE_HEIGHT} while its comping is
     * active, else {@code 0}. The one place a take lane's height enters
     * the layout (story 328).
     */
    double takeLanesHeight(Track track) {
        TakeComping comping = track.getTakeComping();
        return comping.isActive() ? comping.getTakeLaneCount() * TAKE_LANE_HEIGHT : 0.0;
    }

    /**
     * Returns the rendered height in pixels of the automation sub-lane
     * for the given track index, honouring fold state. Returns
     * {@code 0} when the track has no visible automation lane.
     */
    double automationLaneHeight(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return 0.0;
        }
        return automationLaneRenderHeight(tracks.get(trackIndex));
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
            double effective = trackHeight + takeLanesHeight(tracks.get(i))
                    + automationLaneRenderHeight(tracks.get(i));
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
            if (i < tracks.size()) {
                y += takeLanesHeight(tracks.get(i)) + automationLaneRenderHeight(tracks.get(i));
            }
        }
        return y - scrollYPixels;
    }

    /**
     * Returns the rendered height (pixels) of the automation sub-lane
     * for the given track. Honours per-track {@link TrackFoldState}: a
     * folded lane collapses to {@link TrackFoldState#SUMMARY_STRIP_HEIGHT_PX}
     * (a 3 px summary strip) so the user still sees that automation
     * data exists; an unfolded lane uses
     * {@link AutomationLaneRenderer#AUTOMATION_LANE_HEIGHT}; a track
     * whose automation lane is not currently visible contributes 0.
     */
    private double automationLaneRenderHeight(Track track) {
        if (!automationLaneVisibility.containsKey(track.getId())) {
            return 0.0;
        }
        boolean folded = track.getFoldState().automationFolded();
        return TrackFoldState.effectiveLaneHeight(folded,
                AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT);
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
        drawSlipPreviewIfVisible(gc, w, h);

        TransportOverlayRenderer.drawPlayhead(gc, playheadBeat,
                scrollXBeats, pixelsPerBeat, w, h);
    }

    private void drawClips(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            double laneY = laneYCache[i];
            // The track's clip area is its main lane plus the take lanes
            // under it, sized by the same height the hit-test resolves
            // against: a main lane scrolled above the viewport can leave
            // its take lanes on screen.
            double takeLanesTop = laneY + trackHeight;
            if (takeLanesTop + takeLanesHeight(track) < 0 || laneY > canvasHeight) {
                continue;
            }

            Color trackColor = parseTrackColor(track);

            if (takeLanesTop >= 0) {
                for (AudioClip clip : track.getClips()) {
                    ClipOverlayRenderer.drawAudioClip(gc, clip, trackColor,
                            laneY, trackHeight, pixelsPerBeat, scrollXBeats,
                            canvasWidth, canvasHeight, selectionModel,
                            samplesPerBeat, sessionRateHz, clipAudioStates.apply(clip));
                }

                MidiClip midiClip = track.getMidiClip();
                if (!midiClip.isEmpty() && track.getType() == TrackType.MIDI) {
                    ClipOverlayRenderer.drawMidiClip(gc, track, midiClip, trackColor,
                            laneY, trackHeight, pixelsPerBeat, scrollXBeats,
                            canvasWidth, canvasHeight, selectionModel);
                }
            }

            drawTakeLanes(gc, track, trackColor, takeLanesTop, canvasWidth, canvasHeight);
        }
    }

    /**
     * Draws the take lanes of a track whose comping is active (story 328,
     * book §5.6) under its main lane, one strip of {@link #TAKE_LANE_HEIGHT}
     * per {@link TakeLane} in lane order: the lane's name, its clips as
     * muted outlined bodies, and the comp-selected ranges in the one
     * accent the clip selection already uses. The compiled comp itself is
     * drawn with the main-lane clips above; nothing here is interactive
     * beyond {@link #takeLaneIndexAtY(int, double)}.
     */
    private void drawTakeLanes(GraphicsContext gc, Track track, Color trackColor,
                               double lanesTop, double canvasWidth, double canvasHeight) {
        TakeComping comping = track.getTakeComping();
        if (!comping.isActive()) {
            return;
        }
        List<TakeLane> lanes = comping.getTakeLanes();
        for (int laneIndex = 0; laneIndex < lanes.size(); laneIndex++) {
            double laneTop = lanesTop + laneIndex * TAKE_LANE_HEIGHT;
            if (laneTop + TAKE_LANE_HEIGHT < 0 || laneTop > canvasHeight) {
                continue;
            }
            TakeLane lane = lanes.get(laneIndex);
            gc.setFill(laneIndex % 2 == 0 ? TrackLaneRenderer.LANE_COLOR_ODD : TrackLaneRenderer.LANE_COLOR_EVEN);
            gc.fillRect(0, laneTop, canvasWidth, TAKE_LANE_HEIGHT);
            gc.setStroke(TrackLaneRenderer.LANE_SEPARATOR_COLOR);
            gc.setLineWidth(1.0);
            gc.strokeLine(0, laneTop + TAKE_LANE_HEIGHT, canvasWidth, laneTop + TAKE_LANE_HEIGHT);

            double bodyTop = laneTop + TAKE_LANE_INSET;
            double bodyHeight = TAKE_LANE_HEIGHT - 2 * TAKE_LANE_INSET;
            for (AudioClip clip : lane.getClips()) {
                double clipX = (clip.getStartBeat() - scrollXBeats) * pixelsPerBeat;
                double clipWidth = clip.getDurationBeats() * pixelsPerBeat;
                if (clipX + clipWidth < 0 || clipX > canvasWidth) {
                    continue;
                }
                // Muted body: the lane does not play; the comp above does.
                gc.setFill(trackColor.deriveColor(0, 1.0, 1.0, lane.isMuted() ? 0.12 : 0.25));
                gc.fillRoundRect(clipX, bodyTop, clipWidth, bodyHeight,
                        ClipOverlayRenderer.CLIP_CORNER_RADIUS, ClipOverlayRenderer.CLIP_CORNER_RADIUS);
                gc.setStroke(ClipOverlayRenderer.CLIP_BORDER_COLOR);
                gc.strokeRoundRect(clipX, bodyTop, clipWidth, bodyHeight,
                        ClipOverlayRenderer.CLIP_CORNER_RADIUS, ClipOverlayRenderer.CLIP_CORNER_RADIUS);
            }
            // One pass over the unmodifiable view, filtered by lane: no
            // per-lane list copy on every redraw (story 328 review).
            for (CompRegion region : comping.getCompRegions()) {
                if (region.takeIndex() != laneIndex) {
                    continue;
                }
                double regionX = (region.startBeat() - scrollXBeats) * pixelsPerBeat;
                double regionWidth = region.durationBeats() * pixelsPerBeat;
                if (regionX + regionWidth < 0 || regionX > canvasWidth) {
                    continue;
                }
                gc.setFill(ClipOverlayRenderer.CLIP_SELECTED_OVERLAY_COLOR);
                gc.fillRect(regionX, bodyTop, regionWidth, bodyHeight);
                gc.setStroke(ClipOverlayRenderer.CLIP_SELECTED_BORDER_COLOR);
                gc.strokeRect(regionX, bodyTop, regionWidth, bodyHeight);
            }
            gc.setFill(ClipOverlayRenderer.CLIP_LABEL_COLOR);
            gc.fillText(lane.isSoloed() ? lane.getName() + " (solo)" : lane.getName(),
                    TAKE_LANE_INSET, laneTop + TAKE_LANE_HEIGHT / 2 + TAKE_LANE_INSET);
        }
    }

    private void drawAutomationLanes(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            AutomationParameter param = automationLaneVisibility.get(track.getId());
            if (param == null) {
                continue;
            }

            double autoLaneY = laneYCache[i] + trackHeight + takeLanesHeight(track);
            double autoLaneHeight = automationLaneRenderHeight(track);
            if (autoLaneHeight <= 0
                    || autoLaneY + autoLaneHeight < 0
                    || autoLaneY > canvasHeight) {
                continue;
            }

            // When the automation lane group is folded, replace the full
            // envelope render with a thin summary strip so the user can
            // see that data exists without consuming vertical real estate.
            // (Issue 568 — the renderer collapses the lane's height to
            // SUMMARY_STRIP_HEIGHT_PX (3 px) and paints a tinted strip
            // showing that folded automation data exists.)
            if (track.getFoldState().automationFolded()) {
                AutomationLaneSummaryRenderer.draw(gc, autoLaneY, canvasWidth, autoLaneHeight);
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

    /**
     * Draws the slip-edit preview overlay — a translucent ghost of the clip
     * shifted by the in-progress beat delta, with a red flash when the drag
     * has hit the source-window edge.
     *
     * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
     */
    private void drawSlipPreviewIfVisible(GraphicsContext gc,
                                          double canvasWidth, double canvasHeight) {
        if (slipAudioClip == null && slipMidiClip == null) {
            return;
        }
        int trackIndex = findTrackIndexForSlipClip();
        if (trackIndex < 0 || trackIndex >= laneYCache.length) {
            return;
        }
        double laneY = laneYCache[trackIndex];
        if (slipAudioClip != null) {
            ClipOverlayRenderer.drawSlipGhost(gc,
                    slipAudioClip.getStartBeat(), slipAudioClip.getDurationBeats(),
                    slipBeatDelta, slipHitEdge,
                    scrollXBeats, pixelsPerBeat, laneY, trackHeight,
                    canvasWidth, canvasHeight);
        } else {
            double startBeat = SelectionModel.midiClipStartBeat(slipMidiClip);
            double endBeat = SelectionModel.midiClipEndBeat(slipMidiClip);
            ClipOverlayRenderer.drawSlipGhost(gc,
                    startBeat, endBeat - startBeat,
                    slipBeatDelta, slipHitEdge,
                    scrollXBeats, pixelsPerBeat, laneY, trackHeight,
                    canvasWidth, canvasHeight);
        }
    }

    /**
     * Resolves the track index that contains the clip currently being slipped,
     * by scanning the arrangement's track list. Returns -1 if the clip cannot
     * be located (e.g. it was removed mid-drag).
     */
    private int findTrackIndexForSlipClip() {
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            if (slipAudioClip != null && track.getClips().contains(slipAudioClip)) {
                return i;
            }
            if (slipMidiClip != null && track.getMidiClip() == slipMidiClip) {
                return i;
            }
        }
        return -1;
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
