package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An undoable action that batch-freezes multiple tracks at once.
 *
 * <p>Executing this action freezes all non-frozen tracks in the given list.
 * Undoing restores all tracks that were frozen by this action to their
 * unfrozen state.</p>
 */
public final class BatchFreezeTracksAction implements UndoableAction {

    private final List<Track> tracks;
    private final Function<Track, MixerChannel> channelLookup;
    private final int sampleRate;
    private final double tempo;
    private final int channels;
    private final List<Track> frozenByThisAction = new ArrayList<>();

    /**
     * Creates a new batch-freeze action.
     *
     * @param tracks        the tracks to freeze
     * @param channelLookup a function that returns the mixer channel for a track
     * @param sampleRate    the project sample rate in Hz
     * @param tempo         the project tempo in BPM
     * @param channels      the number of output channels
     */
    public BatchFreezeTracksAction(List<Track> tracks,
                                   Function<Track, MixerChannel> channelLookup,
                                   int sampleRate, double tempo, int channels) {
        this.tracks = Objects.requireNonNull(tracks, "tracks must not be null");
        this.channelLookup = Objects.requireNonNull(channelLookup,
                "channelLookup must not be null");
        this.sampleRate = sampleRate;
        this.tempo = tempo;
        this.channels = channels;
    }

    @Override
    public String description() {
        return "Batch Freeze Tracks";
    }

    @Override
    public void execute() {
        frozenByThisAction.clear();
        for (Track track : tracks) {
            if (!track.isFrozen()) {
                MixerChannel channel = channelLookup.apply(track);
                if (channel != null) {
                    TrackFreezeService.freeze(track, channel, sampleRate, tempo, channels);
                    frozenByThisAction.add(track);
                }
            }
        }
    }

    @Override
    public void undo() {
        for (Track track : frozenByThisAction) {
            TrackFreezeService.unfreeze(track);
        }
        frozenByThisAction.clear();
    }
}
