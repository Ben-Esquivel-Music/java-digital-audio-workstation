package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that unfreezes a track.
 *
 * <p>Executing this action restores the track to its unfrozen state,
 * discarding the pre-rendered audio data and re-enabling real-time
 * effects processing. Undoing re-freezes the track.</p>
 */
public final class UnfreezeTrackAction implements UndoableAction {

    private final Track track;
    private final MixerChannel channel;
    private final int sampleRate;
    private final double tempo;
    private final int channels;

    /**
     * Creates a new unfreeze-track action.
     *
     * @param track      the track to unfreeze
     * @param channel    the mixer channel associated with the track
     * @param sampleRate the project sample rate in Hz
     * @param tempo      the project tempo in BPM
     * @param channels   the number of output channels
     */
    public UnfreezeTrackAction(Track track, MixerChannel channel,
                               int sampleRate, double tempo, int channels) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.sampleRate = sampleRate;
        this.tempo = tempo;
        this.channels = channels;
    }

    @Override
    public String description() {
        return "Unfreeze Track";
    }

    @Override
    public void execute() {
        TrackFreezeService.unfreeze(track);
    }

    @Override
    public void undo() {
        TrackFreezeService.freeze(track, channel, sampleRate, tempo, channels);
    }
}
