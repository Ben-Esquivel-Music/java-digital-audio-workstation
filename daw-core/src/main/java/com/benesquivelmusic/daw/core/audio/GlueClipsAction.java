package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that glues (merges) two adjacent {@link AudioClip}s on
 * the same track into a single clip.
 *
 * <p>Executing this action removes both clips from the track and adds a new
 * merged clip that spans from the start of the first clip to the end of the
 * second clip. Undoing restores the original two clips.</p>
 */
public final class GlueClipsAction implements UndoableAction {

    private final Track track;
    private final AudioClip first;
    private final AudioClip second;

    private AudioClip merged;

    /** Tolerance for comparing beat positions (accommodates floating-point imprecision). */
    private static final double BEAT_EPSILON = 0.001;

    /**
     * Creates a new glue-clips action.
     *
     * @param track  the track containing both clips
     * @param first  the earlier clip (by start beat)
     * @param second the later clip (by start beat)
     * @throws IllegalArgumentException if the clips are the same instance, are not
     *         in order, or are not adjacent (i.e. the first clip's end beat does not
     *         approximately equal the second clip's start beat)
     */
    public GlueClipsAction(Track track, AudioClip first, AudioClip second) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.first = Objects.requireNonNull(first, "first must not be null");
        this.second = Objects.requireNonNull(second, "second must not be null");

        if (first == second) {
            throw new IllegalArgumentException("first and second must be distinct clip instances");
        }
        if (first.getStartBeat() > second.getStartBeat()) {
            throw new IllegalArgumentException(
                    "first clip must start before or at the same beat as second clip");
        }
        if (Math.abs(first.getEndBeat() - second.getStartBeat()) > BEAT_EPSILON) {
            throw new IllegalArgumentException(
                    "clips must be adjacent: first ends at " + first.getEndBeat()
                            + " but second starts at " + second.getStartBeat());
        }
        if (!Objects.equals(first.getSourceFilePath(), second.getSourceFilePath())) {
            throw new IllegalArgumentException(
                    "clips must share the same source file path to be glued");
        }
        if (first.getAudioData() != second.getAudioData()) {
            throw new IllegalArgumentException(
                    "clips must share the same audio data buffer to be glued");
        }
    }

    @Override
    public String description() {
        return "Glue Clips";
    }

    @Override
    public void execute() {
        double mergedStart = first.getStartBeat();
        double mergedDuration = second.getEndBeat() - first.getStartBeat();
        merged = new AudioClip(first.getName(), mergedStart, mergedDuration,
                first.getSourceFilePath());
        merged.setSourceOffsetBeats(first.getSourceOffsetBeats());
        merged.setGainDb(first.getGainDb());
        merged.setReversed(first.isReversed());
        merged.setFadeInBeats(first.getFadeInBeats());
        merged.setFadeInCurveType(first.getFadeInCurveType());
        merged.setFadeOutBeats(second.getFadeOutBeats());
        merged.setFadeOutCurveType(second.getFadeOutCurveType());
        merged.setTimeStretchRatio(first.getTimeStretchRatio());
        merged.setPitchShiftSemitones(first.getPitchShiftSemitones());
        merged.setStretchQuality(first.getStretchQuality());
        merged.setAudioData(first.getAudioData());

        track.removeClip(first);
        track.removeClip(second);
        track.addClip(merged);
    }

    @Override
    public void undo() {
        track.removeClip(merged);
        track.addClip(first);
        track.addClip(second);
    }
}
