package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An undoable action that batch-unfreezes multiple tracks at once.
 *
 * <p>Executing this action unfreezes every currently-frozen track in
 * the supplied list. Undoing re-freezes only those tracks that were
 * actually unfrozen by this action — tracks that were already
 * unfrozen at execute time are left untouched on undo so multi-track
 * unfreeze ⇄ undo round-trips faithfully.</p>
 *
 * <p>Pairs with {@link BatchFreezeTracksAction} so both batch
 * operations register as a single undo step in the
 * {@link com.benesquivelmusic.daw.core.undo.UndoManager} history,
 * matching the "Freeze all selected" / "Unfreeze all selected" UX
 * described in story 035.</p>
 */
public final class BatchUnfreezeTracksAction implements UndoableAction {

    private final List<Track> tracks;
    private final Function<Track, MixerChannel> channelLookup;
    private final int sampleRate;
    private final double tempo;
    private final int channels;
    private final List<Track> unfrozenByThisAction = new ArrayList<>();

    /**
     * Creates a new batch-unfreeze action.
     *
     * @param tracks        the tracks to unfreeze
     * @param channelLookup function returning the mixer channel for a
     *                      track; required so {@link #undo()} can re-freeze
     * @param sampleRate    project sample rate in Hz
     * @param tempo         project tempo in BPM
     * @param channels      number of output channels
     */
    public BatchUnfreezeTracksAction(List<Track> tracks,
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
        return "Batch Unfreeze Tracks";
    }

    @Override
    public void execute() {
        unfrozenByThisAction.clear();
        for (Track track : tracks) {
            if (track.isFrozen()) {
                TrackFreezeService.unfreeze(track);
                unfrozenByThisAction.add(track);
            }
        }
    }

    @Override
    public void undo() {
        for (Track track : unfrozenByThisAction) {
            MixerChannel channel = channelLookup.apply(track);
            if (channel != null) {
                TrackFreezeService.freeze(track, channel, sampleRate, tempo, channels);
            }
        }
        unfrozenByThisAction.clear();
    }
}
