package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Result of validating a measured loudness against a {@link LoudnessTarget}.
 *
 * <p>Contains the target that was checked, the measured values, whether
 * the loudness and true-peak pass the target thresholds, and a
 * human-readable summary message.</p>
 *
 * @param target               the loudness target used for validation
 * @param measuredIntegratedLufs  the measured integrated loudness in LUFS
 * @param measuredTruePeakDbtp    the measured true-peak level in dBTP
 * @param loudnessPass         {@code true} if integrated loudness is within ±1 LU of the target
 * @param truePeakPass         {@code true} if true peak does not exceed the target max
 * @param message              human-readable validation summary
 */
public record ExportValidationResult(
        LoudnessTarget target,
        double measuredIntegratedLufs,
        double measuredTruePeakDbtp,
        boolean loudnessPass,
        boolean truePeakPass,
        String message
) {

    /**
     * Returns {@code true} if both loudness and true-peak checks passed.
     *
     * @return overall pass/fail
     */
    public boolean passed() {
        return loudnessPass && truePeakPass;
    }
}
