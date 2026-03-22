package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Immutable snapshot of stereo correlation metering data.
 *
 * <p>The correlation coefficient ranges from {@code -1.0} (fully
 * out-of-phase) to {@code +1.0} (fully in-phase / mono). A value
 * near {@code 0.0} indicates uncorrelated stereo content.</p>
 *
 * <p>This data drives correlation meters and goniometer/vectorscope
 * displays, as recommended for professional mastering workflows.</p>
 *
 * @param correlation     the correlation coefficient in the range [-1.0, 1.0]
 * @param midLevel        mid (sum) channel RMS level in dB
 * @param sideLevel       side (difference) channel RMS level in dB
 * @param stereoBalance   stereo balance from -1.0 (left) to 1.0 (right)
 */
public record CorrelationData(double correlation, double midLevel, double sideLevel, double stereoBalance) {

    /** Mono silence. */
    public static final CorrelationData SILENCE = new CorrelationData(
            1.0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0);

    public CorrelationData {
        if (correlation < -1.0 || correlation > 1.0) {
            throw new IllegalArgumentException(
                    "correlation must be in range [-1.0, 1.0]: " + correlation);
        }
        if (stereoBalance < -1.0 || stereoBalance > 1.0) {
            throw new IllegalArgumentException(
                    "stereoBalance must be in range [-1.0, 1.0]: " + stereoBalance);
        }
    }
}
