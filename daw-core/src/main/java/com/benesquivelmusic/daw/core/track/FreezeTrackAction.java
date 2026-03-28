package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that freezes a track.
 *
 * <p>Executing this action renders the track's audio through its mixer
 * channel's effects chain and stores the result as pre-rendered audio
 * data. Undoing restores the track to its unfrozen state.</p>
 */
public final class FreezeTrackAction implements UndoableAction {

    private final Track track;
    private final MixerChannel channel;
    private final int sampleRate;
    private final double tempo;
    private final int channels;

    /**
     * Creates a new freeze-track action.
     *
     * @param track      the track to freeze
     * @param channel    the mixer channel associated with the track
     * @param sampleRate the project sample rate in Hz
     * @param tempo      the project tempo in BPM
     * @param channels   the number of output channels
     */
    public FreezeTrackAction(Track track, MixerChannel channel,
                             int sampleRate, double tempo, int channels) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.sampleRate = sampleRate;
        this.tempo = tempo;
        this.channels = channels;
    }

    @Override
    public String description() {
        return "Freeze Track";
    }

    @Override
    public void execute() {
        TrackFreezeService.freeze(track, channel, sampleRate, tempo, channels);
    }

    @Override
    public void undo() {
        TrackFreezeService.unfreeze(track);
    }
}
