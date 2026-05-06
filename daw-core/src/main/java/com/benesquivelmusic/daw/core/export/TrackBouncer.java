package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.List;
import java.util.Objects;

/**
 * Bounces a track's audio clips into a single contiguous audio buffer.
 *
 * <p>Each clip with in-memory audio data is placed at its beat position
 * (converted to sample frames using the project tempo and sample rate),
 * scaled by its {@code gainDb}, and summed into the output buffer.
 * Overlapping clips are mixed by simple summation, and the result is
 * clamped to {@code [-1.0, 1.0]}. The output is therefore a "raw" pre-fader
 * summing of the track's clip audio — no mixer-channel volume, pan, or
 * insert effects are applied.</p>
 *
 * <h2>Relationship to the unified render pipeline (story 102)</h2>
 *
 * <p>The full export path — including mixer-channel insert effects,
 * volume, pan, and master processing — is provided by
 * {@link RenderPipeline#renderOffline(
 * com.benesquivelmusic.daw.core.transport.Transport,
 * com.benesquivelmusic.daw.core.mixer.Mixer, java.util.List,
 * com.benesquivelmusic.daw.core.audio.MidiTrackRenderer,
 * com.benesquivelmusic.daw.core.audio.EffectsChain,
 * float[][], int, int) RenderPipeline.renderOffline}. As of story 102,
 * {@link StemExporter} delegates per-track rendering to that pipeline
 * (via {@link OfflineStemRenderer}) — so the production stem-export and
 * bundle-export code paths share the unified render implementation with
 * live playback.</p>
 *
 * <p>This class deliberately stays a low-level <em>raw</em> clip-summing
 * utility because two in-tree callers
 * ({@code com.benesquivelmusic.daw.core.track.TrackFreezeService} and
 * {@code com.benesquivelmusic.daw.core.track.RenderInPlaceService}) compose
 * it with their own per-channel processing — applying insert effects and
 * volume/pan in their own well-defined order so the user can still adjust
 * faders on a frozen / rendered-in-place track. Routing those callers
 * through the full pipeline would change their observable contract and
 * is outside the scope of story 102. New offline render paths that need
 * the full mixer-channel-and-master signal must use
 * {@link RenderPipeline#renderOffline} (or {@link OfflineStemRenderer})
 * directly rather than this method.</p>
 */
public final class TrackBouncer {

    private TrackBouncer() {
        // utility class
    }

    /**
     * Renders all audio clips on the given track into a single audio buffer
     * by summing each clip at its beat position. No mixer-channel
     * processing is applied — see the class javadoc for context and
     * pointers to the full-pipeline alternative.
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
