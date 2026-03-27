package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that splits an {@link AudioClip} at a given beat position.
 *
 * <p>Executing this action splits the clip into two parts: the original clip
 * is truncated to end at the split point, and a new clip covering the
 * remainder is added to the same track. Undoing the action restores the
 * original clip's properties and removes the second clip from the track.</p>
 */
public final class SplitClipAction implements UndoableAction {

    private final Track track;
    private final AudioClip clip;
    private final double splitBeat;

    // Snapshot for undo
    private double originalDuration;
    private double originalFadeOutBeats;
    private AudioClip secondClip;

    /**
     * Creates a new split-clip action.
     *
     * @param track     the track containing the clip
     * @param clip      the clip to split
     * @param splitBeat the beat position at which to split
     */
    public SplitClipAction(Track track, AudioClip clip, double splitBeat) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.splitBeat = splitBeat;
    }

    @Override
    public String description() {
        return "Split Clip";
    }

    @Override
    public void execute() {
        originalDuration = clip.getDurationBeats();
        originalFadeOutBeats = clip.getFadeOutBeats();
        secondClip = clip.splitAt(splitBeat);
        track.addClip(secondClip);
    }

    @Override
    public void undo() {
        track.removeClip(secondClip);
        clip.setDurationBeats(originalDuration);
        clip.setFadeOutBeats(originalFadeOutBeats);
    }
}
