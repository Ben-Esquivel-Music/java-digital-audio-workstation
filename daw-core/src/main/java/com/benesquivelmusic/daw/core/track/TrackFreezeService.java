package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.export.TrackBouncer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Service for freezing and unfreezing tracks to manage CPU usage.
 *
 * <p>Freezing a track renders its audio clips through the associated mixer
 * channel's effects chain offline and stores the result as pre-rendered
 * audio data on the track. During playback the frozen audio is used
 * instead of real-time effects processing, freeing CPU resources for
 * other tracks.</p>
 *
 * <p>Unfreezing a track restores the original effects chain for further
 * editing and discards the pre-rendered audio data.</p>
 */
public final class TrackFreezeService {

    private TrackFreezeService() {
        // utility class
    }

    /**
     * Freezes a single track by rendering its audio through the mixer
     * channel's effects chain and storing the result.
     *
     * @param track      the track to freeze
     * @param channel    the mixer channel associated with the track
     * @param sampleRate the project sample rate in Hz
     * @param tempo      the project tempo in BPM
     * @param channels   the number of output channels
     * @throws NullPointerException     if {@code track} or {@code channel} is null
     * @throws IllegalStateException    if the track is already frozen
     * @throws IllegalArgumentException if {@code sampleRate}, {@code tempo},
     *                                  or {@code channels} is not positive
     */
    public static void freeze(Track track, MixerChannel channel,
                              int sampleRate, double tempo, int channels) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        if (track.isFrozen()) {
            throw new IllegalStateException("track is already frozen");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }

        float[][] rawAudio = TrackBouncer.bounce(track, sampleRate, tempo, channels);

        float[][] frozenAudio;
        if (rawAudio == null || rawAudio.length == 0 || rawAudio[0].length == 0) {
            frozenAudio = new float[channels][0];
        } else {
            EffectsChain chain = channel.getEffectsChain();
            if (!chain.isEmpty() && !chain.isBypassed()) {
                int numFrames = rawAudio[0].length;
                frozenAudio = new float[channels][numFrames];
                chain.process(rawAudio, frozenAudio, numFrames);
            } else {
                frozenAudio = rawAudio;
            }
        }

        track.setFrozenAudioData(frozenAudio);
        track.setFrozen(true);
    }

    /**
     * Unfreezes a single track, restoring its original effects chain
     * and discarding the pre-rendered audio data.
     *
     * @param track the track to unfreeze
     * @throws NullPointerException  if {@code track} is null
     * @throws IllegalStateException if the track is not frozen
     */
    public static void unfreeze(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        if (!track.isFrozen()) {
            throw new IllegalStateException("track is not frozen");
        }
        track.setFrozenAudioData(null);
        track.setFrozen(false);
    }

    /**
     * Freezes all non-frozen tracks in the given list (batch freeze).
     *
     * <p>Tracks that are already frozen are skipped. If a channel lookup
     * returns {@code null} for a track, that track is also skipped.</p>
     *
     * @param tracks        the tracks to freeze
     * @param channelLookup a function that returns the mixer channel for a track
     * @param sampleRate    the project sample rate in Hz
     * @param tempo         the project tempo in BPM
     * @param channels      the number of output channels
     * @throws NullPointerException     if {@code tracks} or {@code channelLookup} is null
     * @throws IllegalArgumentException if {@code sampleRate}, {@code tempo},
     *                                  or {@code channels} is not positive
     */
    public static void freezeAll(List<Track> tracks,
                                 Function<Track, MixerChannel> channelLookup,
                                 int sampleRate, double tempo, int channels) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(channelLookup, "channelLookup must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }

        for (Track track : tracks) {
            if (!track.isFrozen()) {
                MixerChannel channel = channelLookup.apply(track);
                if (channel != null) {
                    freeze(track, channel, sampleRate, tempo, channels);
                }
            }
        }
    }

    /**
     * Unfreezes all frozen tracks in the given list (batch unfreeze).
     *
     * <p>Tracks that are not frozen are skipped.</p>
     *
     * @param tracks the tracks to unfreeze
     * @throws NullPointerException if {@code tracks} is null
     */
    public static void unfreezeAll(List<Track> tracks) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        for (Track track : tracks) {
            if (track.isFrozen()) {
                unfreeze(track);
            }
        }
    }
}
