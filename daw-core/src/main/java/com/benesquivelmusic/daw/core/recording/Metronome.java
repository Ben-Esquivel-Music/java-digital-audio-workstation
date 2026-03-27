package com.benesquivelmusic.daw.core.recording;

import java.util.Objects;

/**
 * Generates audible metronome click samples for count-in and tempo reference.
 *
 * <p>The metronome produces a short sine-wave burst for each beat. The first
 * beat of each bar is accented (higher pitch) to help the performer identify
 * bar boundaries. The click is generated entirely in-memory — no audio files
 * or external resources are required.</p>
 *
 * <p>Typical usage:</p>
 * <pre>
 *   Metronome metronome = new Metronome(44100.0, 2);
 *   float[][] clicks = metronome.generateCountIn(CountInMode.TWO_BARS, 120.0, 4);
 * </pre>
 */
public final class Metronome {

    /** Frequency of the accented (downbeat) click in Hz. */
    private static final double ACCENT_FREQUENCY_HZ = 1000.0;

    /** Frequency of the normal (non-downbeat) click in Hz. */
    private static final double NORMAL_FREQUENCY_HZ = 800.0;

    /** Duration of each click in seconds. */
    private static final double CLICK_DURATION_SECONDS = 0.02;

    /** Amplitude of the accented click. */
    private static final float ACCENT_AMPLITUDE = 0.8f;

    /** Amplitude of the normal click. */
    private static final float NORMAL_AMPLITUDE = 0.5f;

    private final double sampleRate;
    private final int channels;

    /**
     * Creates a new metronome.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param channels   the number of audio channels
     */
    public Metronome(double sampleRate, int channels) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * Generates count-in click audio for the given parameters.
     *
     * <p>Returns a multi-channel audio buffer containing the full count-in
     * audio. Each beat position receives a short click; the first beat of
     * each bar is accented.</p>
     *
     * @param mode        the count-in mode (number of bars)
     * @param tempo       the tempo in BPM
     * @param beatsPerBar the number of beats per bar (time signature numerator)
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0],
     *         or an empty array if count-in is {@link CountInMode#OFF}
     */
    public float[][] generateCountIn(CountInMode mode, double tempo, int beatsPerBar) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("beatsPerBar must be positive: " + beatsPerBar);
        }

        int totalBeats = mode.getTotalBeats(beatsPerBar);
        if (totalBeats == 0) {
            return new float[channels][0];
        }

        double secondsPerBeat = 60.0 / tempo;
        double totalSeconds = totalBeats * secondsPerBeat;
        int totalSamples = (int) Math.ceil(totalSeconds * sampleRate);

        float[][] buffer = new float[channels][totalSamples];
        int clickSamples = (int) (CLICK_DURATION_SECONDS * sampleRate);

        for (int beat = 0; beat < totalBeats; beat++) {
            boolean accent = (beat % beatsPerBar) == 0;
            double frequency = accent ? ACCENT_FREQUENCY_HZ : NORMAL_FREQUENCY_HZ;
            float amplitude = accent ? ACCENT_AMPLITUDE : NORMAL_AMPLITUDE;

            int beatStartSample = (int) (beat * secondsPerBeat * sampleRate);
            int clickEnd = Math.min(beatStartSample + clickSamples, totalSamples);

            for (int s = beatStartSample; s < clickEnd; s++) {
                double t = (s - beatStartSample) / sampleRate;
                // Apply an envelope that decays over the click duration
                double envelope = 1.0 - ((double) (s - beatStartSample) / clickSamples);
                float sample = (float) (amplitude * envelope
                        * Math.sin(2.0 * Math.PI * frequency * t));
                for (int ch = 0; ch < channels; ch++) {
                    buffer[ch][s] = sample;
                }
            }
        }

        return buffer;
    }

    /**
     * Generates a single click sample buffer for one beat.
     *
     * @param accent {@code true} for an accented (downbeat) click
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0]
     */
    public float[][] generateClick(boolean accent) {
        double frequency = accent ? ACCENT_FREQUENCY_HZ : NORMAL_FREQUENCY_HZ;
        float amplitude = accent ? ACCENT_AMPLITUDE : NORMAL_AMPLITUDE;
        int clickSamples = (int) (CLICK_DURATION_SECONDS * sampleRate);

        float[][] buffer = new float[channels][clickSamples];

        for (int s = 0; s < clickSamples; s++) {
            double t = s / sampleRate;
            double envelope = 1.0 - ((double) s / clickSamples);
            float sample = (float) (amplitude * envelope
                    * Math.sin(2.0 * Math.PI * frequency * t));
            for (int ch = 0; ch < channels; ch++) {
                buffer[ch][s] = sample;
            }
        }

        return buffer;
    }

    /**
     * Returns the sample rate used by this metronome.
     *
     * @return the sample rate in Hz
     */
    public double getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the number of audio channels.
     *
     * @return the channel count
     */
    public int getChannels() {
        return channels;
    }
}
