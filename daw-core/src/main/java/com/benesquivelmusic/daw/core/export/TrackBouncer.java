package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.List;
import java.util.Objects;

/**
 * Bounces a track's audio clips into a single contiguous audio buffer.
 *
 * <p>Each clip with in-memory audio data is placed at its beat position,
 * converted to sample frames using the project tempo and sample rate.
 * Overlapping clips are summed (mixed). Clips without audio data are skipped.</p>
 */
public final class TrackBouncer {

    private TrackBouncer() {
        // utility class
    }

    /**
     * Renders all audio clips on the given track into a single stereo buffer.
     *
     * @param track      the track to bounce
     * @param sampleRate the project sample rate in Hz
     * @param tempo      the project tempo in BPM
     * @param channels   the number of output channels (1 = mono, 2 = stereo)
     * @return the bounced audio as {@code [channel][sample]} in [-1.0, 1.0],
     *         or {@code null} if no clips contain audio data
     * @throws NullPointerException     if {@code track} is null
     * @throws IllegalArgumentException if {@code sampleRate}, {@code tempo},
     *                                  or {@code channels} is not positive
     */
    public static float[][] bounce(Track track, int sampleRate, double tempo, int channels) {
        Objects.requireNonNull(track, "track must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }

        List<AudioClip> clips = track.getClips();
        if (clips.isEmpty()) {
            return null;
        }

        // Find the last beat across all clips to determine total buffer length
        double lastBeat = 0.0;
        boolean hasAudioData = false;
        for (AudioClip clip : clips) {
            if (clip.getAudioData() != null && clip.getAudioData().length > 0) {
                hasAudioData = true;
                double clipEndBeat = clip.getStartBeat() + clip.getDurationBeats();
                if (clipEndBeat > lastBeat) {
                    lastBeat = clipEndBeat;
                }
            }
        }

        if (!hasAudioData) {
            return null;
        }

        int totalFrames = beatsToFrames(lastBeat, sampleRate, tempo);
        if (totalFrames <= 0) {
            return null;
        }

        float[][] output = new float[channels][totalFrames];

        for (AudioClip clip : clips) {
            float[][] clipData = clip.getAudioData();
            if (clipData == null || clipData.length == 0) {
                continue;
            }

            int startFrame = beatsToFrames(clip.getStartBeat(), sampleRate, tempo);
            int clipChannels = clipData.length;
            int clipSamples = clipData[0].length;

            // Apply gain
            double gainLinear = Math.pow(10.0, clip.getGainDb() / 20.0);

            for (int ch = 0; ch < channels; ch++) {
                int srcChannel = Math.min(ch, clipChannels - 1);
                for (int i = 0; i < clipSamples; i++) {
                    int outIdx = startFrame + i;
                    if (outIdx >= 0 && outIdx < totalFrames) {
                        output[ch][outIdx] += (float) (clipData[srcChannel][i] * gainLinear);
                    }
                }
            }
        }

        // Clamp to [-1.0, 1.0]
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < totalFrames; i++) {
                output[ch][i] = Math.max(-1.0f, Math.min(1.0f, output[ch][i]));
            }
        }

        return output;
    }

    /**
     * Converts a beat position to a sample frame index.
     *
     * @param beats      the position in beats
     * @param sampleRate the sample rate in Hz
     * @param tempo      the tempo in BPM
     * @return the frame index
     */
    static int beatsToFrames(double beats, int sampleRate, double tempo) {
        double seconds = beats * 60.0 / tempo;
        return (int) Math.round(seconds * sampleRate);
    }
}
