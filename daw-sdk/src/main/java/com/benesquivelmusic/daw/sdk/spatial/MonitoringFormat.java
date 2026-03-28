package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Monitoring output format for fold-down preview switching.
 *
 * <p>Defines the standard monitoring formats that an engineer may switch
 * between when previewing an immersive mix on different playback systems.
 * Each format maps to a {@link SpeakerLayout} and has a display name
 * suitable for transport bar or monitoring section UI.</p>
 *
 * <p>The fold-down chain follows ITU-R BS.775:
 * 7.1.4 → 5.1 → Stereo → Mono.</p>
 *
 * @see SpeakerLayout
 * @see FoldDownCoefficients
 */
public enum MonitoringFormat {

    /** 7.1.4 Dolby Atmos — full immersive (12 channels). */
    IMMERSIVE_7_1_4("7.1.4", 12),

    /** 5.1 Surround — standard surround (6 channels). */
    SURROUND_5_1("5.1", 6),

    /** Stereo — two-channel output (2 channels). */
    STEREO("Stereo", 2),

    /** Mono — single-channel output (1 channel). */
    MONO("Mono", 1);

    private final String displayName;
    private final int channelCount;

    MonitoringFormat(String displayName, int channelCount) {
        this.displayName = displayName;
        this.channelCount = channelCount;
    }

    /**
     * Returns the human-readable display name for this format,
     * suitable for the transport bar or monitoring section UI.
     *
     * @return the display name (e.g. "7.1.4", "Stereo")
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the number of output channels for this format.
     *
     * @return the channel count
     */
    public int channelCount() {
        return channelCount;
    }

    /**
     * Returns the {@link SpeakerLayout} corresponding to this format.
     *
     * @return the speaker layout
     */
    public SpeakerLayout toSpeakerLayout() {
        return switch (this) {
            case IMMERSIVE_7_1_4 -> SpeakerLayout.LAYOUT_7_1_4;
            case SURROUND_5_1 -> SpeakerLayout.LAYOUT_5_1;
            case STEREO -> SpeakerLayout.LAYOUT_STEREO;
            case MONO -> SpeakerLayout.LAYOUT_MONO;
        };
    }
}
