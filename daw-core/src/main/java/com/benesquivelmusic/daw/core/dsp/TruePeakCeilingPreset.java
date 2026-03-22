package com.benesquivelmusic.daw.core.dsp;

/**
 * Platform-specific true peak ceiling presets for streaming and broadcast compliance.
 *
 * <p>Each preset defines the maximum true peak level (in dBTP) required
 * by the corresponding platform or standard. Using the correct ceiling
 * prevents intersample clipping after lossy encoding.</p>
 */
public enum TruePeakCeilingPreset {

    /** Spotify loudness normalization target: −1.0 dBTP ceiling. */
    SPOTIFY(-1.0),

    /** Apple Music / iTunes Sound Check: −1.0 dBTP ceiling. */
    APPLE_MUSIC(-1.0),

    /** YouTube loudness normalization: −1.0 dBTP ceiling. */
    YOUTUBE(-1.0),

    /** EBU R128 broadcast standard: −1.0 dBTP ceiling (general). */
    BROADCAST_EBU(-1.0),

    /** ATSC A/85 broadcast standard (US): −2.0 dBTP ceiling. */
    BROADCAST_ATSC(-2.0),

    /** Strict broadcast / high-quality master: −0.5 dBTP ceiling. */
    BROADCAST_STRICT(-0.5),

    /** Mastering default: −0.3 dBTP ceiling. */
    MASTERING(-0.3);

    private final double ceilingDbtp;

    TruePeakCeilingPreset(double ceilingDbtp) {
        this.ceilingDbtp = ceilingDbtp;
    }

    /**
     * Returns the true peak ceiling value in dBTP.
     *
     * @return ceiling in dBTP (always ≤ 0)
     */
    public double getCeilingDbtp() {
        return ceilingDbtp;
    }
}
