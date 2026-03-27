package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the fade-in and/or fade-out parameters
 * (duration and curve type) of an {@link AudioClip}.
 *
 * <p>Executing this action applies new fade-in/fade-out durations and curve
 * types to the clip. Undoing restores the previous values.</p>
 */
public final class FadeClipAction implements UndoableAction {

    private final AudioClip clip;
    private final double newFadeInBeats;
    private final double newFadeOutBeats;
    private final FadeCurveType newFadeInCurveType;
    private final FadeCurveType newFadeOutCurveType;

    // Snapshot for undo
    private double originalFadeInBeats;
    private double originalFadeOutBeats;
    private FadeCurveType originalFadeInCurveType;
    private FadeCurveType originalFadeOutCurveType;

    /**
     * Creates a new fade-clip action.
     *
     * @param clip                the clip to modify
     * @param newFadeInBeats      the new fade-in duration in beats
     * @param newFadeOutBeats     the new fade-out duration in beats
     * @param newFadeInCurveType  the new fade-in curve type
     * @param newFadeOutCurveType the new fade-out curve type
     */
    public FadeClipAction(AudioClip clip,
                          double newFadeInBeats, double newFadeOutBeats,
                          FadeCurveType newFadeInCurveType, FadeCurveType newFadeOutCurveType) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newFadeInBeats = newFadeInBeats;
        this.newFadeOutBeats = newFadeOutBeats;
        this.newFadeInCurveType = Objects.requireNonNull(newFadeInCurveType, "newFadeInCurveType must not be null");
        this.newFadeOutCurveType = Objects.requireNonNull(newFadeOutCurveType, "newFadeOutCurveType must not be null");
    }

    @Override
    public String description() {
        return "Adjust Clip Fade";
    }

    @Override
    public void execute() {
        originalFadeInBeats = clip.getFadeInBeats();
        originalFadeOutBeats = clip.getFadeOutBeats();
        originalFadeInCurveType = clip.getFadeInCurveType();
        originalFadeOutCurveType = clip.getFadeOutCurveType();

        clip.setFadeInBeats(newFadeInBeats);
        clip.setFadeOutBeats(newFadeOutBeats);
        clip.setFadeInCurveType(newFadeInCurveType);
        clip.setFadeOutCurveType(newFadeOutCurveType);
    }

    @Override
    public void undo() {
        clip.setFadeInBeats(originalFadeInBeats);
        clip.setFadeOutBeats(originalFadeOutBeats);
        clip.setFadeInCurveType(originalFadeInCurveType);
        clip.setFadeOutCurveType(originalFadeOutCurveType);
    }
}
