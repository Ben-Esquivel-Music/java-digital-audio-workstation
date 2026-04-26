package com.benesquivelmusic.daw.core.mixer;

/**
 * Enumerates the built-in DSP processor types available for insert effect slots.
 *
 * <p>Each value corresponds to a concrete {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor}
 * implementation in the {@code daw-core} DSP package. The {@link #getDisplayName()} method
 * provides a human-readable label suitable for menus and channel strip UI.</p>
 */
public enum InsertEffectType {

    PARAMETRIC_EQ("Parametric EQ"),
    COMPRESSOR("Compressor"),
    LIMITER("Limiter"),
    REVERB("Reverb"),
    DELAY("Delay"),
    CHORUS("Chorus"),
    NOISE_GATE("Noise Gate"),
    STEREO_IMAGER("Stereo Imager"),
    GRAPHIC_EQ("Graphic EQ"),
    ANALOG_DISTORTION("Analog Distortion"),
    BASS_EXTENSION("Bass Extension"),
    CHIRP_PEAK_REDUCER("Chirp Peak Reducer"),
    GAIN_STAGING("Gain Staging"),
    HEARING_LOSS_SIMULATOR("Hearing Loss Simulator"),
    LESLIE("Leslie"),
    PITCH_SHIFT("Pitch Shift"),
    SPRING_REVERB("Spring Reverb"),
    TIME_STRETCH("Time Stretch"),
    VELVET_NOISE_REVERB("Velvet Noise Reverb"),
    CONVOLUTION_REVERB("Convolution Reverb"),
    WAVESHAPER("Waveshaper"),
    CLAP_PLUGIN("CLAP Plugin");

    private final String displayName;

    InsertEffectType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this effect type.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }
}
