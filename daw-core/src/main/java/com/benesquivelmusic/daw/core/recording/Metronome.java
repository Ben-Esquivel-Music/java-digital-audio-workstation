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
 * <p>Features include:</p>
 * <ul>
 *   <li>Enable/disable toggle for muting the click</li>
 *   <li>Configurable volume independent of the master bus</li>
 *   <li>Selectable built-in click sounds ({@link ClickSound})</li>
 *   <li>Subdivision clicks for eighth and sixteenth notes ({@link Subdivision})</li>
 *   <li>Configurable count-in duration ({@link CountInMode})</li>
 * </ul>
 *
 * <p>Typical usage:</p>
 * <pre>
 *   Metronome metronome = new Metronome(44100.0, 2);
 *   metronome.setClickSound(ClickSound.COWBELL);
 *   metronome.setVolume(0.7f);
 *   metronome.setSubdivision(Subdivision.EIGHTH);
 *   float[][] clicks = metronome.generateCountIn(CountInMode.TWO_BARS, 120.0, 4);
 * </pre>
 */
public final class Metronome {

    /** Duration of each click in seconds. */
    private static final double CLICK_DURATION_SECONDS = 0.02;

    /** Base amplitude of the accented click (before volume scaling). */
    private static final float ACCENT_AMPLITUDE = 0.8f;

    /** Base amplitude of the normal click (before volume scaling). */
    private static final float NORMAL_AMPLITUDE = 0.5f;

    /** Amplitude scale factor for subdivision clicks relative to normal clicks. */
    private static final float SUBDIVISION_AMPLITUDE_SCALE = 0.5f;

    private final double sampleRate;
    private final int channels;
    private boolean enabled;
    private float volume;
    private ClickSound clickSound;
    private Subdivision subdivision;

    /**
     * Creates a new metronome with default settings: enabled, full volume,
     * woodblock click sound, and quarter-note subdivision.
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
        this.enabled = true;
        this.volume = 1.0f;
        this.clickSound = ClickSound.WOODBLOCK;
        this.subdivision = Subdivision.QUARTER;
    }

    /**
     * Generates count-in click audio for the given parameters.
     *
     * <p>Returns a multi-channel audio buffer containing the full count-in
     * audio. Each beat position receives a short click; the first beat of
     * each bar is accented. When a subdivision other than {@link Subdivision#QUARTER}
     * is configured, additional softer clicks are placed between beats.</p>
     *
     * <p>If the metronome is disabled, an empty buffer is returned.</p>
     *
     * @param mode        the count-in mode (number of bars)
     * @param tempo       the tempo in BPM
     * @param beatsPerBar the number of beats per bar (time signature numerator)
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0],
     *         or an empty array if count-in is {@link CountInMode#OFF} or disabled
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
        if (totalBeats == 0 || !enabled) {
            return new float[channels][0];
        }

        double secondsPerBeat = 60.0 / tempo;
        double totalSeconds = totalBeats * secondsPerBeat;
        int totalSamples = (int) Math.ceil(totalSeconds * sampleRate);

        float[][] buffer = new float[channels][totalSamples];
        int clickSamples = (int) (CLICK_DURATION_SECONDS * sampleRate);
        int clicksPerBeat = subdivision.getClicksPerBeat();
        double secondsPerSubdivision = secondsPerBeat / clicksPerBeat;
        double accentFrequency = clickSound.getAccentFrequencyHz();
        double normalFrequency = clickSound.getNormalFrequencyHz();

        for (int beat = 0; beat < totalBeats; beat++) {
            for (int sub = 0; sub < clicksPerBeat; sub++) {
                boolean isMainBeat = (sub == 0);
                boolean isAccent = isMainBeat && (beat % beatsPerBar) == 0;

                double frequency;
                float amplitude;
                if (isAccent) {
                    frequency = accentFrequency;
                    amplitude = ACCENT_AMPLITUDE * volume;
                } else if (isMainBeat) {
                    frequency = normalFrequency;
                    amplitude = NORMAL_AMPLITUDE * volume;
                } else {
                    frequency = normalFrequency;
                    amplitude = NORMAL_AMPLITUDE * SUBDIVISION_AMPLITUDE_SCALE * volume;
                }

                double subdivisionOffset = sub * secondsPerSubdivision;
                int subdivisionStartSample = (int) ((beat * secondsPerBeat + subdivisionOffset) * sampleRate);
                int clickEnd = Math.min(subdivisionStartSample + clickSamples, totalSamples);

                for (int s = subdivisionStartSample; s < clickEnd; s++) {
                    double t = (s - subdivisionStartSample) / sampleRate;
                    double envelope = 1.0 - ((double) (s - subdivisionStartSample) / clickSamples);
                    float sample = (float) (amplitude * envelope
                            * Math.sin(2.0 * Math.PI * frequency * t));
                    for (int ch = 0; ch < channels; ch++) {
                        buffer[ch][s] = sample;
                    }
                }
            }
        }

        return buffer;
    }

    /**
     * Generates a single click sample buffer for one beat.
     *
     * <p>The click uses the currently configured {@link ClickSound} and
     * is scaled by the current {@link #getVolume() volume}.</p>
     *
     * @param accent {@code true} for an accented (downbeat) click
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0]
     */
    public float[][] generateClick(boolean accent) {
        double frequency = accent
                ? clickSound.getAccentFrequencyHz()
                : clickSound.getNormalFrequencyHz();
        float amplitude = (accent ? ACCENT_AMPLITUDE : NORMAL_AMPLITUDE) * volume;
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
     * Returns whether the metronome is enabled.
     *
     * @return {@code true} if the metronome is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the metronome.
     *
     * <p>When disabled, all generation methods return empty buffers.</p>
     *
     * @param enabled {@code true} to enable, {@code false} to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the metronome volume.
     *
     * @return the volume in the range [0.0, 1.0]
     */
    public float getVolume() {
        return volume;
    }

    /**
     * Sets the metronome volume, independent of the master bus volume.
     *
     * @param volume the volume in the range [0.0, 1.0]
     * @throws IllegalArgumentException if the volume is outside [0.0, 1.0]
     */
    public void setVolume(float volume) {
        if (volume < 0.0f || volume > 1.0f) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        this.volume = volume;
    }

    /**
     * Returns the current click sound preset.
     *
     * @return the click sound
     */
    public ClickSound getClickSound() {
        return clickSound;
    }

    /**
     * Sets the click sound preset.
     *
     * @param clickSound the click sound to use
     * @throws NullPointerException if {@code clickSound} is null
     */
    public void setClickSound(ClickSound clickSound) {
        this.clickSound = Objects.requireNonNull(clickSound, "clickSound must not be null");
    }

    /**
     * Returns the current subdivision setting.
     *
     * @return the subdivision
     */
    public Subdivision getSubdivision() {
        return subdivision;
    }

    /**
     * Sets the subdivision level for metronome clicks.
     *
     * @param subdivision the subdivision to use
     * @throws NullPointerException if {@code subdivision} is null
     */
    public void setSubdivision(Subdivision subdivision) {
        this.subdivision = Objects.requireNonNull(subdivision, "subdivision must not be null");
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
