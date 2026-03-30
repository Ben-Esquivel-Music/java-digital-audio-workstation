package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.ClipEdgeTrimAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Handles interactive clip edge trim gestures in the arrangement view.
 *
 * <p>When the Pointer tool is active and the mouse hovers within a few pixels
 * of a clip's left or right edge, the handler activates trim mode. Dragging
 * the left edge adjusts {@code startBeat} and {@code sourceOffsetBeats}
 * simultaneously, while dragging the right edge adjusts
 * {@code durationBeats}.</p>
 *
 * <p>The handler respects snap-to-grid settings, prevents trimming beyond the
 * source audio boundary, and registers an {@link TrimClipAction} when the drag
 * completes.</p>
 */
final class ClipTrimHandler {

    private static final Logger LOG = Logger.getLogger(ClipTrimHandler.class.getName());

    /** Number of pixels from a clip edge that triggers trim mode. */
    static final double EDGE_THRESHOLD_PIXELS = 6.0;

    /** Minimum allowed clip duration in beats after trimming. */
    static final double MIN_CLIP_DURATION_BEATS = 0.0625;

    /**
     * Identifies which edge of a clip is being trimmed.
     */
    enum TrimEdge {
        LEFT,
        RIGHT
    }

    /**
     * Callback interface for obtaining arrangement state and triggering
     * canvas updates.
     */
    interface Host {
        double pixelsPerBeat();
        double scrollXBeats();
        double scrollYPixels();
        double trackHeight();
        java.util.List<Track> tracks();
        UndoManager undoManager();
        boolean snapEnabled();
        GridResolution gridResolution();
        int beatsPerBar();
        void refreshCanvas();
    }

    private final Host host;

    // ── Drag state ───────────────────────────────────────────────────────────

    private AudioClip trimClip;
    private TrimEdge trimEdge;
    private double originalStartBeat;
    private double originalDurationBeats;
    private double originalSourceOffsetBeats;

    // ── Preview state ────────────────────────────────────────────────────────

    private double previewBeat = -1.0;
    private int previewTrackIndex = -1;

    ClipTrimHandler(Host host) {
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    // ── Edge detection ───────────────────────────────────────────────────────

    /**
     * Detects whether the given pixel position is over a clip edge that can
     * be trimmed. Returns the detected edge, or {@code null} if the position
     * is not within trim threshold of any clip edge.
     *
     * @param x the mouse X coordinate in canvas pixels
     * @param y the mouse Y coordinate in canvas pixels
     * @return the detected {@link TrimEdge}, or {@code null}
     */
    TrimEdge detectEdge(double x, double y) {
        int trackIndex = trackIndexAt(y);
        if (trackIndex < 0) {
            return null;
        }
        Track track = host.tracks().get(trackIndex);
        double beat = beatAt(x);

        for (AudioClip clip : track.getClips()) {
            double leftEdgeX = (clip.getStartBeat() - host.scrollXBeats()) * host.pixelsPerBeat();
            double rightEdgeX = (clip.getEndBeat() - host.scrollXBeats()) * host.pixelsPerBeat();

            if (Math.abs(x - leftEdgeX) <= EDGE_THRESHOLD_PIXELS
                    && beat >= clip.getStartBeat() - beatThreshold()
                    && beat <= clip.getEndBeat()) {
                return TrimEdge.LEFT;
            }
            if (Math.abs(x - rightEdgeX) <= EDGE_THRESHOLD_PIXELS
                    && beat >= clip.getStartBeat()
                    && beat <= clip.getEndBeat() + beatThreshold()) {
                return TrimEdge.RIGHT;
            }
        }
        return null;
    }

    /**
     * Finds the clip whose edge is near the given pixel position.
     *
     * @param x the mouse X coordinate in canvas pixels
     * @param y the mouse Y coordinate in canvas pixels
     * @return the clip at the detected edge, or {@code null}
     */
    AudioClip clipAtEdge(double x, double y) {
        int trackIndex = trackIndexAt(y);
        if (trackIndex < 0) {
            return null;
        }
        Track track = host.tracks().get(trackIndex);
        double beat = beatAt(x);

        for (AudioClip clip : track.getClips()) {
            double leftEdgeX = (clip.getStartBeat() - host.scrollXBeats()) * host.pixelsPerBeat();
            double rightEdgeX = (clip.getEndBeat() - host.scrollXBeats()) * host.pixelsPerBeat();

            if (Math.abs(x - leftEdgeX) <= EDGE_THRESHOLD_PIXELS
                    && beat >= clip.getStartBeat() - beatThreshold()
                    && beat <= clip.getEndBeat()) {
                return clip;
            }
            if (Math.abs(x - rightEdgeX) <= EDGE_THRESHOLD_PIXELS
                    && beat >= clip.getStartBeat()
                    && beat <= clip.getEndBeat() + beatThreshold()) {
                return clip;
            }
        }
        return null;
    }

    // ── Drag lifecycle ───────────────────────────────────────────────────────

    /**
     * Begins a trim drag on the given clip edge.
     *
     * @param clip the clip being trimmed
     * @param edge the edge to drag
     */
    void beginTrim(AudioClip clip, TrimEdge edge) {
        Objects.requireNonNull(clip, "clip must not be null");
        Objects.requireNonNull(edge, "edge must not be null");
        this.trimClip = clip;
        this.trimEdge = edge;
        this.originalStartBeat = clip.getStartBeat();
        this.originalDurationBeats = clip.getDurationBeats();
        this.originalSourceOffsetBeats = clip.getSourceOffsetBeats();
        LOG.fine(() -> "Begin trim: " + edge + " edge of '" + clip.getName() + "'");
    }

    /**
     * Updates the trim preview during a drag. Applies the trim in real time
     * to give visual feedback, but does not register an undo action yet.
     *
     * @param x          the current mouse X coordinate in canvas pixels
     * @param trackIndex the track index for preview rendering
     */
    void updateTrim(double x, int trackIndex) {
        if (trimClip == null) {
            return;
        }
        double beat = beatAt(x);
        if (host.snapEnabled()) {
            beat = SnapQuantizer.quantize(beat, host.gridResolution(), host.beatsPerBar());
        }

        applyTrim(beat);

        // Update preview state
        this.previewBeat = beat;
        this.previewTrackIndex = trackIndex;
        host.refreshCanvas();
    }

    /**
     * Completes the trim drag and registers an undoable action if the clip
     * state actually changed.
     *
     * @param x the final mouse X coordinate in canvas pixels
     */
    void completeTrim(double x) {
        if (trimClip == null) {
            return;
        }

        double beat = beatAt(x);
        if (host.snapEnabled()) {
            beat = SnapQuantizer.quantize(beat, host.gridResolution(), host.beatsPerBar());
        }

        // Reset to original state before executing the undoable action
        trimClip.setStartBeat(originalStartBeat);
        trimClip.setDurationBeats(originalDurationBeats);
        trimClip.setSourceOffsetBeats(originalSourceOffsetBeats);

        // Compute the final trim values
        double newStartBeat;
        double newDurationBeats;
        double newSourceOffsetBeats;

        if (trimEdge == TrimEdge.LEFT) {
            double clampedBeat = clampLeftEdge(beat);
            double delta = clampedBeat - originalStartBeat;
            newStartBeat = clampedBeat;
            newDurationBeats = originalDurationBeats - delta;
            newSourceOffsetBeats = originalSourceOffsetBeats + delta;
        } else {
            double clampedBeat = clampRightEdge(beat);
            newStartBeat = originalStartBeat;
            newDurationBeats = clampedBeat - originalStartBeat;
            newSourceOffsetBeats = originalSourceOffsetBeats;
        }

        // Only register an action if the trim actually changed something
        boolean startChanged = Math.abs(newStartBeat - originalStartBeat) > 0.001;
        boolean durationChanged = Math.abs(newDurationBeats - originalDurationBeats) > 0.001;
        if (startChanged || durationChanged) {
            host.undoManager().execute(new ClipEdgeTrimAction(
                    trimClip, newStartBeat, newDurationBeats, newSourceOffsetBeats));
            LOG.fine(() -> "Completed trim: " + trimEdge + " edge of '" + trimClip.getName()
                    + "' start=" + newStartBeat + " duration=" + newDurationBeats);
        }

        clearState();
        host.refreshCanvas();
    }

    /**
     * Cancels an in-progress trim, restoring the clip to its original state.
     */
    void cancelTrim() {
        if (trimClip != null) {
            trimClip.setStartBeat(originalStartBeat);
            trimClip.setDurationBeats(originalDurationBeats);
            trimClip.setSourceOffsetBeats(originalSourceOffsetBeats);
            clearState();
            host.refreshCanvas();
        }
    }

    /**
     * Returns {@code true} if a trim drag is currently in progress.
     */
    boolean isTrimming() {
        return trimClip != null;
    }

    /**
     * Returns the current preview beat position, or a negative value if
     * no preview is active.
     */
    double getPreviewBeat() {
        return previewBeat;
    }

    /**
     * Returns the current preview track index, or {@code -1} if no
     * preview is active.
     */
    int getPreviewTrackIndex() {
        return previewTrackIndex;
    }

    /**
     * Returns the edge currently being trimmed, or {@code null} if no
     * trim is in progress.
     */
    TrimEdge getActiveEdge() {
        return trimEdge;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void applyTrim(double beat) {
        if (trimEdge == TrimEdge.LEFT) {
            double clampedBeat = clampLeftEdge(beat);
            double delta = clampedBeat - originalStartBeat;
            trimClip.setSourceOffsetBeats(originalSourceOffsetBeats + delta);
            trimClip.setStartBeat(clampedBeat);
            trimClip.setDurationBeats(originalDurationBeats - delta);
        } else {
            double clampedBeat = clampRightEdge(beat);
            trimClip.setDurationBeats(clampedBeat - originalStartBeat);
        }
    }

    /**
     * Clamps a left-edge position so the clip cannot be trimmed shorter
     * than {@link #MIN_CLIP_DURATION_BEATS}, cannot start before beat 0,
     * and cannot extend the source offset below 0 (i.e. before the audio
     * source boundary).
     */
    private double clampLeftEdge(double beat) {
        double originalEndBeat = originalStartBeat + originalDurationBeats;
        // Cannot exceed the right edge minus minimum duration
        double maxBeat = originalEndBeat - MIN_CLIP_DURATION_BEATS;
        // Cannot extend before the source boundary
        double minBeat = originalStartBeat - originalSourceOffsetBeats;
        // Cannot go below 0
        minBeat = Math.max(0.0, minBeat);
        return Math.max(minBeat, Math.min(beat, maxBeat));
    }

    /**
     * Clamps a right-edge position so the clip cannot be trimmed shorter
     * than {@link #MIN_CLIP_DURATION_BEATS} and cannot extend beyond the
     * source audio boundary (original end + remaining source length).
     */
    private double clampRightEdge(double beat) {
        // Cannot be shorter than minimum duration
        double minBeat = originalStartBeat + MIN_CLIP_DURATION_BEATS;
        // Cannot extend beyond where the original end was (the source boundary).
        // The original end is the maximum right-edge position since trimTo
        // only allows shrinking.
        double maxBeat = originalStartBeat + originalDurationBeats;
        return Math.max(minBeat, Math.min(beat, maxBeat));
    }

    private void clearState() {
        trimClip = null;
        trimEdge = null;
        previewBeat = -1.0;
        previewTrackIndex = -1;
    }

    private int trackIndexAt(double y) {
        double adjustedY = y + host.scrollYPixels();
        int index = (int) Math.floor(adjustedY / host.trackHeight());
        if (index < 0 || index >= host.tracks().size()) {
            return -1;
        }
        return index;
    }

    private double beatAt(double x) {
        return x / host.pixelsPerBeat() + host.scrollXBeats();
    }

    /**
     * Returns the edge detection threshold in beats (derived from the pixel
     * threshold and the current zoom level).
     */
    private double beatThreshold() {
        return EDGE_THRESHOLD_PIXELS / host.pixelsPerBeat();
    }
}
