package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.FadeClipAction;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Handles interactive clip fade handle gestures in the arrangement view.
 *
 * <p>When the Pointer tool is active and the mouse hovers over a fade handle
 * indicator at the top-left (fade-in) or top-right (fade-out) corner of a
 * clip, the handler activates fade drag mode. Dragging the handle inward
 * adjusts {@code fadeInBeats} or {@code fadeOutBeats} on the clip in real
 * time.</p>
 *
 * <p>The handler respects snap-to-grid settings, prevents the fade-in handle
 * from crossing the fade-out handle and vice versa, and registers a
 * {@link FadeClipAction} when the drag completes.</p>
 */
final class ClipFadeHandler {

    private static final Logger LOG = Logger.getLogger(ClipFadeHandler.class.getName());

    /** Size of the fade handle hit-test area in pixels. */
    static final double HANDLE_SIZE_PIXELS = 10.0;

    /**
     * Identifies which fade handle is being dragged.
     */
    enum FadeHandle {
        FADE_IN,
        FADE_OUT
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

        /**
         * Resolves the track index at the given Y pixel.
         */
        default int trackIndexAtY(double y) {
            double adjustedY = y + scrollYPixels();
            int index = (int) Math.floor(adjustedY / trackHeight());
            if (index < 0 || index >= tracks().size()) {
                return -1;
            }
            return index;
        }

        /**
         * Returns the Y pixel offset for the given track index, accounting
         * for expanded automation lanes. The default implementation assumes
         * uniform {@code trackHeight()} spacing; hosts that support
         * automation lanes should override this.
         */
        default double laneYForTrack(int trackIndex) {
            double y = 0;
            for (int i = 0; i < trackIndex; i++) {
                y += trackHeight();
            }
            return y - scrollYPixels();
        }
    }

    private final Host host;

    // ── Drag state ───────────────────────────────────────────────────────────

    private AudioClip fadeClip;
    private FadeHandle fadeHandle;
    private double originalFadeInBeats;
    private double originalFadeOutBeats;
    private FadeCurveType originalFadeInCurveType;
    private FadeCurveType originalFadeOutCurveType;

    ClipFadeHandler(Host host) {
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    /**
     * Result of a fade handle hit test, pairing the detected clip with the
     * handle.
     */
    record HandleHit(AudioClip clip, FadeHandle handle) {}

    // ── Handle detection ─────────────────────────────────────────────────────

    /** Clip inset from lane edge — must match ArrangementCanvas.CLIP_INSET. */
    private static final double CLIP_INSET = 2.0;

    /**
     * Hit-tests the given pixel position against all fade handles on the
     * resolved track. Returns a {@link HandleHit} if the position is within
     * the fade handle area, or {@code null} otherwise.
     *
     * @param x the mouse X coordinate in canvas pixels
     * @param y the mouse Y coordinate in canvas pixels
     * @return a {@link HandleHit}, or {@code null}
     */
    HandleHit hitTestHandle(double x, double y) {
        int trackIndex = trackIndexAt(y);
        if (trackIndex < 0) {
            return null;
        }
        Track track = host.tracks().get(trackIndex);

        double laneY = host.laneYForTrack(trackIndex);
        double clipTopY = laneY + CLIP_INSET;

        for (AudioClip clip : track.getClips()) {
            double clipX = (clip.getStartBeat() - host.scrollXBeats()) * host.pixelsPerBeat();
            double clipWidth = clip.getDurationBeats() * host.pixelsPerBeat();

            // Fade-in handle: top-left corner
            double fadeInWidth = clip.getFadeInBeats() * host.pixelsPerBeat();
            double handleX = clipX + fadeInWidth;
            if (x >= handleX - HANDLE_SIZE_PIXELS / 2.0
                    && x <= handleX + HANDLE_SIZE_PIXELS / 2.0
                    && y >= clipTopY
                    && y <= clipTopY + HANDLE_SIZE_PIXELS) {
                return new HandleHit(clip, FadeHandle.FADE_IN);
            }

            // Fade-out handle: top-right corner
            double fadeOutWidth = clip.getFadeOutBeats() * host.pixelsPerBeat();
            double handleOutX = clipX + clipWidth - fadeOutWidth;
            if (x >= handleOutX - HANDLE_SIZE_PIXELS / 2.0
                    && x <= handleOutX + HANDLE_SIZE_PIXELS / 2.0
                    && y >= clipTopY
                    && y <= clipTopY + HANDLE_SIZE_PIXELS) {
                return new HandleHit(clip, FadeHandle.FADE_OUT);
            }
        }
        return null;
    }

    // ── Drag lifecycle ───────────────────────────────────────────────────────

    /**
     * Begins a fade handle drag on the given clip.
     *
     * @param clip   the clip being modified
     * @param handle the fade handle to drag
     */
    void beginFade(AudioClip clip, FadeHandle handle) {
        Objects.requireNonNull(clip, "clip must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        this.fadeClip = clip;
        this.fadeHandle = handle;
        this.originalFadeInBeats = clip.getFadeInBeats();
        this.originalFadeOutBeats = clip.getFadeOutBeats();
        this.originalFadeInCurveType = clip.getFadeInCurveType();
        this.originalFadeOutCurveType = clip.getFadeOutCurveType();
        LOG.fine(() -> "Begin fade drag: " + handle + " on '" + clip.getName() + "'");
    }

    /**
     * Updates the fade during a drag. Applies the fade in real time to give
     * visual feedback, but does not register an undo action yet.
     *
     * @param x the current mouse X coordinate in canvas pixels
     */
    void updateFade(double x) {
        if (fadeClip == null) {
            return;
        }
        double beat = beatAt(x);
        if (host.snapEnabled()) {
            beat = SnapQuantizer.quantize(beat, host.gridResolution(), host.beatsPerBar());
        }
        applyFade(beat);
    }

    /**
     * Completes the fade drag and registers an undoable action if the fade
     * state actually changed.
     *
     * @param x the final mouse X coordinate in canvas pixels
     */
    void completeFade(double x) {
        if (fadeClip == null) {
            return;
        }

        double beat = beatAt(x);
        if (host.snapEnabled()) {
            beat = SnapQuantizer.quantize(beat, host.gridResolution(), host.beatsPerBar());
        }

        // Compute the new fade values from the final mouse position
        double newFadeIn;
        double newFadeOut;
        if (fadeHandle == FadeHandle.FADE_IN) {
            newFadeIn = clampFadeIn(beat - fadeClip.getStartBeat());
            newFadeOut = fadeClip.getFadeOutBeats();
        } else {
            newFadeIn = fadeClip.getFadeInBeats();
            newFadeOut = clampFadeOut(fadeClip.getEndBeat() - beat);
        }

        // Reset to original state so the undoable action captures correct before/after
        fadeClip.setFadeInBeats(originalFadeInBeats);
        fadeClip.setFadeOutBeats(originalFadeOutBeats);
        fadeClip.setFadeInCurveType(originalFadeInCurveType);
        fadeClip.setFadeOutCurveType(originalFadeOutCurveType);

        // Only register an action if the fade actually changed
        boolean fadeInChanged = Math.abs(newFadeIn - originalFadeInBeats) > 0.001;
        boolean fadeOutChanged = Math.abs(newFadeOut - originalFadeOutBeats) > 0.001;
        if (fadeInChanged || fadeOutChanged) {
            host.undoManager().execute(new FadeClipAction(
                    fadeClip, newFadeIn, newFadeOut,
                    fadeClip.getFadeInCurveType(), fadeClip.getFadeOutCurveType()));
            LOG.fine(() -> "Completed fade drag: " + fadeHandle + " on '" + fadeClip.getName()
                    + "' fadeIn=" + newFadeIn + " fadeOut=" + newFadeOut);
        }

        clearState();
        host.refreshCanvas();
    }

    /**
     * Cancels an in-progress fade drag, restoring the clip to its original
     * fade state.
     */
    void cancelFade() {
        if (fadeClip != null) {
            fadeClip.setFadeInBeats(originalFadeInBeats);
            fadeClip.setFadeOutBeats(originalFadeOutBeats);
            fadeClip.setFadeInCurveType(originalFadeInCurveType);
            fadeClip.setFadeOutCurveType(originalFadeOutCurveType);
            clearState();
            host.refreshCanvas();
        }
    }

    /**
     * Returns {@code true} if a fade drag is currently in progress.
     */
    boolean isFading() {
        return fadeClip != null;
    }

    /**
     * Returns the handle currently being dragged, or {@code null} if no
     * fade drag is in progress.
     */
    FadeHandle getActiveHandle() {
        return fadeHandle;
    }

    /**
     * Returns the clip currently being faded, or {@code null} if no fade
     * drag is in progress.
     */
    AudioClip getFadeClip() {
        return fadeClip;
    }

    /**
     * Returns a tooltip string describing the fade at the given handle hit.
     *
     * @param hit the handle hit to describe
     * @return a human-readable tooltip string
     */
    static String tooltipFor(HandleHit hit) {
        Objects.requireNonNull(hit, "hit must not be null");
        AudioClip clip = hit.clip();
        if (hit.handle() == FadeHandle.FADE_IN) {
            return String.format("Fade In: %.2f beats (%s)",
                    clip.getFadeInBeats(), curveLabel(clip.getFadeInCurveType()));
        } else {
            return String.format("Fade Out: %.2f beats (%s)",
                    clip.getFadeOutBeats(), curveLabel(clip.getFadeOutCurveType()));
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void applyFade(double beat) {
        if (fadeHandle == FadeHandle.FADE_IN) {
            double fadeInBeats = clampFadeIn(beat - fadeClip.getStartBeat());
            fadeClip.setFadeInBeats(fadeInBeats);
        } else {
            double fadeOutBeats = clampFadeOut(fadeClip.getEndBeat() - beat);
            fadeClip.setFadeOutBeats(fadeOutBeats);
        }
    }

    /**
     * Clamps the fade-in duration so it does not go negative and does not
     * cross the fade-out boundary.
     */
    private double clampFadeIn(double fadeInBeats) {
        fadeInBeats = Math.max(0.0, fadeInBeats);
        // Prevent crossing: fade-in + fade-out cannot exceed clip duration
        double maxFadeIn = fadeClip.getDurationBeats() - fadeClip.getFadeOutBeats();
        return Math.min(fadeInBeats, Math.max(0.0, maxFadeIn));
    }

    /**
     * Clamps the fade-out duration so it does not go negative and does not
     * cross the fade-in boundary.
     */
    private double clampFadeOut(double fadeOutBeats) {
        fadeOutBeats = Math.max(0.0, fadeOutBeats);
        // Prevent crossing: fade-in + fade-out cannot exceed clip duration
        double maxFadeOut = fadeClip.getDurationBeats() - fadeClip.getFadeInBeats();
        return Math.min(fadeOutBeats, Math.max(0.0, maxFadeOut));
    }

    private void clearState() {
        fadeClip = null;
        fadeHandle = null;
    }

    private int trackIndexAt(double y) {
        return host.trackIndexAtY(y);
    }

    private double beatAt(double x) {
        return x / host.pixelsPerBeat() + host.scrollXBeats();
    }

    private static String curveLabel(FadeCurveType curveType) {
        return switch (curveType) {
            case LINEAR -> "Linear";
            case EQUAL_POWER -> "Equal Power";
            case S_CURVE -> "S-Curve";
        };
    }
}
