package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AddClipAction;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
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
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.scene.Cursor;
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
    }

    private final ArrangementCanvas canvas;
    private final Host host;
    private final ClipTrimHandler trimHandler;

    // Drag state for pointer tool
    private AudioClip dragClip;
    private Track dragSourceTrack;
    private double dragStartBeat;

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
    }

    /**
     * Installs mouse event handlers on the arrangement canvas.
     */
    void install() {
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);
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

        // ── Normal clip interaction (primary button only) ───────────────────
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
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

        if (trackIndex < 0) {
            if (host.activeTool() == EditTool.POINTER) {
                host.seekToPosition(beat);
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

        if (trimHandler.isTrimming()) {
            int trackIndex = trackIndexAt(event.getY());
            // updateTrim applies the trim and computes the clamped preview beat
            // but does not trigger a redraw — we do a single refresh below.
            trimHandler.updateTrim(event.getX(), trackIndex);
            canvas.setTrimPreview(trimHandler.getPreviewBeat(), trimHandler.getPreviewTrackIndex());
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

        if (trimHandler.isTrimming()) {
            // Clear the visual trim preview before completing the trim so that
            // any refresh triggered inside completeTrim() does not render a
            // stale cyan preview line.
            canvas.setTrimPreview(-1.0, -1);
            trimHandler.completeTrim(event.getX());
            updateCursor();
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
            ClipTrimHandler.EdgeHit hit = trimHandler.hitTestEdge(event.getX(), event.getY());
            if (hit != null) {
                canvas.setCursor(Cursor.H_RESIZE);
                return;
            }
        }
        updateCursor();
    }

    // ── Tool-specific handlers ───────────────────────────────────────────────

    private void handlePointerPress(Track track, double beat, MouseEvent event) {
        AudioClip clip = clipAt(track, beat);
        if (clip == null) {
            host.seekToPosition(beat);
            return;
        }
        dragClip = clip;
        dragSourceTrack = track;
        dragStartBeat = beat;
        LOG.fine(() -> "Pointer: selected clip '" + clip.getName() + "' at beat " + beat);
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

}
