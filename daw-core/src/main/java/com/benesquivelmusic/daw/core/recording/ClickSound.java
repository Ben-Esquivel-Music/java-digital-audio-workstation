package com.benesquivelmusic.daw.core.recording;

/**
 * Built-in click sound presets for the metronome.
 *
 * <p>Each preset defines a pair of frequencies (accent and normal)
 * that give the metronome click a distinct character. The accent
 * frequency is used on the downbeat (beat 1) of each bar, and the
 * normal frequency is used on all other beats.</p>
 */
public enum ClickSound {

    /** A warm, mid-range click resembling a woodblock. */
    WOODBLOCK(1000.0, 800.0),

    /** A bright, metallic click resembling a cowbell. */
    COWBELL(1200.0, 900.0),

    /** A sharp, synthetic beep for a modern electronic feel. */
    ELECTRONIC(1500.0, 1100.0);

    private final double accentFrequencyHz;
    private final double normalFrequencyHz;

    ClickSound(double accentFrequencyHz, double normalFrequencyHz) {
        this.accentFrequencyHz = accentFrequencyHz;
        this.normalFrequencyHz = normalFrequencyHz;
    }

    /**
     * Returns the accent (downbeat) click frequency in Hz.
     *
     * @return the accent frequency
     */
    public double getAccentFrequencyHz() {
        return accentFrequencyHz;
    }

    /**
     * Returns the normal (non-downbeat) click frequency in Hz.
     *
     * @return the normal frequency
     */
    public double getNormalFrequencyHz() {
        return normalFrequencyHz;
    }
}
