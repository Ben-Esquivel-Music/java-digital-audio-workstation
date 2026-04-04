package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AddClipAction;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
import com.benesquivelmusic.daw.core.audio.FadeClipAction;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.audio.GlueClipsAction;
import com.benesquivelmusic.daw.core.audio.MoveClipAction;
import com.benesquivelmusic.daw.core.audio.RemoveClipAction;
import com.benesquivelmusic.daw.core.audio.SplitClipAction;
import com.benesquivelmusic.daw.core.automation.AddAutomationPointAction;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.MoveAutomationPointAction;
import com.benesquivelmusic.daw.core.automation.RemoveAutomationPointAction;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Translates mouse events on the {@link ArrangementCanvas} into model
 * operations based on the currently active {@link EditTool}.
 *
 * <p>This controller follows the same extraction pattern used by
 * {@link TrackStripController} and {@link TransportController}: it is a
 * package-private final class with a nested {@link Host} callback interface
 * and constructor injection of all dependencies.</p>
 */
final class ClipInteractionController {

    private static final Logger LOG = Logger.getLogger(ClipInteractionController.class.getName());

    /** Default duration in beats for newly created (pencil) clips. */
    static final double DEFAULT_NEW_CLIP_DURATION = 4.0;

    /**
     * Callback interface implemented by the host controller to provide
     * state and coordination methods.
     */
    interface Host {
        List<Track> tracks();
        EditTool activeTool();
        UndoManager undoManager();
        double pixelsPerBeat();
        double scrollXBeats();
        double scrollYPixels();
        double trackHeight();
        boolean snapEnabled();
        GridResolution gridResolution();
        int beatsPerBar();
        void refreshCanvas();
        void seekToPosition(double beat);
        SelectionModel selectionModel();
        void updateStatusBar(String text);
    }

    private final ArrangementCanvas canvas;
    private final Host host;
    private final ClipTrimHandler trimHandler;
    private final ClipFadeHandler fadeHandler;
    private final Tooltip fadeTooltip = new Tooltip();

    // Drag state for pointer tool
    private AudioClip dragClip;
    private Track dragSourceTrack;
    private double dragStartBeat;

    // Time selection drag state
    private boolean selectionDragging;
    private boolean selectionDragShift;
    private double selectionDragAnchorBeat;

    // Rubber-band (marquee) clip selection drag state
    private boolean rubberBandDragging;
    private boolean rubberBandShift;
    private double rubberBandAnchorX;
    private double rubberBandAnchorY;

    /**
     * Identifies which edge of the time selection is being dragged for
     * fine-tuning the selection boundaries.
     */
    enum SelectionEdge {
        LEFT,
        RIGHT
    }

    private SelectionEdge selectionHandleDrag;

    // Drag state for automation breakpoint moves
    private AutomationPoint dragAutomationPoint;
    private AutomationLane dragAutomationLane;
    private int dragAutomationTrackIndex = -1;
    private double dragAutomationOriginalBeat;
    private double dragAutomationOriginalValue;

    ClipInteractionController(ArrangementCanvas canvas, Host host) {
        this.canvas = Objects.requireNonNull(canvas, "canvas must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.trimHandler = new ClipTrimHandler(new ClipTrimHandler.Host() {
            @Override public double pixelsPerBeat() { return host.pixelsPerBeat(); }
            @Override public double scrollXBeats() { return host.scrollXBeats(); }
            @Override public double scrollYPixels() { return host.scrollYPixels(); }
            @Override public double trackHeight() { return host.trackHeight(); }
            @Override public List<Track> tracks() { return host.tracks(); }
            @Override public UndoManager undoManager() { return host.undoManager(); }
            @Override public boolean snapEnabled() { return host.snapEnabled(); }
            @Override public GridResolution gridResolution() { return host.gridResolution(); }
            @Override public int beatsPerBar() { return host.beatsPerBar(); }
            @Override public void refreshCanvas() { host.refreshCanvas(); }
            @Override public int trackIndexAtY(double y) { return canvas.trackIndexAtY(y); }
        });
        this.fadeHandler = new ClipFadeHandler(new ClipFadeHandler.Host() {
            @Override public double pixelsPerBeat() { return host.pixelsPerBeat(); }
            @Override public double scrollXBeats() { return host.scrollXBeats(); }
            @Override public double scrollYPixels() { return host.scrollYPixels(); }
            @Override public double trackHeight() { return host.trackHeight(); }
            @Override public List<Track> tracks() { return host.tracks(); }
            @Override public UndoManager undoManager() { return host.undoManager(); }
            @Override public boolean snapEnabled() { return host.snapEnabled(); }
            @Override public GridResolution gridResolution() { return host.gridResolution(); }
            @Override public int beatsPerBar() { return host.beatsPerBar(); }
            @Override public void refreshCanvas() { host.refreshCanvas(); }
            @Override public int trackIndexAtY(double y) { return canvas.trackIndexAtY(y); }
            @Override public double laneYForTrack(int trackIndex) { return canvas.computeLaneY(trackIndex); }
        });
    }

    /**
     * Installs mouse event handlers on the arrangement canvas.
     */
    void install() {
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setSelectionModel(host.selectionModel());
        updateCursor();
    }

    /**
     * Updates the cursor on the arrangement canvas based on the active tool.
     */
    void updateCursor() {
        Cursor cursor = switch (host.activeTool()) {
            case POINTER -> Cursor.DEFAULT;
            case PENCIL -> Cursor.CROSSHAIR;
            case ERASER -> Cursor.HAND;
            case SCISSORS -> Cursor.CROSSHAIR;
            case GLUE -> Cursor.HAND;
        };
        canvas.setCursor(cursor);
    }

    // ── Hit testing ──────────────────────────────────────────────────────────

    /**
     * Resolves the track index at the given Y pixel coordinate.
     *
     * @return the track index, or -1 if outside all lanes
     */
    int trackIndexAt(double y) {
        return canvas.trackIndexAtY(y);
    }

    /**
     * Resolves the beat position at the given X pixel coordinate.
     */
    double beatAt(double x) {
        return x / host.pixelsPerBeat() + host.scrollXBeats();
    }

    /**
     * Finds the audio clip at the given beat on the specified track,
     * or {@code null} if none.
     */
    AudioClip clipAt(Track track, double beat) {
        for (AudioClip clip : track.getClips()) {
            if (beat >= clip.getStartBeat() && beat < clip.getEndBeat()) {
                return clip;
            }
        }
        return null;
    }

    /**
     * Finds the MIDI clip at the given beat on the specified MIDI track,
     * or {@code null} if the track is not a MIDI track or the beat is
     * outside the MIDI clip's rendered bounds.
     */
    MidiClip midiClipAt(Track track, double beat) {
        if (track.getType() != TrackType.MIDI) {
            return null;
        }
        MidiClip midiClip = track.getMidiClip();
        if (midiClip.isEmpty()) {
            return null;
        }
        double startBeat = SelectionModel.midiClipStartBeat(midiClip);
        double endBeat = SelectionModel.midiClipEndBeat(midiClip);
        if (beat >= startBeat && beat < endBeat) {
            return midiClip;
        }
        return null;
    }

    /**
     * Tests whether the given X coordinate is within the draggable handle
     * zone of either selection edge. Returns the hit edge, or {@code null}
     * if no handle was hit.
     */
    SelectionEdge hitTestSelectionHandle(double x) {
        SelectionModel sm = host.selectionModel();
        if (!sm.hasSelection()) {
            return null;
        }
        double leftX = (sm.getStartBeat() - host.scrollXBeats()) * host.pixelsPerBeat();
        double rightX = (sm.getEndBeat() - host.scrollXBeats()) * host.pixelsPerBeat();
        double threshold = ArrangementCanvas.SELECTION_HANDLE_WIDTH;
        if (Math.abs(x - leftX) <= threshold) {
            return SelectionEdge.LEFT;
        }
        if (Math.abs(x - rightX) <= threshold) {
            return SelectionEdge.RIGHT;
        }
        return null;
    }

    /**
     * Snaps a beat position to the grid if snap-to-grid is enabled.
     */
    private double snapBeat(double beat) {
        if (host.snapEnabled()) {
            return SnapQuantizer.quantize(beat, host.gridResolution(), host.beatsPerBar());
        }
        return Math.max(0.0, beat);
    }

    /**
     * Updates the selection model, arrangement canvas overlay, and status bar
     * to reflect the given selection range.
     */
    private void applySelection(double startBeat, double endBeat) {
        SelectionModel sm = host.selectionModel();
        if (startBeat < endBeat) {
            sm.setSelection(startBeat, endBeat);
            canvas.setSelectionRange(true, startBeat, endBeat);
            double duration = endBeat - startBeat;
            host.updateStatusBar(String.format(
                    "Selection: %.2f – %.2f (%.2f beats)", startBeat, endBeat, duration));
        } else {
            sm.clearSelection();
            canvas.setSelectionRange(false, 0, 0);
            host.updateStatusBar("");
        }
    }

    /**
     * Clears the current time selection and updates the canvas overlay and
     * status bar. Does not trigger a full canvas refresh — the caller is
     * responsible for calling {@link Host#refreshCanvas()} if needed.
     */
    private void clearTimeSelection() {
        host.selectionModel().clearSelection();
        canvas.setSelectionRange(false, 0, 0);
        host.updateStatusBar("");
    }

    // ── Mouse event handlers ─────────────────────────────────────────────────

    private void onMousePressed(MouseEvent event) {
        int trackIndex = trackIndexAt(event.getY());
        double beat = beatAt(event.getX());

        // ── Automation lane interaction ─────────────────────────────────────
        if (trackIndex >= 0 && canvas.isYInAutomationLane(event.getY())) {
            Track track = host.tracks().get(trackIndex);
            AutomationParameter param = canvas.getAutomationParameter(track);
            if (param != null) {
                AutomationLane lane = track.getAutomationData().getLane(param);
                double autoLaneY = canvas.automationLaneY(trackIndex);
                double autoLaneH = AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;

                AutomationPoint hitPoint = lane == null ? null
                        : AutomationLaneRenderer.hitTestBreakpoint(
                                lane, event.getX(), event.getY(), param,
                                autoLaneY, autoLaneH,
                                host.pixelsPerBeat(), host.scrollXBeats());

                if (event.getButton() == MouseButton.SECONDARY
                        || host.activeTool() == EditTool.ERASER) {
                    // Right-click or Eraser tool → remove breakpoint
                    if (hitPoint != null) {
                        host.undoManager().execute(
                                new RemoveAutomationPointAction(lane, hitPoint));
                        host.refreshCanvas();
                    }
                    return;
                }

                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }

                if (hitPoint != null) {
                    // Start dragging an existing breakpoint
                    dragAutomationPoint = hitPoint;
                    dragAutomationLane = lane;
                    dragAutomationTrackIndex = trackIndex;
                    dragAutomationOriginalBeat = hitPoint.getTimeInBeats();
                    dragAutomationOriginalValue = hitPoint.getValue();
                    canvas.setCursor(Cursor.MOVE);
                } else {
                    // Click on empty area → add a new breakpoint
                    double value = AutomationLaneRenderer.yToValue(
                            event.getY(), param, autoLaneY, autoLaneH);
                    value = Math.max(param.getMinValue(),
                            Math.min(param.getMaxValue(), value));
                    AutomationPoint newPoint = new AutomationPoint(
                            Math.max(0, beat), value);
                    AutomationLane addLane = track.getAutomationData()
                            .getOrCreateLane(param);
                    host.undoManager().execute(
                            new AddAutomationPointAction(addLane, newPoint));
                    host.refreshCanvas();
                }
                return;
            }
        }

        // ── Normal clip interaction ──────────────────────────────────────────

        // Right-click on a fade handle → show curve type context menu
        if (event.getButton() == MouseButton.SECONDARY
                && host.activeTool() == EditTool.POINTER && trackIndex >= 0) {
            ClipFadeHandler.HandleHit fadeHit = fadeHandler.hitTestHandle(event.getX(), event.getY());
            if (fadeHit != null) {
                showFadeCurveContextMenu(fadeHit, event);
                return;
            }
        }

        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        // Check for fade handle activation before trim edges
        if (host.activeTool() == EditTool.POINTER && trackIndex >= 0) {
            ClipFadeHandler.HandleHit fadeHit = fadeHandler.hitTestHandle(event.getX(), event.getY());
            if (fadeHit != null) {
                fadeHandler.beginFade(fadeHit.clip(), fadeHit.handle());
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        // Check for trim edge activation before normal tool handling
        if (host.activeTool() == EditTool.POINTER && trackIndex >= 0) {
            ClipTrimHandler.EdgeHit hit = trimHandler.hitTestEdge(event.getX(), event.getY());
            if (hit != null) {
                trimHandler.beginTrim(hit.clip(), hit.edge());
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        // Check for selection handle activation (pointer tool)
        if (host.activeTool() == EditTool.POINTER) {
            SelectionEdge handleHit = hitTestSelectionHandle(event.getX());
            if (handleHit != null) {
                selectionHandleDrag = handleHit;
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        if (trackIndex < 0) {
            if (host.activeTool() == EditTool.POINTER) {
                // Click below all tracks: start selection or clear
                beginTimeSelectionDrag(beat, event.isShiftDown());
            }
            return;
        }
        Track track = host.tracks().get(trackIndex);

        switch (host.activeTool()) {
            case POINTER -> handlePointerPress(track, beat, event);
            case PENCIL -> handlePencilPress(track, beat);
            case ERASER -> handleEraserPress(track, beat);
            case SCISSORS -> handleScissorsPress(track, beat);
            case GLUE -> handleGluePress(track, beat);
        }
    }

    private void onMouseDragged(MouseEvent event) {
        // Automation breakpoint dragging
        if (dragAutomationPoint != null && dragAutomationLane != null) {
            Track track = host.tracks().get(dragAutomationTrackIndex);
            AutomationParameter param = canvas.getAutomationParameter(track);
            if (param != null) {
                double autoLaneY = canvas.automationLaneY(dragAutomationTrackIndex);
                double autoLaneH = AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
                double newBeat = Math.max(0, beatAt(event.getX()));
                double newValue = AutomationLaneRenderer.yToValue(
                        event.getY(), param, autoLaneY, autoLaneH);
                newValue = Math.max(param.getMinValue(),
                        Math.min(param.getMaxValue(), newValue));
                dragAutomationPoint.setTimeInBeats(newBeat);
                dragAutomationPoint.setValue(newValue);
                dragAutomationLane.sortPoints();
                host.refreshCanvas();
            }
            return;
        }

        if (fadeHandler.isFading()) {
            fadeHandler.updateFade(event.getX());
            host.refreshCanvas();
            return;
        }

        if (trimHandler.isTrimming()) {
            int trackIndex = trackIndexAt(event.getY());
            // updateTrim applies the trim and computes the clamped preview beat
            // but does not trigger a redraw — we do a single refresh below.
            trimHandler.updateTrim(event.getX(), trackIndex);
            canvas.setTrimPreview(trimHandler.getPreviewBeat(), trimHandler.getPreviewTrackIndex());
            host.refreshCanvas();
            return;
        }

        // Selection handle drag
        if (selectionHandleDrag != null) {
            updateSelectionHandleDrag(event.getX());
            return;
        }

        // Rubber-band clip selection drag
        if (rubberBandDragging) {
            canvas.setRubberBand(true, rubberBandAnchorX, rubberBandAnchorY,
                    event.getX(), event.getY());
            return;
        }

        // Time selection drag
        if (selectionDragging) {
            double snappedBeat = snapBeat(beatAt(event.getX()));
            double lo = Math.min(selectionDragAnchorBeat, snappedBeat);
            double hi = Math.max(selectionDragAnchorBeat, snappedBeat);
            if (lo < hi) {
                applySelection(lo, hi);
            }
            host.refreshCanvas();
            return;
        }

        if (host.activeTool() != EditTool.POINTER || dragClip == null) {
            return;
        }
        // Drag preview is visual only — the actual move happens on release
    }

    private void onMouseReleased(MouseEvent event) {
        // Complete automation breakpoint drag — register undo action
        if (dragAutomationPoint != null && dragAutomationLane != null) {
            Track track = host.tracks().get(dragAutomationTrackIndex);
            AutomationParameter param = canvas.getAutomationParameter(track);
            if (param != null) {
                double autoLaneY = canvas.automationLaneY(dragAutomationTrackIndex);
                double autoLaneH = AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;
                double newBeat = Math.max(0, beatAt(event.getX()));
                double newValue = AutomationLaneRenderer.yToValue(
                        event.getY(), param, autoLaneY, autoLaneH);
                newValue = Math.max(param.getMinValue(),
                        Math.min(param.getMaxValue(), newValue));
                // Restore original position so MoveAutomationPointAction can
                // capture the correct before/after state
                dragAutomationPoint.setTimeInBeats(dragAutomationOriginalBeat);
                dragAutomationPoint.setValue(dragAutomationOriginalValue);
                dragAutomationLane.sortPoints();
                host.undoManager().execute(new MoveAutomationPointAction(
                        dragAutomationLane, dragAutomationPoint,
                        newBeat, newValue));
                host.refreshCanvas();
            } else {
                // Lane collapsed or parameter cleared mid-drag — restore
                // the point to its original position to avoid leaving it
                // stranded at the preview position with no undo action.
                dragAutomationPoint.setTimeInBeats(dragAutomationOriginalBeat);
                dragAutomationPoint.setValue(dragAutomationOriginalValue);
                dragAutomationLane.sortPoints();
                host.refreshCanvas();
            }
            dragAutomationPoint = null;
            dragAutomationLane = null;
            dragAutomationTrackIndex = -1;
            updateCursor();
            return;
        }

        if (fadeHandler.isFading()) {
            fadeHandler.completeFade(event.getX());
            updateCursor();
            return;
        }

        if (trimHandler.isTrimming()) {
            // Clear the visual trim preview before completing the trim so that
            // any refresh triggered inside completeTrim() does not render a
            // stale cyan preview line.
            canvas.setTrimPreview(-1.0, -1);
            trimHandler.completeTrim(event.getX());
            updateCursor();
            return;
        }

        // Complete selection handle drag
        if (selectionHandleDrag != null) {
            updateSelectionHandleDrag(event.getX());
            selectionHandleDrag = null;
            updateCursor();
            return;
        }

        // Complete rubber-band clip selection drag
        if (rubberBandDragging) {
            canvas.setRubberBand(false, 0, 0, 0, 0);
            double x1 = rubberBandAnchorX;
            double y1 = rubberBandAnchorY;
            double x2 = event.getX();
            double y2 = event.getY();
            double beatStart = beatAt(Math.min(x1, x2));
            double beatEnd = beatAt(Math.max(x1, x2));
            boolean dragged = Math.abs(x2 - x1) > 2 || Math.abs(y2 - y1) > 2;
            if (dragged && beatStart < beatEnd) {
                List<Track> coveredTracks = tracksInYRange(y1, y2);
                if (!coveredTracks.isEmpty()) {
                    if (rubberBandShift) {
                        host.selectionModel().addClipsInRegion(coveredTracks, beatStart, beatEnd);
                    } else {
                        host.selectionModel().selectClipsInRegion(coveredTracks, beatStart, beatEnd);
                    }
                }
            } else if (!rubberBandShift) {
                // Click without drag (non-shift) — clear selections and seek
                host.selectionModel().clearClipSelection();
                clearTimeSelection();
                double snappedBeat = snapBeat(beatAt(event.getX()));
                host.seekToPosition(snappedBeat);
            }
            rubberBandDragging = false;
            rubberBandShift = false;
            host.refreshCanvas();
            return;
        }

        // Complete time selection drag
        if (selectionDragging) {
            double snappedBeat = snapBeat(beatAt(event.getX()));
            double lo = Math.min(selectionDragAnchorBeat, snappedBeat);
            double hi = Math.max(selectionDragAnchorBeat, snappedBeat);
            if (lo < hi) {
                applySelection(lo, hi);
            } else if (!selectionDragShift) {
                // Click without drag (non-shift) — clear selection and seek
                clearTimeSelection();
                host.seekToPosition(snappedBeat);
            }
            // When shift was held and lo == hi, the existing selection is
            // preserved (no-op) rather than unexpectedly cleared.
            selectionDragging = false;
            selectionDragShift = false;
            host.refreshCanvas();
            return;
        }

        if (host.activeTool() != EditTool.POINTER || dragClip == null) {
            return;
        }
        double beat = beatAt(event.getX());
        double newStartBeat = Math.max(0.0, beat - (dragStartBeat - dragClip.getStartBeat()));

        int targetTrackIndex = trackIndexAt(event.getY());
        if (targetTrackIndex >= 0) {
            Track targetTrack = host.tracks().get(targetTrackIndex);
            if (targetTrack == dragSourceTrack) {
                // Same track — only move the beat position
                if (Math.abs(newStartBeat - dragClip.getStartBeat()) > 0.001) {
                    host.undoManager().execute(new MoveClipAction(dragClip, newStartBeat));
                    host.refreshCanvas();
                }
            } else {
                // Cross-track move: remove from source, update position, add to target
                host.undoManager().execute(new CrossTrackMoveAction(
                        dragSourceTrack, targetTrack, dragClip, newStartBeat));
                host.refreshCanvas();
            }
        }

        dragClip = null;
        dragSourceTrack = null;
    }

    private void onMouseMoved(MouseEvent event) {
        if (host.activeTool() == EditTool.POINTER) {
            // Check fade handles first
            ClipFadeHandler.HandleHit fadeHit = fadeHandler.hitTestHandle(event.getX(), event.getY());
            if (fadeHit != null) {
                canvas.setCursor(Cursor.H_RESIZE);
                fadeTooltip.setText(ClipFadeHandler.tooltipFor(fadeHit));
                Tooltip.install(canvas, fadeTooltip);
                return;
            }
            Tooltip.uninstall(canvas, fadeTooltip);

            ClipTrimHandler.EdgeHit hit = trimHandler.hitTestEdge(event.getX(), event.getY());
            if (hit != null) {
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }

            // Check selection handles
            SelectionEdge handleHit = hitTestSelectionHandle(event.getX());
            if (handleHit != null) {
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        } else {
            Tooltip.uninstall(canvas, fadeTooltip);
        }
        updateCursor();
    }

    // ── Tool-specific handlers ───────────────────────────────────────────────

    private void handlePointerPress(Track track, double beat, MouseEvent event) {
        AudioClip clip = clipAt(track, beat);
        if (clip != null) {
            // Click on an audio clip
            clearTimeSelection();
            if (event.isShiftDown()) {
                host.selectionModel().toggleClipSelection(track, clip);
                host.refreshCanvas();
            } else {
                host.selectionModel().selectClip(track, clip);
                host.refreshCanvas();
                dragClip = clip;
                dragSourceTrack = track;
                dragStartBeat = beat;
            }
            LOG.fine(() -> "Pointer: selected clip '" + clip.getName() + "' at beat " + beat);
            return;
        }
        // Check for MIDI clip hit
        MidiClip midiClip = midiClipAt(track, beat);
        if (midiClip != null) {
            clearTimeSelection();
            if (event.isShiftDown()) {
                host.selectionModel().toggleMidiClipSelection(track, midiClip);
            } else {
                host.selectionModel().selectMidiClip(track, midiClip);
            }
            host.refreshCanvas();
            LOG.fine(() -> "Pointer: selected MIDI clip on track '" + track.getName() + "' at beat " + beat);
            return;
        }
        // Empty space in a track lane — begin rubber-band clip selection
        beginRubberBandDrag(event.getX(), event.getY(), event.isShiftDown());
    }

    /**
     * Begins a time selection drag. If Shift is held and a selection exists,
     * extends the existing selection to the new beat; otherwise starts a new
     * selection from the clicked beat.
     *
     * <p>The playhead is also moved to the clicked position so that the user
     * gets immediate feedback (consistent with the prior seek-on-click
     * behavior).</p>
     */
    private void beginTimeSelectionDrag(double beat, boolean shiftDown) {
        double snappedBeat = snapBeat(beat);
        SelectionModel sm = host.selectionModel();
        selectionDragShift = shiftDown && sm.hasSelection();
        if (selectionDragShift) {
            // Extend selection: anchor is the farther existing edge
            double distToStart = Math.abs(snappedBeat - sm.getStartBeat());
            double distToEnd = Math.abs(snappedBeat - sm.getEndBeat());
            if (distToStart <= distToEnd) {
                selectionDragAnchorBeat = sm.getEndBeat();
            } else {
                selectionDragAnchorBeat = sm.getStartBeat();
            }
            double lo = Math.min(selectionDragAnchorBeat, snappedBeat);
            double hi = Math.max(selectionDragAnchorBeat, snappedBeat);
            if (lo < hi) {
                applySelection(lo, hi);
            }
        } else {
            // Clear previous selection and start new drag
            clearTimeSelection();
            selectionDragAnchorBeat = snappedBeat;
        }
        selectionDragging = true;
        // Seek immediately so the playhead moves to the click position
        host.seekToPosition(snappedBeat);
    }

    /**
     * Begins a rubber-band (marquee) clip selection drag. The anchor pixel
     * coordinates are stored and updated as the user drags to form a 2D
     * selection rectangle. On release, all clips that overlap the rectangle
     * are selected via the selection model.
     *
     * <p>If Shift is held, the rubber-band selection is additive — clips
     * within the rectangle are added to the existing selection.</p>
     */
    private void beginRubberBandDrag(double x, double y, boolean shiftDown) {
        rubberBandDragging = true;
        rubberBandShift = shiftDown;
        rubberBandAnchorX = x;
        rubberBandAnchorY = y;
        if (!shiftDown) {
            host.selectionModel().clearClipSelection();
            clearTimeSelection();
        }
    }

    /**
     * Returns all tracks whose clip lanes overlap the vertical pixel range
     * between {@code y1} and {@code y2} (in canvas space).
     */
    private List<Track> tracksInYRange(double y1, double y2) {
        double topY = Math.min(y1, y2);
        double bottomY = Math.max(y1, y2);
        List<Track> result = new java.util.ArrayList<>();
        for (int i = 0; i < host.tracks().size(); i++) {
            double laneTop = canvas.computeLaneY(i);
            double laneBottom = laneTop + host.trackHeight();
            if (laneTop < bottomY && laneBottom > topY) {
                result.add(host.tracks().get(i));
            }
        }
        return result;
    }

    /**
     * Updates the selection boundary corresponding to the active handle drag.
     */
    private void updateSelectionHandleDrag(double x) {
        double beat = snapBeat(beatAt(x));
        SelectionModel sm = host.selectionModel();
        double start = sm.getStartBeat();
        double end = sm.getEndBeat();
        if (selectionHandleDrag == SelectionEdge.LEFT) {
            start = beat;
        } else {
            end = beat;
        }
        // Ensure start < end; if the handle crosses the opposite edge, swap
        double lo = Math.min(start, end);
        double hi = Math.max(start, end);
        if (lo < hi) {
            applySelection(lo, hi);
        }
        host.refreshCanvas();
    }

    private void handlePencilPress(Track track, double beat) {
        // Only create a clip if there's no existing clip at this position
        if (clipAt(track, beat) != null) {
            return;
        }
        double startBeat = Math.max(0.0, beat);
        AudioClip newClip = new AudioClip("New Clip", startBeat, DEFAULT_NEW_CLIP_DURATION, null);
        host.undoManager().execute(new AddClipAction(track, newClip));
        host.refreshCanvas();
        LOG.fine(() -> "Pencil: created clip at beat " + startBeat);
    }

    private void handleEraserPress(Track track, double beat) {
        AudioClip clip = clipAt(track, beat);
        if (clip == null) {
            return;
        }
        host.undoManager().execute(new RemoveClipAction(track, clip));
        host.refreshCanvas();
        LOG.fine(() -> "Eraser: removed clip '" + clip.getName() + "'");
    }

    private void handleScissorsPress(Track track, double beat) {
        AudioClip clip = clipAt(track, beat);
        if (clip == null) {
            return;
        }
        // Only split if the beat is strictly inside the clip
        if (beat <= clip.getStartBeat() || beat >= clip.getEndBeat()) {
            return;
        }
        host.undoManager().execute(new SplitClipAction(track, clip, beat));
        host.refreshCanvas();
        LOG.fine(() -> "Scissors: split clip '" + clip.getName() + "' at beat " + beat);
    }

    private void handleGluePress(Track track, double beat) {
        // Find two adjacent clips: the clip that ends closest to the beat
        // and the clip that starts closest to the beat, on the same track.
        List<AudioClip> clips = track.getClips().stream()
                .sorted(Comparator.comparingDouble(AudioClip::getStartBeat))
                .toList();

        for (int i = 0; i < clips.size() - 1; i++) {
            AudioClip left = clips.get(i);
            AudioClip right = clips.get(i + 1);
            // Check if the click is near the boundary between adjacent clips
            double boundary = left.getEndBeat();
            double tolerance = 0.5; // half a beat
            if (Math.abs(beat - boundary) <= tolerance
                    && Math.abs(boundary - right.getStartBeat()) < 0.001) {
                host.undoManager().execute(new GlueClipsAction(track, left, right));
                host.refreshCanvas();
                LOG.fine(() -> "Glue: merged clips '" + left.getName()
                        + "' and '" + right.getName() + "'");
                return;
            }
        }
    }

    // ── Fade curve context menu ──────────────────────────────────────────────

    private void showFadeCurveContextMenu(ClipFadeHandler.HandleHit hit, MouseEvent event) {
        AudioClip clip = hit.clip();
        boolean isFadeIn = hit.handle() == ClipFadeHandler.FadeHandle.FADE_IN;

        ContextMenu menu = new ContextMenu();
        for (FadeCurveType curveType : FadeCurveType.values()) {
            MenuItem item = new MenuItem(curveLabel(curveType));
            item.setOnAction(e -> {
                FadeCurveType currentIn = clip.getFadeInCurveType();
                FadeCurveType currentOut = clip.getFadeOutCurveType();
                FadeCurveType newIn = isFadeIn ? curveType : currentIn;
                FadeCurveType newOut = isFadeIn ? currentOut : curveType;
                if (newIn != currentIn || newOut != currentOut) {
                    host.undoManager().execute(new FadeClipAction(
                            clip, clip.getFadeInBeats(), clip.getFadeOutBeats(),
                            newIn, newOut));
                    host.refreshCanvas();
                }
            });
            menu.getItems().add(item);
        }
        menu.show(canvas, event.getScreenX(), event.getScreenY());
    }

    private static String curveLabel(FadeCurveType curveType) {
        return switch (curveType) {
            case LINEAR -> "Linear";
            case EQUAL_POWER -> "Equal Power";
            case S_CURVE -> "S-Curve";
        };
    }

}
