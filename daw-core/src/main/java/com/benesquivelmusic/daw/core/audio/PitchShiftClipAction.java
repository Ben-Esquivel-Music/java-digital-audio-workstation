package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the pitch-shift amount and quality
 * setting of an {@link AudioClip}.
 *
 * <p>Executing this action applies a new pitch-shift (in semitones) and
 * quality to the clip. Undoing restores the previous values. The pitch-shift
 * is stored as non-destructive clip metadata, computed during playback.</p>
 */
public final class PitchShiftClipAction implements UndoableAction {

    private final AudioClip clip;
    private final double newPitchShiftSemitones;
    private final StretchQuality newQuality;

    // Snapshot for undo
    private double originalPitchShiftSemitones;
    private StretchQuality originalQuality;

    /**
     * Creates a new pitch-shift clip action.
     *
     * @param clip                    the clip to modify (must not be {@code null})
     * @param newPitchShiftSemitones  the new pitch shift in semitones (must be between -24 and 24)
     * @param newQuality              the new quality setting (must not be {@code null})
     */
    public PitchShiftClipAction(AudioClip clip, double newPitchShiftSemitones,
                                StretchQuality newQuality) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        if (newPitchShiftSemitones < -24.0 || newPitchShiftSemitones > 24.0) {
            throw new IllegalArgumentException(
                    "newPitchShiftSemitones must be in [-24, 24]: " + newPitchShiftSemitones);
        }
        this.newPitchShiftSemitones = newPitchShiftSemitones;
        this.newQuality = Objects.requireNonNull(newQuality, "newQuality must not be null");
    }

    @Override
    public String description() {
        return "Pitch Shift Clip";
    }

    @Override
    public void execute() {
        originalPitchShiftSemitones = clip.getPitchShiftSemitones();
        originalQuality = clip.getStretchQuality();

        clip.setPitchShiftSemitones(newPitchShiftSemitones);
        clip.setStretchQuality(newQuality);
    }

    @Override
    public void undo() {
        clip.setPitchShiftSemitones(originalPitchShiftSemitones);
        clip.setStretchQuality(originalQuality);
    }
}
