package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Standard frequency band ranges used for color-coding the spectrum display.
 *
 * <p>Engineers commonly divide the audible spectrum into five bands
 * for quick visual identification of problem frequencies during mixing
 * and mastering.</p>
 */
public enum FrequencyRange {

    /** Sub-bass: 20 Hz – 60 Hz (rumble, sub-kick, bass synths). */
    SUB_BASS("Sub-Bass", 20.0, 60.0),

    /** Bass: 60 Hz – 250 Hz (bass guitar, kick body, low male vocals). */
    BASS("Bass", 60.0, 250.0),

    /** Mids: 250 Hz – 2 kHz (vocals, guitars, snare body). */
    MIDS("Mids", 250.0, 2000.0),

    /** High-mids: 2 kHz – 6 kHz (vocal presence, guitar attack). */
    HIGH_MIDS("High-Mids", 2000.0, 6000.0),

    /** Highs: 6 kHz – 20 kHz (cymbals, air, sibilance). */
    HIGHS("Highs", 6000.0, 20000.0);

    private final String displayName;
    private final double lowFrequencyHz;
    private final double highFrequencyHz;

    FrequencyRange(String displayName, double lowFrequencyHz, double highFrequencyHz) {
        this.displayName = displayName;
        this.lowFrequencyHz = lowFrequencyHz;
        this.highFrequencyHz = highFrequencyHz;
    }

    /**
     * Returns a human-readable display name for this frequency range.
     *
     * @return display name (e.g. "Sub-Bass", "High-Mids")
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the lower boundary frequency in Hz (inclusive).
     *
     * @return low frequency boundary in Hz
     */
    public double lowFrequencyHz() {
        return lowFrequencyHz;
    }

    /**
     * Returns the upper boundary frequency in Hz (exclusive).
     *
     * @return high frequency boundary in Hz
     */
    public double highFrequencyHz() {
        return highFrequencyHz;
    }

    /**
     * Returns the frequency range that contains the given frequency.
     *
     * <p>Frequencies below 20 Hz are mapped to {@link #SUB_BASS},
     * frequencies above 20 kHz are mapped to {@link #HIGHS}.</p>
     *
     * @param frequencyHz the frequency in Hz
     * @return the containing frequency range
     */
    public static FrequencyRange forFrequency(double frequencyHz) {
        for (FrequencyRange range : values()) {
            if (frequencyHz >= range.lowFrequencyHz && frequencyHz < range.highFrequencyHz) {
                return range;
            }
        }
        return frequencyHz < 20.0 ? SUB_BASS : HIGHS;
    }
}
