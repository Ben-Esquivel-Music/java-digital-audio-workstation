package com.benesquivelmusic.daw.core.project.edit;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.SlipClipAction;
import com.benesquivelmusic.daw.core.clip.LockedClipException;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SlipMidiClipAction;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * Computes clamped slip deltas and wraps them in undoable actions.
 *
 * <p>Slip editing slides the <em>content</em> inside a clip's fixed timeline
 * boundaries — for an {@link AudioClip} this shifts
 * {@link AudioClip#getSourceOffsetBeats() sourceOffsetBeats}; for a
 * {@link MidiClip} it shifts every note's {@code startColumn}. The
 * timeline start/end of the clip itself is unchanged, so slip never
 * disturbs neighbouring clips.</p>
 *
 * <p>This service is responsible for <em>clamping</em> the requested delta
 * so the result stays within a valid window — for audio, the source offset
 * must remain within {@code [0, sourceLengthBeats - durationBeats]}; for
 * MIDI, no note may end up at a negative start column. When the requested
 * delta is clamped at either edge, {@link SlipResult#hitEdge()} returns
 * {@code true} so the UI can surface a visual cue.</p>
 *
 * <p>This class is stateless — every method is a static utility.</p>
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
public final class SlipEditService {

    /** Minimum meaningful beat-difference — smaller deltas are treated as zero. */
    private static final double EPSILON = 1e-6;

    private SlipEditService() { /* utility class */ }

    /**
     * The outcome of a slip build: the clamped delta actually applied, the
     * {@link UndoableAction} that performs it (or {@code null} when the
     * clamped delta is effectively zero), and a flag indicating whether
     * clamping actually cut the requested delta short (so the UI can
     * briefly flash the "hit edge" cue).
     *
     * @param action            the undoable slip action, or {@code null} if the
     *                          clamped delta is zero
     * @param appliedBeatDelta  the beat delta that was actually applied after
     *                          clamping
     * @param hitEdge           {@code true} if the request was clamped at the
     *                          start or end of the source window
     */
    public record SlipResult(UndoableAction action, double appliedBeatDelta, boolean hitEdge) {

        /** Convenience — whether a non-trivial action was produced. */
        public boolean hasAction() {
            return action != null;
        }
    }

    /**
     * Builds a slip action for an audio clip, clamping the requested beat
     * delta to the source-window bounds.
     *
     * <p>The valid source-offset range is
     * {@code [0, sourceLengthBeats - durationBeats]}. When
     * {@code sourceLengthBeats <= 0} (e.g. no audio data loaded), the upper
     * bound is treated as unbounded — only the lower bound (≥ 0) is enforced.</p>
     *
     * @param clip                the audio clip to slip
     * @param requestedBeatDelta  the requested delta applied to
     *                            {@code sourceOffsetBeats}; positive moves the
     *                            clip window later in the source (content
     *                            appears earlier on the timeline), negative
     *                            moves it earlier (content appears later)
     * @param sourceLengthBeats   the total length of the source audio in beats,
     *                            or {@code 0.0} / negative if unknown/unbounded
     * @return the slip result
     * @throws NullPointerException if {@code clip} is {@code null}
     */
    public static SlipResult buildAudioSlip(AudioClip clip,
                                            double requestedBeatDelta,
                                            double sourceLengthBeats) {
        Objects.requireNonNull(clip, "clip must not be null");
        LockedClipException.requireUnlocked("Slip", clip);

        double currentOffset = clip.getSourceOffsetBeats();
        double requestedOffset = currentOffset + requestedBeatDelta;

        double minOffset = 0.0;
        boolean upperBounded = sourceLengthBeats > clip.getDurationBeats();
        double maxOffset = upperBounded
                ? sourceLengthBeats - clip.getDurationBeats()
                : Double.POSITIVE_INFINITY;

        double clampedOffset = Math.max(minOffset, Math.min(requestedOffset, maxOffset));
        double appliedDelta = clampedOffset - currentOffset;
        boolean hitEdge = Math.abs(clampedOffset - requestedOffset) > EPSILON;

        if (Math.abs(appliedDelta) <= EPSILON) {
            return new SlipResult(null, 0.0, hitEdge);
        }
        return new SlipResult(new SlipClipAction(clip, clampedOffset), appliedDelta, hitEdge);
    }

    /**
     * Builds a slip action for a MIDI clip, clamping the requested column
     * delta so that no note ends up at a negative start column.
     *
     * <p>Negative deltas (slide notes earlier on the timeline) are clamped so
     * the earliest note lands on column 0. Positive deltas have no upper
     * bound — MIDI clips carry no fixed "source length" analogue, so the user
     * is free to slide notes arbitrarily far to the right.</p>
     *
     * @param clip                   the MIDI clip to slip
     * @param requestedColumnDelta   the requested column delta applied to every
     *                               note's {@code startColumn}; may be positive
     *                               or negative
     * @return the slip result (with the beat-delta equivalent of the clamped
     *         column delta)
     * @throws NullPointerException if {@code clip} is {@code null}
     */
    public static SlipResult buildMidiSlip(MidiClip clip, int requestedColumnDelta) {
        Objects.requireNonNull(clip, "clip must not be null");
        LockedClipException.requireUnlocked("Slip", clip);

        if (clip.isEmpty() || requestedColumnDelta == 0) {
            return new SlipResult(null, 0.0, false);
        }

        int minStart = Integer.MAX_VALUE;
        for (MidiNoteData note : clip.getNotes()) {
            if (note.startColumn() < minStart) {
                minStart = note.startColumn();
            }
        }

        // The most-negative delta allowed is -minStart; positive deltas are unbounded.
        int clampedDelta = requestedColumnDelta < 0
                ? Math.max(requestedColumnDelta, -minStart)
                : requestedColumnDelta;
        boolean hitEdge = clampedDelta != requestedColumnDelta;

        if (clampedDelta == 0) {
            return new SlipResult(null, 0.0, hitEdge);
        }
        // 0.25 beats per column (1/16th at 4/4) — same constant used by the UI
        // layer (see {@code EditorView.BEATS_PER_COLUMN}). Hard-coded here to
        // avoid a reverse dependency from daw-core onto daw-app.
        double appliedBeatDelta = clampedDelta * 0.25;
        return new SlipResult(new SlipMidiClipAction(clip, clampedDelta),
                appliedBeatDelta, hitEdge);
    }
}
