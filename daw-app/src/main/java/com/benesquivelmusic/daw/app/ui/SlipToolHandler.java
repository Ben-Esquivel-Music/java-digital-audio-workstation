package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.project.edit.SlipEditService;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Handles interactive slip-edit gestures ({@code Ctrl+Alt}-drag) in the
 * arrangement view.
 *
 * <p>Slip editing slides the <em>content</em> inside a clip's fixed timeline
 * boundaries. For an {@link AudioClip} this shifts the clip's
 * {@code sourceOffsetBeats}; for a {@link MidiClip} it shifts every note's
 * {@code startColumn} by the same amount. The clip's on-timeline position
 * and duration never change, so neighbouring clips are untouched.</p>
 *
 * <p>The handler itself is transient preview state — it does not mutate the
 * clip during drag. On mouse-release it computes the clamped delta via
 * {@link SlipEditService} and executes the resulting undoable action.</p>
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
final class SlipToolHandler {

    private static final Logger LOG = Logger.getLogger(SlipToolHandler.class.getName());

    /**
     * Callback interface for obtaining arrangement state and triggering
     * canvas updates during a slip drag.
     */
    interface Host {
        double pixelsPerBeat();
        UndoManager undoManager();
        double projectTempoBpm();
        void refreshCanvas();
        void showNotification(NotificationLevel level, String message);
        /**
         * Updates the canvas slip-preview overlay with the in-progress beat
         * delta for the given clip. A clip of {@code null} clears the
         * preview. The {@code hitEdge} flag triggers a visual flash.
         */
        void setSlipPreview(AudioClip audioClip, MidiClip midiClip,
                            double appliedBeatDelta, boolean hitEdge);
    }

    /** Tags the kind of clip being slipped. */
    private enum Kind { AUDIO, MIDI }

    private final Host host;

    // ── Active drag state ────────────────────────────────────────────────────

    private Kind kind;
    private AudioClip audioClip;
    private MidiClip midiClip;
    private double anchorX;

    SlipToolHandler(Host host) {
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    /** Returns {@code true} if a slip drag is currently in progress. */
    boolean isSlipping() {
        return kind != null;
    }

    /** Begins a slip drag on an audio clip from the given anchor pixel. */
    void beginAudioSlip(AudioClip clip, double anchorX) {
        Objects.requireNonNull(clip, "clip must not be null");
        this.kind = Kind.AUDIO;
        this.audioClip = clip;
        this.midiClip = null;
        this.anchorX = anchorX;
        host.setSlipPreview(clip, null, 0.0, false);
        LOG.fine(() -> "Begin audio slip on '" + clip.getName() + "' at x=" + anchorX);
    }

    /** Begins a slip drag on a MIDI clip from the given anchor pixel. */
    void beginMidiSlip(MidiClip clip, double anchorX) {
        Objects.requireNonNull(clip, "clip must not be null");
        this.kind = Kind.MIDI;
        this.audioClip = null;
        this.midiClip = clip;
        this.anchorX = anchorX;
        host.setSlipPreview(null, clip, 0.0, false);
        LOG.fine(() -> "Begin MIDI slip at x=" + anchorX);
    }

    /**
     * Updates the slip preview during a drag. Computes the requested beat
     * delta from the pixel delta, clamps it via {@link SlipEditService},
     * and forwards the preview to the canvas.
     */
    void updateSlip(double currentX) {
        if (kind == null) {
            return;
        }
        double beatDelta = (currentX - anchorX) / host.pixelsPerBeat();
        // Slip convention: dragging content RIGHT on the timeline should make
        // the clip's source offset DECREASE (content appears later in the
        // window, so window start must move backwards through the source).
        double requestedOffsetDelta = -beatDelta;

        switch (kind) {
            case AUDIO -> {
                double sourceLengthBeats = sourceLengthBeatsFor(audioClip);
                SlipEditService.SlipResult result = SlipEditService.buildAudioSlip(
                        audioClip, requestedOffsetDelta, sourceLengthBeats);
                // Preview delta is expressed as the visible (content) direction:
                // a positive beatDelta means the content has been slid right,
                // equivalent to a negative source-offset delta.
                host.setSlipPreview(audioClip, null, -result.appliedBeatDelta(), result.hitEdge());
            }
            case MIDI -> {
                int requestedColumns = columnsForBeatDelta(beatDelta);
                SlipEditService.SlipResult result = SlipEditService.buildMidiSlip(
                        midiClip, requestedColumns);
                host.setSlipPreview(null, midiClip, result.appliedBeatDelta(), result.hitEdge());
            }
        }
        host.refreshCanvas();
    }

    /**
     * Completes the slip drag, computing the final clamped delta and
     * executing the resulting undoable action.
     */
    void completeSlip(double currentX) {
        if (kind == null) {
            return;
        }
        double beatDelta = (currentX - anchorX) / host.pixelsPerBeat();

        switch (kind) {
            case AUDIO -> {
                double sourceLengthBeats = sourceLengthBeatsFor(audioClip);
                SlipEditService.SlipResult result = SlipEditService.buildAudioSlip(
                        audioClip, -beatDelta, sourceLengthBeats);
                if (result.hasAction()) {
                    host.undoManager().execute(result.action());
                    LOG.fine(() -> "Completed audio slip on '" + audioClip.getName()
                            + "' by " + result.appliedBeatDelta() + " beats");
                }
                if (result.hitEdge()) {
                    host.showNotification(NotificationLevel.INFO,
                            "Slip clamped at source-window edge");
                }
            }
            case MIDI -> {
                int requestedColumns = columnsForBeatDelta(beatDelta);
                SlipEditService.SlipResult result = SlipEditService.buildMidiSlip(
                        midiClip, requestedColumns);
                if (result.hasAction()) {
                    host.undoManager().execute(result.action());
                    LOG.fine(() -> "Completed MIDI slip by "
                            + result.appliedBeatDelta() + " beats");
                }
                if (result.hitEdge()) {
                    host.showNotification(NotificationLevel.INFO,
                            "Slip clamped — notes cannot cross column 0");
                }
            }
        }

        clearState();
        host.setSlipPreview(null, null, 0.0, false);
        host.refreshCanvas();
    }

    /** Cancels an in-progress slip without mutating the model. */
    void cancelSlip() {
        if (kind != null) {
            clearState();
            host.setSlipPreview(null, null, 0.0, false);
            host.refreshCanvas();
        }
    }

    private void clearState() {
        kind = null;
        audioClip = null;
        midiClip = null;
        anchorX = 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts a beat-delta to a column count using the editor's
     * {@code BEATS_PER_COLUMN} quantum (0.25 beats per 1/16th column).
     */
    private static int columnsForBeatDelta(double beatDelta) {
        return (int) Math.round(beatDelta / EditorView.BEATS_PER_COLUMN);
    }

    /**
     * Computes the audio clip's total source length in beats from its native
     * rate metadata and the project tempo. Returns {@code 0.0} when the
     * length is unknown (no in-memory data and no metadata) so
     * {@link SlipEditService#buildAudioSlip} treats the upper bound as
     * unbounded.
     */
    private double sourceLengthBeatsFor(AudioClip clip) {
        double bpm = host.projectTempoBpm();
        if (bpm <= 0) {
            return 0.0;
        }
        SourceRateMetadata meta = clip.getSourceRateMetadata();
        if (meta != null && meta.framesPerChannel() > 0 && meta.nativeRateHz() > 0) {
            double seconds = (double) meta.framesPerChannel() / meta.nativeRateHz();
            return seconds * (bpm / 60.0);
        }
        float[][] data = clip.getAudioData();
        if (data != null && data.length > 0 && data[0] != null && data[0].length > 0) {
            // When no rate metadata is present, fall back to the session
            // sample rate — the render pipeline treats native-rate-absent as
            // session-rate, so this matches the playback assumption.
            return 0.0; // conservative: treat as unbounded when rate unknown
        }
        return 0.0;
    }
}
