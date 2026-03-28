package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Configurable fold-down coefficients for immersive-to-stereo downmixing.
 *
 * <p>These coefficients control the gain applied to each channel group
 * during the fold-down chain. The defaults follow ITU-R BS.775 and Dolby
 * recommended downmix coefficients (−3 dB ≈ 0.707 for center, surround,
 * and height; 0.0 for LFE).</p>
 *
 * <p>Custom coefficients allow tuning the fold-down for non-standard
 * speaker setups or creative monitoring preferences.</p>
 *
 * @param centerLevel   gain coefficient for the center channel when folding to stereo
 *                      (ITU-R BS.775 default: −3 dB ≈ 0.707)
 * @param surroundLevel gain coefficient for surround channels when folding to stereo
 *                      or rear surround to side surround (default: −3 dB ≈ 0.707)
 * @param lfeLevel      gain coefficient for the LFE channel when folding to stereo
 *                      (default: 0.0, LFE is discarded)
 * @param heightLevel   gain coefficient for height channels when folding to ear level
 *                      (default: −3 dB ≈ 0.707)
 */
public record FoldDownCoefficients(
        double centerLevel,
        double surroundLevel,
        double lfeLevel,
        double heightLevel) {

    /** −3 dB ≈ 0.707 — the standard equal-power fold-down coefficient. */
    private static final double MINUS_3_DB = Math.sqrt(0.5);

    /**
     * ITU-R BS.775 standard coefficients:
     * center = −3 dB, surround = −3 dB, LFE = 0 dB (discarded), height = −3 dB.
     */
    public static final FoldDownCoefficients ITU_R_BS_775 =
            new FoldDownCoefficients(MINUS_3_DB, MINUS_3_DB, 0.0, MINUS_3_DB);

    /**
     * Validates that all coefficients are in the range [0.0, 1.0].
     */
    public FoldDownCoefficients {
        validateRange("centerLevel", centerLevel);
        validateRange("surroundLevel", surroundLevel);
        validateRange("lfeLevel", lfeLevel);
        validateRange("heightLevel", heightLevel);
    }

    private static void validateRange(String name, double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be in [0.0, 1.0] but was " + value);
        }
    }
}
