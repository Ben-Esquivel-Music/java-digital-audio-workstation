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

    /**
     * Creates a new glue-clips action.
     *
     * @param track  the track containing both clips
     * @param first  the earlier clip (by start beat)
     * @param second the later clip (by start beat)
     * @throws IllegalArgumentException if the clips are not adjacent
     *         (i.e. the first clip's end beat does not equal the second clip's start beat)
     */
    public GlueClipsAction(Track track, AudioClip first, AudioClip second) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.first = Objects.requireNonNull(first, "first must not be null");
        this.second = Objects.requireNonNull(second, "second must not be null");

        if (first.getStartBeat() > second.getStartBeat()) {
            throw new IllegalArgumentException(
                    "first clip must start before or at the same beat as second clip");
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
        merged.setFadeInBeats(first.getFadeInBeats());
        merged.setFadeInCurveType(first.getFadeInCurveType());
        merged.setFadeOutBeats(second.getFadeOutBeats());
        merged.setFadeOutCurveType(second.getFadeOutCurveType());
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
