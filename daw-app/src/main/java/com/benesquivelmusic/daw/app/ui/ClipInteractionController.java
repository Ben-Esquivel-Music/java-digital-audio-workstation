package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.drag.DragSourceKind;
import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;
import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.automation.*;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.project.edit.RippleEditService;
import com.benesquivelmusic.daw.core.project.edit.RippleValidationException;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Translates mouse events on the {@link ArrangementCanvas} into model
 * operations based on the currently active {@link EditTool}.
 *
 * <p>This controller follows the same extraction pattern used by
 * {@link TrackStripController} and {@link TransportController}: it is a
 * package-private final class taking a {@link Deps} record of direct functional
 * dependencies (story 293 retired the former {@code Host} callback-up
 * interface) and constructor injection of all dependencies.</p>
 */
final class ClipInteractionController {

    private static final Logger LOG = Logger.getLogger(ClipInteractionController.class.getName());

    /** Default duration in beats for newly created (pencil) clips. */
    static final double DEFAULT_NEW_CLIP_DURATION = 4.0;

    /**
     * Story 293 — direct collaborators replacing the retired {@code Host}
     * callback-up interface (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §9). A plain
     * data carrier of functional dependencies. This controller is init-only
     * (not reconstructed on project load), so the swappable {@code undoManager}
     * is a {@link Supplier} read fresh on every access; geometry getters,
     * tool/ripple/selection state, and the action seams are all functional
     * dependencies bound by {@link ArrangementCanvasFactory}.
     *
     * @param tracks           the live track list
     * @param activeTool       the active edit tool
     * @param undoManager      the live undo manager (swappable)
     * @param pixelsPerBeat    horizontal zoom in pixels per beat
     * @param scrollXBeats     horizontal scroll offset in beats
     * @param scrollYPixels    vertical scroll offset in pixels
     * @param trackHeight      uniform track-lane height in pixels
     * @param snapEnabled      whether snap-to-grid is active
     * @param gridResolution   the active grid resolution
     * @param beatsPerBar      beats per bar
     * @param refreshCanvas    requests a canvas repaint
     * @param seekToPosition   seeks the transport to a beat
     * @param selectionModel   the selection model
     * @param updateStatusBar  writes a status-bar message
     * @param rippleMode       the live ripple mode
     * @param showNotification surfaces a notification (level, message)
     * @param projectTempoBpm  project tempo in BPM (slip source-length math)
     * @param onTimeStretchClip opens the time-stretch dialog (story 042)
     * @param onPitchShiftClip  opens the pitch-shift dialog (story 042)
     */
    record Deps(Supplier<List<Track>> tracks,
                Supplier<EditTool> activeTool,
                Supplier<UndoManager> undoManager,
                DoubleSupplier pixelsPerBeat,
                DoubleSupplier scrollXBeats,
                DoubleSupplier scrollYPixels,
                DoubleSupplier trackHeight,
                BooleanSupplier snapEnabled,
                Supplier<GridResolution> gridResolution,
                IntSupplier beatsPerBar,
                Runnable refreshCanvas,
                DoubleConsumer seekToPosition,
                Supplier<SelectionModel> selectionModel,
                Consumer<String> updateStatusBar,
                Supplier<RippleMode> rippleMode,
                BiConsumer<NotificationLevel, String> showNotification,
                DoubleSupplier projectTempoBpm,
                Runnable onTimeStretchClip,
                Runnable onPitchShiftClip) {
    }

    private final ArrangementCanvas canvas;
    private final Deps deps;
    private final ClipTrimHandler trimHandler;
    private final ClipFadeHandler fadeHandler;
    private final SlipToolHandler slipHandler;
    private final Tooltip fadeTooltip = new Tooltip();

    // Drag state for pointer tool
    private AudioClip dragClip;
    private Track dragSourceTrack;
    private int dragSourceTrackIndex = -1;
    private double dragStartBeat;
    private boolean groupDrag;
    // Time-selection snapshot captured at drag-start, before the click clears it.
    private OptionalDouble dragSelectionStart = OptionalDouble.empty();
    private OptionalDouble dragSelectionEnd = OptionalDouble.empty();

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

    /**
     * Original clip start-beat captured at drag-begin so the advisor's
     * cancel-revert (Esc) can restore the clip's position.
     */
    private double dragClipOriginalStartBeat;

    /**
     * Shared {@link DragVisualAdvisor} consulted on every clip-drag
     * gesture (story 197). Optional — when {@code null} clip drags fall
     * back to the legacy direct-manipulation behaviour.
     */
    private DragVisualAdvisor dragVisualAdvisor;

    // Drag state for automation breakpoint moves
    private AutomationPoint dragAutomationPoint;
    private AutomationLane dragAutomationLane;
    private int dragAutomationTrackIndex = -1;
    private double dragAutomationOriginalBeat;
    private double dragAutomationOriginalValue;

    // Comp tool drag state
    private CompToolHandler compToolHandler;

    ClipInteractionController(ArrangementCanvas canvas, Deps deps) {
        this.canvas = Objects.requireNonNull(canvas, "canvas must not be null");
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.trimHandler = new ClipTrimHandler(new ClipTrimHandler.Deps(
                deps.pixelsPerBeat(),
                deps.scrollXBeats(),
                deps.scrollYPixels(),
                deps.trackHeight(),
                deps.tracks(),
                deps.undoManager(),
                deps.snapEnabled(),
                deps.gridResolution(),
                deps.beatsPerBar(),
                deps.refreshCanvas(),
                canvas::trackIndexAtY));
        this.fadeHandler = new ClipFadeHandler(new ClipFadeHandler.Deps(
                deps.pixelsPerBeat(),
                deps.scrollXBeats(),
                deps.scrollYPixels(),
                deps.trackHeight(),
                deps.tracks(),
                deps.undoManager(),
                deps.snapEnabled(),
                deps.gridResolution(),
                deps.beatsPerBar(),
                deps.refreshCanvas(),
                canvas::trackIndexAtY,
                canvas::computeLaneY));
        this.slipHandler = new SlipToolHandler(new SlipToolHandler.Deps(
                deps.pixelsPerBeat(),
                deps.undoManager(),
                deps.projectTempoBpm(),
                deps.refreshCanvas(),
                deps.showNotification(),
                canvas::setSlipPreview));
    }

    /**
     * Installs mouse event handlers on the arrangement canvas.
     */
    void install() {
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);
        canvas.setSelectionModel(deps.selectionModel().get());
        // Ensure the canvas can receive key events so the Esc filter fires.
        canvas.setFocusTraversable(true);
        // Esc cancels the in-progress clip drag with the cancel-revert
        // animation timings from the shared AnimationProfile (story 197).
        canvas.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE && dragClip != null) {
                cancelClipDragInProgress();
                ev.consume();
            }
        });
        updateCursor();
    }

    /**
     * Updates the cursor on the arrangement canvas based on the active tool.
     */
    void updateCursor() {
        Cursor cursor = switch (deps.activeTool().get()) {
            case POINTER -> Cursor.DEFAULT;
            case PENCIL -> Cursor.CROSSHAIR;
            case ERASER -> Cursor.HAND;
            case SCISSORS -> Cursor.CROSSHAIR;
            case GLUE -> Cursor.HAND;
            case COMP -> Cursor.CROSSHAIR;
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
        return x / deps.pixelsPerBeat().getAsDouble() + deps.scrollXBeats().getAsDouble();
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
        SelectionModel sm = deps.selectionModel().get();
        if (!sm.hasSelection()) {
            return null;
        }
        double leftX = (sm.getStartBeat() - deps.scrollXBeats().getAsDouble()) * deps.pixelsPerBeat().getAsDouble();
        double rightX = (sm.getEndBeat() - deps.scrollXBeats().getAsDouble()) * deps.pixelsPerBeat().getAsDouble();
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
        if (deps.snapEnabled().getAsBoolean()) {
            return SnapQuantizer.quantize(beat, deps.gridResolution().get(), deps.beatsPerBar().getAsInt());
        }
        return Math.max(0.0, beat);
    }

    /**
     * Updates the selection model, arrangement canvas overlay, and status bar
     * to reflect the given selection range.
     */
    private void applySelection(double startBeat, double endBeat) {
        SelectionModel sm = deps.selectionModel().get();
        if (startBeat < endBeat) {
            sm.setSelection(startBeat, endBeat);
            canvas.setSelectionRange(true, startBeat, endBeat);
            double duration = endBeat - startBeat;
            deps.updateStatusBar().accept(String.format(
                    "Selection: %.2f – %.2f (%.2f beats)", startBeat, endBeat, duration));
        } else {
            sm.clearSelection();
            canvas.setSelectionRange(false, 0, 0);
            deps.updateStatusBar().accept("");
        }
    }

    /**
     * Clears the current time selection and updates the canvas overlay and
     * status bar. Does not trigger a full canvas refresh — the caller is
     * responsible for calling {@link Host#refreshCanvas()} if needed.
     */
    private void clearTimeSelection() {
        deps.selectionModel().get().clearSelection();
        canvas.setSelectionRange(false, 0, 0);
        deps.updateStatusBar().accept("");
    }

    // ── Mouse event handlers ─────────────────────────────────────────────────

    private void onMousePressed(MouseEvent event) {
        // Ensure the canvas has focus so Esc key events are received.
        if (!canvas.isFocused()) {
            canvas.requestFocus();
        }
        int trackIndex = trackIndexAt(event.getY());
        double beat = beatAt(event.getX());

        // ── Automation lane interaction ─────────────────────────────────────
        if (trackIndex >= 0 && canvas.isYInAutomationLane(event.getY())) {
            Track track = deps.tracks().get().get(trackIndex);
            AutomationParameter param = canvas.getAutomationParameter(track);
            if (param != null) {
                AutomationLane lane = track.getAutomationData().getLane(param);
                double autoLaneY = canvas.automationLaneY(trackIndex);
                double autoLaneH = AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT;

                AutomationPoint hitPoint = lane == null ? null
                        : AutomationLaneRenderer.hitTestBreakpoint(
                                lane, event.getX(), event.getY(), param,
                                autoLaneY, autoLaneH,
                                deps.pixelsPerBeat().getAsDouble(), deps.scrollXBeats().getAsDouble());

                if (event.getButton() == MouseButton.SECONDARY
                        || deps.activeTool().get() == EditTool.ERASER) {
                    // Right-click or Eraser tool → remove breakpoint
                    if (hitPoint != null) {
                        deps.undoManager().get().execute(
                                new RemoveAutomationPointAction(lane, hitPoint));
                        deps.refreshCanvas().run();
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
                    deps.undoManager().get().execute(
                            new AddAutomationPointAction(addLane, newPoint));
                    deps.refreshCanvas().run();
                }
                return;
            }
        }

        // ── Normal clip interaction ──────────────────────────────────────────

        // Right-click on a fade handle → show curve type context menu
        if (event.getButton() == MouseButton.SECONDARY
                && deps.activeTool().get() == EditTool.POINTER && trackIndex >= 0) {
            ClipFadeHandler.HandleHit fadeHit = fadeHandler.hitTestHandle(event.getX(), event.getY());
            if (fadeHit != null) {
                showFadeCurveContextMenu(fadeHit, event);
                return;
            }
        }

        // Right-click on a clip body → show clip context menu (Story 042 —
        // entry point for Time-Stretch / Pitch-Shift dialogs).
        if (event.getButton() == MouseButton.SECONDARY
                && deps.activeTool().get() == EditTool.POINTER && trackIndex >= 0) {
            Track ctxTrack = deps.tracks().get().get(trackIndex);
            AudioClip ctxClip = clipAt(ctxTrack, beat);
            if (ctxClip != null) {
                // Ensure the right-clicked clip is part of the selection so
                // the controller acts on it (mirrors common DAW behaviour).
                SelectionModel sm = deps.selectionModel().get();
                boolean alreadySelected = sm.getSelectedClips().stream()
                        .anyMatch(e -> e.clip() == ctxClip);
                if (!alreadySelected) {
                    sm.clearClipSelection();
                    sm.selectClip(ctxTrack, ctxClip);
                    deps.refreshCanvas().run();
                }
                showClipContextMenu(event);
                return;
            }
        }

        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        // Check for slip-edit activation: Ctrl+Alt+drag inside a clip body.
        // Slip must win over trim/fade so Ctrl+Alt near an edge still slips.
        // Story 139 — docs/user-stories/139-slip-edit-within-clip.md.
        if (deps.activeTool().get() == EditTool.POINTER && trackIndex >= 0
                && event.isShortcutDown() && event.isAltDown()) {
            Track slipTrack = deps.tracks().get().get(trackIndex);
            AudioClip audioHit = clipAt(slipTrack, beat);
            if (audioHit != null) {
                slipHandler.beginAudioSlip(slipTrack, audioHit, event.getX());
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
            MidiClip midiHit = midiClipAt(slipTrack, beat);
            if (midiHit != null) {
                slipHandler.beginMidiSlip(midiHit, event.getX());
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        // Check for fade handle activation before trim edges
        if (deps.activeTool().get() == EditTool.POINTER && trackIndex >= 0) {
            ClipFadeHandler.HandleHit fadeHit = fadeHandler.hitTestHandle(event.getX(), event.getY());
            if (fadeHit != null) {
                fadeHandler.beginFade(fadeHit.clip(), fadeHit.handle());
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        // Check for trim edge activation before normal tool handling
        if (deps.activeTool().get() == EditTool.POINTER && trackIndex >= 0) {
            ClipTrimHandler.EdgeHit hit = trimHandler.hitTestEdge(event.getX(), event.getY());
            if (hit != null) {
                trimHandler.beginTrim(hit.track(), hit.clip(), hit.edge());
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        // Check for selection handle activation (pointer tool)
        if (deps.activeTool().get() == EditTool.POINTER) {
            SelectionEdge handleHit = hitTestSelectionHandle(event.getX());
            if (handleHit != null) {
                selectionHandleDrag = handleHit;
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }

        if (trackIndex < 0) {
            if (deps.activeTool().get() == EditTool.POINTER) {
                // Click below all tracks: start selection or clear
                beginTimeSelectionDrag(beat, event.isShiftDown());
            }
            return;
        }
        Track track = deps.tracks().get().get(trackIndex);

        switch (deps.activeTool().get()) {
            case POINTER -> handlePointerPress(track, trackIndex, beat, event);
            case PENCIL -> handlePencilPress(track, beat);
            case ERASER -> handleEraserPress(track, beat);
            case SCISSORS -> handleScissorsPress(track, beat);
            case GLUE -> handleGluePress(track, beat);
            case COMP -> handleCompPress(track, beat, event);
        }
    }

    private void onMouseDragged(MouseEvent event) {
        // Automation breakpoint dragging
        if (dragAutomationPoint != null && dragAutomationLane != null) {
            Track track = deps.tracks().get().get(dragAutomationTrackIndex);
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
                deps.refreshCanvas().run();
            }
            return;
        }

        if (slipHandler.isSlipping()) {
            slipHandler.updateSlip(event.getX());
            return;
        }

        if (fadeHandler.isFading()) {
            fadeHandler.updateFade(event.getX());
            deps.refreshCanvas().run();
            return;
        }

        if (trimHandler.isTrimming()) {
            int trackIndex = trackIndexAt(event.getY());
            // updateTrim applies the trim and computes the clamped preview beat
            // but does not trigger a redraw — we do a single refresh below.
            trimHandler.updateTrim(event.getX(), trackIndex);
            canvas.setTrimPreview(trimHandler.getPreviewBeat(), trimHandler.getPreviewTrackIndex());
            deps.refreshCanvas().run();
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
            deps.refreshCanvas().run();
            return;
        }

        if (deps.activeTool().get() != EditTool.POINTER || dragClip == null) {
            return;
        }
        // Drag preview is visual only — the actual move happens on release
    }

    private void onMouseReleased(MouseEvent event) {
        // Complete automation breakpoint drag — register undo action
        if (dragAutomationPoint != null && dragAutomationLane != null) {
            Track track = deps.tracks().get().get(dragAutomationTrackIndex);
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
                deps.undoManager().get().execute(new MoveAutomationPointAction(
                        dragAutomationLane, dragAutomationPoint,
                        newBeat, newValue));
                deps.refreshCanvas().run();
            } else {
                // Lane collapsed or parameter cleared mid-drag — restore
                // the point to its original position to avoid leaving it
                // stranded at the preview position with no undo action.
                dragAutomationPoint.setTimeInBeats(dragAutomationOriginalBeat);
                dragAutomationPoint.setValue(dragAutomationOriginalValue);
                dragAutomationLane.sortPoints();
                deps.refreshCanvas().run();
            }
            dragAutomationPoint = null;
            dragAutomationLane = null;
            dragAutomationTrackIndex = -1;
            updateCursor();
            return;
        }

        if (slipHandler.isSlipping()) {
            slipHandler.completeSlip(event.getX());
            updateCursor();
            return;
        }

        // Complete comp tool swipe
        if (compToolHandler != null && compToolHandler.isSwipeActive()) {
            double beat = Math.max(0.0, beatAt(event.getX()));
            compToolHandler.endSwipe(beat);
            compToolHandler = null;
            deps.refreshCanvas().run();
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
                        deps.selectionModel().get().addClipsInRegion(coveredTracks, beatStart, beatEnd);
                    } else {
                        deps.selectionModel().get().selectClipsInRegion(coveredTracks, beatStart, beatEnd);
                    }
                }
            } else if (!rubberBandShift) {
                // Click without drag (non-shift) — clear selections and seek
                deps.selectionModel().get().clearClipSelection();
                clearTimeSelection();
                double snappedBeat = snapBeat(beatAt(event.getX()));
                deps.seekToPosition().accept(snappedBeat);
            }
            rubberBandDragging = false;
            rubberBandShift = false;
            deps.refreshCanvas().run();
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
                deps.seekToPosition().accept(snappedBeat);
            }
            // When shift was held and lo == hi, the existing selection is
            // preserved (no-op) rather than unexpectedly cleared.
            selectionDragging = false;
            selectionDragShift = false;
            deps.refreshCanvas().run();
            return;
        }

        if (deps.activeTool().get() != EditTool.POINTER || dragClip == null) {
            return;
        }
        double beat = beatAt(event.getX());
        double snappedNewStart = snapBeat(
                Math.max(0.0, beat - (dragStartBeat - dragClip.getStartBeat())));
        double beatDelta = snappedNewStart - dragClip.getStartBeat();

        int targetTrackIndex = trackIndexAt(event.getY());

        if (groupDrag && targetTrackIndex >= 0) {
            // Group move: move all selected clips by the same delta
            int trackDelta = targetTrackIndex - dragSourceTrackIndex;
            List<ClipboardEntry> selected = deps.selectionModel().get().getSelectedClips();
            if (!selected.isEmpty()
                    && (Math.abs(beatDelta) > 0.001 || trackDelta != 0)) {
                List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
                for (ClipboardEntry entry : selected) {
                    entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
                }
                deps.undoManager().get().execute(new GroupMoveClipsAction(
                        entries, beatDelta, trackDelta, deps.tracks().get()));
                // After cross-track move, update the selection's track mapping
                // so that subsequent operations reference the correct tracks.
                if (trackDelta != 0) {
                    var movedClips = new java.util.HashSet<AudioClip>(entries.size());
                    for (Map.Entry<Track, AudioClip> e : entries) {
                        movedClips.add(e.getValue());
                    }
                    deps.selectionModel().get().clearClipSelection();
                    for (Track t : deps.tracks().get()) {
                        for (AudioClip c : t.getClips()) {
                            if (movedClips.contains(c)) {
                                deps.selectionModel().get().toggleClipSelection(t, c);
                            }
                        }
                    }
                }
                deps.refreshCanvas().run();
            } else {
                // Click without drag on a multi-selected clip — collapse to single selection
                deps.selectionModel().get().selectClip(dragSourceTrack, dragClip);
                deps.refreshCanvas().run();
            }
        } else if (targetTrackIndex >= 0) {
            Track targetTrack = deps.tracks().get().get(targetTrackIndex);
            if (targetTrack == dragSourceTrack) {
                // Same track — only move the beat position
                if (Math.abs(beatDelta) > 0.001) {
                    executeMove(dragClip, dragSourceTrack, snappedNewStart);
                }
            } else {
                // Cross-track move: remove from source, update position, add to target
                // (Ripple semantics do not apply to cross-track moves — the source
                // track closes the gap only when an explicit delete occurs.)
                deps.undoManager().get().execute(new CrossTrackMoveAction(
                        dragSourceTrack, targetTrack, dragClip, snappedNewStart));
                deps.refreshCanvas().run();
            }
        }

        dragClip = null;
        dragSourceTrack = null;
        dragSourceTrackIndex = -1;
        groupDrag = false;
        dragSelectionStart = OptionalDouble.empty();
        dragSelectionEnd = OptionalDouble.empty();
        notifyAdvisorCommitClipDrag();
    }

    /**
     * Executes a same-track clip move, routing through the {@link RippleEditService}
     * when the project's {@link RippleMode} is active. Falls back to a plain
     * {@link MoveClipAction} when ripple is {@link RippleMode#OFF} or when
     * ripple validation rejects the edit (the user is notified in that case).
     */
    private void executeMove(AudioClip clip, Track track, double newStartBeat) {
        RippleMode mode = deps.rippleMode().get();
        if (mode == RippleMode.OFF) {
            deps.undoManager().get().execute(new MoveClipAction(track, clip, newStartBeat));
            deps.refreshCanvas().run();
            return;
        }
        try {
            deps.undoManager().get().execute(RippleEditService.buildRippleMove(
                    clip, track, newStartBeat, mode, deps.tracks().get(),
                    dragSelectionStart, dragSelectionEnd));
            deps.refreshCanvas().run();
        } catch (RippleValidationException e) {
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Move cancelled — ripple would overlap clips: " + e.getMessage());
        }
    }

    private void onMouseMoved(MouseEvent event) {
        if (deps.activeTool().get() == EditTool.POINTER) {
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

    private void handlePointerPress(Track track, int trackIndex, double beat, MouseEvent event) {
        AudioClip clip = clipAt(track, beat);
        if (clip != null) {
            // Snapshot the time selection before clearing it so executeMove
            // can still honour selection-gated ripple on mouse-release.
            SelectionModel sm = deps.selectionModel().get();
            dragSelectionStart = sm.hasSelection()
                    ? OptionalDouble.of(sm.getStartBeat()) : OptionalDouble.empty();
            dragSelectionEnd = sm.hasSelection()
                    ? OptionalDouble.of(sm.getEndBeat()) : OptionalDouble.empty();
            // Click on an audio clip
            clearTimeSelection();
            if (event.isShiftDown()) {
                deps.selectionModel().get().toggleClipSelection(track, clip);
                deps.refreshCanvas().run();
            } else if (deps.selectionModel().get().isClipSelected(clip)
                       && deps.selectionModel().get().getSelectedClips().size() > 1) {
                // Clip is already part of a multi-selection — start group drag
                // without resetting the selection
                groupDrag = true;
                dragClip = clip;
                dragSourceTrack = track;
                dragSourceTrackIndex = trackIndex;
                dragStartBeat = beat;
                dragClipOriginalStartBeat = clip.getStartBeat();
                notifyAdvisorBeginClipDrag(clip, event.getScreenX(), event.getScreenY());
            } else {
                deps.selectionModel().get().selectClip(track, clip);
                deps.refreshCanvas().run();
                groupDrag = false;
                dragClip = clip;
                dragSourceTrack = track;
                dragSourceTrackIndex = trackIndex;
                dragStartBeat = beat;
                dragClipOriginalStartBeat = clip.getStartBeat();
                notifyAdvisorBeginClipDrag(clip, event.getScreenX(), event.getScreenY());
            }
            LOG.fine(() -> "Pointer: selected clip '" + clip.getName() + "' at beat " + beat);
            return;
        }
        // Check for MIDI clip hit
        MidiClip midiClip = midiClipAt(track, beat);
        if (midiClip != null) {
            clearTimeSelection();
            if (event.isShiftDown()) {
                deps.selectionModel().get().toggleMidiClipSelection(track, midiClip);
            } else {
                deps.selectionModel().get().selectMidiClip(track, midiClip);
            }
            deps.refreshCanvas().run();
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
        SelectionModel sm = deps.selectionModel().get();
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
        deps.seekToPosition().accept(snappedBeat);
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
            deps.selectionModel().get().clearClipSelection();
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
        for (int i = 0; i < deps.tracks().get().size(); i++) {
            double laneTop = canvas.computeLaneY(i);
            double laneBottom = laneTop + deps.trackHeight().getAsDouble();
            if (laneTop < bottomY && laneBottom > topY) {
                result.add(deps.tracks().get().get(i));
            }
        }
        return result;
    }

    /**
     * Updates the selection boundary corresponding to the active handle drag.
     */
    private void updateSelectionHandleDrag(double x) {
        double beat = snapBeat(beatAt(x));
        SelectionModel sm = deps.selectionModel().get();
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
        deps.refreshCanvas().run();
    }

    private void handlePencilPress(Track track, double beat) {
        // Only create a clip if there's no existing clip at this position
        if (clipAt(track, beat) != null) {
            return;
        }
        double startBeat = Math.max(0.0, beat);
        AudioClip newClip = new AudioClip("New Clip", startBeat, DEFAULT_NEW_CLIP_DURATION, null);
        deps.undoManager().get().execute(new AddClipAction(track, newClip));
        deps.refreshCanvas().run();
        LOG.fine(() -> "Pencil: created clip at beat " + startBeat);
    }

    private void handleEraserPress(Track track, double beat) {
        AudioClip clip = clipAt(track, beat);
        if (clip == null) {
            return;
        }
        SelectionModel sm = deps.selectionModel().get();
        if (sm.isClipSelected(clip) && sm.getSelectedClips().size() > 1) {
            // Group delete: remove all selected clips as a single undoable action
            List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
            for (ClipboardEntry entry : sm.getSelectedClips()) {
                entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
            }
            deps.undoManager().get().execute(new CutClipsAction(entries));
            sm.clearClipSelection();
            deps.refreshCanvas().run();
            LOG.fine(() -> "Eraser: removed " + entries.size() + " selected clips");
        } else {
            deps.undoManager().get().execute(new RemoveClipAction(track, clip));
            deps.refreshCanvas().run();
            LOG.fine(() -> "Eraser: removed clip '" + clip.getName() + "'");
        }
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
        deps.undoManager().get().execute(new SplitClipAction(track, clip, beat));
        deps.refreshCanvas().run();
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
                deps.undoManager().get().execute(new GlueClipsAction(track, left, right));
                deps.refreshCanvas().run();
                LOG.fine(() -> "Glue: merged clips '" + left.getName()
                        + "' and '" + right.getName() + "'");
                return;
            }
        }
    }

    /**
     * Handles a press with the {@link EditTool#COMP} tool. Alt+Click solos the
     * track's first take lane for auditioning (without retaining handler state);
     * a regular press begins a comp swipe on take lane 0 (the default lane).
     * The beat is clamped to {@code >= 0} to avoid negative-position exceptions.
     */
    private void handleCompPress(Track track, double beat, MouseEvent event) {
        var takeComping = track.getTakeComping();
        if (!takeComping.isActive()) {
            return;
        }
        double clampedBeat = Math.max(0.0, beat);
        if (event.isAltDown()) {
            // TODO: derive take-lane index from pointer Y once take lanes are
            //       hit-testable; for now, solo lane 0 as the default.
            var handler = new CompToolHandler(takeComping, deps.undoManager().get());
            handler.altClickLane(0);
            deps.refreshCanvas().run();
        } else {
            // TODO: derive take-lane index from pointer Y once take lanes are
            //       hit-testable; for now, swipe on lane 0 as the default.
            compToolHandler = new CompToolHandler(takeComping, deps.undoManager().get());
            compToolHandler.beginSwipe(0, clampedBeat);
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
                    deps.undoManager().get().execute(new FadeClipAction(
                            clip, clip.getFadeInBeats(), clip.getFadeOutBeats(),
                            newIn, newOut));
                    deps.refreshCanvas().run();
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

    // ── Clip body context menu (Story 042) ───────────────────────────────────

    /**
     * Shows the clip right-click context menu — currently exposes the
     * Time-Stretch and Pitch-Shift dialogs from Story 042. Additional clip
     * actions can be added here as new stories arrive.
     */
    private void showClipContextMenu(MouseEvent event) {
        ContextMenu menu = new ContextMenu();

        MenuItem timeStretch = new MenuItem("Time-Stretch\u2026");
        timeStretch.setOnAction(_ -> deps.onTimeStretchClip().run());
        MenuItem pitchShift = new MenuItem("Pitch-Shift\u2026");
        pitchShift.setOnAction(_ -> deps.onPitchShiftClip().run());

        menu.getItems().addAll(timeStretch, pitchShift);
        menu.show(canvas, event.getScreenX(), event.getScreenY());
    }

    // ── DragVisualAdvisor wiring (Story 197) ────────────────────────────────

    /**
     * Installs the shared {@link DragVisualAdvisor}. When set, every clip
     * drag in the arrangement view will consult the advisor so the
     * application's drag-feedback layer (ghost preview, drop-zone
     * highlight, snap indicator, modifier-key cursor) renders over the
     * gesture. Esc cancels the in-progress drag and restores the clip
     * to its original beat.
     *
     * @param advisor the shared advisor, or {@code null} to disable
     */
    void setDragVisualAdvisor(DragVisualAdvisor advisor) {
        this.dragVisualAdvisor = advisor;
    }

    /** Returns the advisor, primarily for tests. */
    DragVisualAdvisor getDragVisualAdvisor() {
        return dragVisualAdvisor;
    }

    /**
     * Cancels the clip drag in progress (Esc key handler). Restores the
     * dragged clip to its original beat and notifies the advisor so the
     * cancel-revert animation runs with the shared
     * {@link com.benesquivelmusic.daw.app.ui.drag.AnimationProfile}
     * timings. No-op when no clip drag is in progress.
     */
    void cancelClipDragInProgress() {
        if (dragClip == null) {
            return;
        }
        try {
            dragClip.setStartBeat(dragClipOriginalStartBeat);
        } catch (RuntimeException ignored) {
            // Restoration is best-effort; underlying model may have changed.
        }
        dragClip = null;
        dragSourceTrack = null;
        dragSourceTrackIndex = -1;
        groupDrag = false;
        dragSelectionStart = OptionalDouble.empty();
        dragSelectionEnd = OptionalDouble.empty();
        deps.refreshCanvas().run();

        if (dragVisualAdvisor != null
                && dragVisualAdvisor.state() == DragVisualAdvisor.State.DRAGGING) {
            try {
                DragVisualAdvisor.CancelRevert revert = dragVisualAdvisor.cancel();
                // Defer revertCompleted() until the cancel-revert animation
                // duration has elapsed so the presenter can play the slide-
                // back animation (Story 197). Uses PauseTransition which
                // fires on the FX Application Thread.
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(revert.duration().toMillis()));
                pause.setOnFinished(_ -> {
                    if (dragVisualAdvisor.state() == DragVisualAdvisor.State.REVERTING) {
                        dragVisualAdvisor.revertCompleted();
                    }
                });
                pause.play();
            } catch (RuntimeException ignored) {
                // never break the underlying drag
            }
        }
    }

    private void notifyAdvisorBeginClipDrag(AudioClip clip, double screenX, double screenY) {
        if (dragVisualAdvisor == null
                || dragVisualAdvisor.state() != DragVisualAdvisor.State.IDLE) {
            return;
        }
        try {
            String label = clip.getName();
            if (label == null || label.isEmpty()) {
                label = "clip";
            }
            dragVisualAdvisor.beginDrag(
                    DragSourceKind.CLIP, label,
                    screenX, screenY,
                    /* ghostWidth */ 80, /* ghostHeight */ 24);
        } catch (RuntimeException ignored) {
            // never break clip drag
        }
    }

    private void notifyAdvisorCommitClipDrag() {
        if (dragVisualAdvisor == null
                || dragVisualAdvisor.state() != DragVisualAdvisor.State.DRAGGING) {
            return;
        }
        try {
            dragVisualAdvisor.commit();
        } catch (RuntimeException ignored) {
            // never break clip drag
        }
    }

}
