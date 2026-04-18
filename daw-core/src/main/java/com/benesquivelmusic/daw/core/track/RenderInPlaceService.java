package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.export.TrackBouncer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.List;
import java.util.Objects;

/**
 * Service that renders a track's output through its full signal chain into a
 * new {@link AudioClip} — the "render in place" / "bounce in place" / "commit"
 * workflow.
 *
 * <p>Unlike {@link TrackFreezeService}, which is a binary freeze/unfreeze
 * toggle, render-in-place produces a permanent, editable audio clip containing
 * the rendered output. The caller chooses whether the rendered clip replaces
 * the source clips on the original track or is placed on a new audio track.</p>
 *
 * <p>Rendering for audio tracks goes: clips → {@link EffectsChain inserts}
 * (optional) → channel volume/pan/phase — for the duration of the track's
 * content. For MIDI tracks, a caller-supplied
 * {@link RenderInPlaceOptions.MidiRenderer MIDI renderer} synthesizes audio
 * from the MIDI clip before the insert chain is applied.</p>
 */
public final class RenderInPlaceService {

    private RenderInPlaceService() {
        // utility class
    }

    /**
     * Result of a render-in-place operation.
     *
     * @param renderedClip   the rendered audio clip (never {@code null})
     * @param originalClips  snapshot of the clips that existed on the source
     *                       track before the operation (used for undo)
     * @param destinationTrack the track that actually received the rendered
     *                       clip; equals the source track in "replace" mode
     *                       and is a freshly-created track in "new track" mode
     */
    public record Result(AudioClip renderedClip,
                         List<AudioClip> originalClips,
                         Track destinationTrack) {
        public Result {
            Objects.requireNonNull(renderedClip, "renderedClip must not be null");
            Objects.requireNonNull(originalClips, "originalClips must not be null");
            Objects.requireNonNull(destinationTrack, "destinationTrack must not be null");
            originalClips = List.copyOf(originalClips);
        }
    }

    /**
     * Renders the given track's output to a new {@link AudioClip}.
     *
     * <p>If {@link RenderInPlaceOptions#isReplaceOriginalClips()} is set, the
     * source track's clips are removed and replaced with the rendered clip
     * in one mutation. If {@link RenderInPlaceOptions#isCreateNewTrack()} is
     * set, the clip is placed on a track produced by the options'
     * {@code newTrackFactory}. Otherwise the rendered clip is returned
     * without being attached to any track (the caller is responsible for
     * placement).</p>
     *
     * @param track      the track to render
     * @param channel    the mixer channel associated with the track
     * @param sampleRate the project sample rate in Hz
     * @param tempo      the project tempo in BPM
     * @param channels   the number of output channels (1 = mono, 2 = stereo)
     * @param options    render options (not {@code null})
     * @return the result of the render, or {@code null} if the track has no
     *         content to render
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if {@code sampleRate}, {@code tempo},
     *                                  or {@code channels} is not positive
     * @throws IllegalStateException    if the track is frozen, or if the
     *                                  track is a MIDI track and no MIDI
     *                                  renderer was supplied in {@code options}
     */
    public static Result render(Track track, MixerChannel channel,
                                int sampleRate, double tempo, int channels,
                                RenderInPlaceOptions options) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (track.isFrozen()) {
            throw new IllegalStateException(
                    "cannot render-in-place a frozen track; unfreeze it first");
        }

        // 1. Acquire the raw source audio for the track. For audio tracks this
        //    is the bounce of its clips; for MIDI tracks it is the output of
        //    the caller-supplied MIDI renderer.
        float[][] rawAudio;
        double totalBeats;
        switch (track.getType()) {
            case MIDI -> {
                RenderInPlaceOptions.MidiRenderer midi = options.getMidiRenderer();
                if (midi == null) {
                    throw new IllegalStateException(
                            "MIDI track requires a MidiRenderer in RenderInPlaceOptions");
                }
                totalBeats = midiTrackDurationBeats(track);
                if (totalBeats <= 0.0) {
                    return null;
                }
                int frames = beatsToFrames(totalBeats, sampleRate, tempo);
                if (frames <= 0) {
                    return null;
                }
                rawAudio = midi.render(track, sampleRate, tempo, channels, frames);
                if (rawAudio == null || rawAudio.length == 0 || rawAudio[0].length == 0) {
                    return null;
                }
            }
            case AUDIO, AUDIO_OBJECT -> {
                rawAudio = TrackBouncer.bounce(track, sampleRate, tempo, channels);
                if (rawAudio == null || rawAudio.length == 0 || rawAudio[0].length == 0) {
                    return null;
                }
                totalBeats = framesToBeats(rawAudio[0].length, sampleRate, tempo);
            }
            default -> throw new IllegalStateException(
                    "render-in-place is not supported for track type " + track.getType());
        }

        int numFrames = rawAudio[0].length;

        // 2. Apply insert effects (if requested and present).
        float[][] processed = rawAudio;
        if (options.isIncludeInserts()) {
            EffectsChain chain = channel.getEffectsChain();
            if (chain != null && !chain.isEmpty() && !chain.isBypassed()) {
                float[][] out = new float[channels][numFrames];
                chain.process(rawAudio, out, numFrames);
                processed = out;
            }
        }

        // 3. Apply channel volume, pan, and phase inversion. Keep this simple
        //    and self-contained so the core render-in-place path does not
        //    depend on the real-time Mixer/RenderPipeline lifecycle.
        applyChannelGainAndPan(processed, channel, channels);

        // 4. Clamp to [-1, 1] to match the behaviour of TrackBouncer.
        clamp(processed);

        // 5. Package into an AudioClip positioned at the original start.
        double startBeat = earliestStartBeat(track);
        AudioClip rendered = new AudioClip(
                track.getName() + " (rendered)",
                startBeat,
                Math.max(totalBeats, 1e-6),
                null);
        rendered.setAudioData(processed);

        // 6. Capture the original clip list for undo, then perform placement.
        List<AudioClip> originalClips = List.copyOf(track.getClips());
        Track destination;
        if (options.isReplaceOriginalClips()) {
            for (AudioClip c : originalClips) {
                track.removeClip(c);
            }
            track.addClip(rendered);
            destination = track;
        } else if (options.isCreateNewTrack()) {
            destination = options.getNewTrackFactory().apply(track);
            Objects.requireNonNull(destination,
                    "newTrackFactory must not return null");
            destination.addClip(rendered);
        } else {
            destination = track;
        }

        return new Result(rendered, originalClips, destination);
    }

    /**
     * Restores a track to its state before a render-in-place operation, given
     * the {@link Result} returned by {@link #render}. Used to implement undo.
     *
     * @param track  the source track that was rendered
     * @param result the result previously returned by {@link #render}
     */
    public static void restore(Track track, Result result) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(result, "result must not be null");
        // Remove the rendered clip wherever it ended up.
        result.destinationTrack().removeClip(result.renderedClip());
        // In replace mode the source track's clips were removed during render
        // and need to be restored. In non-replace modes the originals were
        // never removed, so skip re-adding them.
        boolean replaceMode = result.destinationTrack() == track
                && !track.getClips().containsAll(result.originalClips());
        if (replaceMode) {
            for (AudioClip c : result.originalClips()) {
                track.addClip(c);
            }
        }
    }

    // ---------- helpers ----------

    private static double midiTrackDurationBeats(Track track) {
        // Use the span of audio clips if present; otherwise infer from the
        // MIDI clip length if the track exposes one. We stay intentionally
        // conservative: if the track has AudioClips (e.g. preview renders),
        // include them in the length; otherwise fall back to a single bar
        // (4 beats) so MIDI renderers always have a non-zero window.
        double last = 0.0;
        for (AudioClip c : track.getClips()) {
            last = Math.max(last, c.getStartBeat() + c.getDurationBeats());
        }
        if (last > 0.0) {
            return last;
        }
        return 4.0;
    }

    private static double earliestStartBeat(Track track) {
        double earliest = Double.POSITIVE_INFINITY;
        for (AudioClip c : track.getClips()) {
            if (c.getStartBeat() < earliest) {
                earliest = c.getStartBeat();
            }
        }
        return Double.isFinite(earliest) ? earliest : 0.0;
    }

    private static double framesToBeats(int frames, int sampleRate, double tempo) {
        double seconds = (double) frames / sampleRate;
        return seconds * tempo / 60.0;
    }

    private static int beatsToFrames(double beats, int sampleRate, double tempo) {
        double seconds = beats * 60.0 / tempo;
        return (int) Math.round(seconds * sampleRate);
    }

    private static void applyChannelGainAndPan(float[][] buffer, MixerChannel channel, int channels) {
        double volume = channel.getVolume();
        double pan = channel.getPan();
        boolean invert = channel.isPhaseInverted();

        // Equal-power pan law for stereo; mono ignores pan.
        double leftGain = volume;
        double rightGain = volume;
        if (channels >= 2) {
            double theta = (pan + 1.0) * 0.25 * Math.PI; // pan in [-1,1] -> [0, pi/2]
            leftGain = volume * Math.cos(theta) * Math.sqrt(2.0);
            rightGain = volume * Math.sin(theta) * Math.sqrt(2.0);
        }
        int frames = buffer[0].length;
        double phase = invert ? -1.0 : 1.0;
        for (int ch = 0; ch < buffer.length; ch++) {
            double g = (ch == 0 ? leftGain : rightGain) * phase;
            for (int i = 0; i < frames; i++) {
                buffer[ch][i] = (float) (buffer[ch][i] * g);
            }
        }
    }

    private static void clamp(float[][] buffer) {
        for (int ch = 0; ch < buffer.length; ch++) {
            float[] row = buffer[ch];
            for (int i = 0; i < row.length; i++) {
                if (row[i] > 1.0f) row[i] = 1.0f;
                else if (row[i] < -1.0f) row[i] = -1.0f;
            }
        }
    }
}
