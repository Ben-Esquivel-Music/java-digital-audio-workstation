package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the time-stretch ratio and quality
 * setting of an {@link AudioClip}.
 *
 * <p>Executing this action applies a new time-stretch ratio and quality
 * to the clip. Undoing restores the previous values. The time-stretch is
 * stored as non-destructive clip metadata, computed during playback.</p>
 */
public final class TimeStretchClipAction implements UndoableAction {

    private final AudioClip clip;
    private final double newStretchRatio;
    private final StretchQuality newQuality;

    // Snapshot for undo
    private double originalStretchRatio;
    private StretchQuality originalQuality;

    /**
     * Creates a new time-stretch clip action.
     *
     * @param clip            the clip to modify (must not be {@code null})
     * @param newStretchRatio the new stretch ratio (must be between 0.25 and 4.0)
     * @param newQuality      the new quality setting (must not be {@code null})
     */
    public TimeStretchClipAction(AudioClip clip, double newStretchRatio,
                                 StretchQuality newQuality) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        if (newStretchRatio < 0.25 || newStretchRatio > 4.0) {
            throw new IllegalArgumentException(
                    "newStretchRatio must be in [0.25, 4.0]: " + newStretchRatio);
        }
        this.newStretchRatio = newStretchRatio;
        this.newQuality = Objects.requireNonNull(newQuality, "newQuality must not be null");
    }

    @Override
    public String description() {
        return "Time Stretch Clip";
    }

    @Override
    public void execute() {
        originalStretchRatio = clip.getTimeStretchRatio();
        originalQuality = clip.getStretchQuality();

        clip.setTimeStretchRatio(newStretchRatio);
        clip.setStretchQuality(newQuality);
    }

    @Override
    public void undo() {
        clip.setTimeStretchRatio(originalStretchRatio);
        clip.setStretchQuality(originalQuality);
    }
}
