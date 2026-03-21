package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Immutable snapshot of audio level metering data for a single channel.
 *
 * <p>Contains peak and RMS values in both linear and dB scales,
 * suitable for driving VU meters and peak indicators.</p>
 *
 * @param peakLinear peak amplitude in the range [0.0, 1.0+]
 * @param rmsLinear  RMS amplitude in the range [0.0, 1.0+]
 * @param peakDb     peak amplitude in dB (0 dB = full scale)
 * @param rmsDb      RMS amplitude in dB (0 dB = full scale)
 * @param clipping   {@code true} if the signal exceeded 0 dBFS
 */
public record LevelData(double peakLinear, double rmsLinear, double peakDb, double rmsDb, boolean clipping) {

    /** Silence — no signal. */
    public static final LevelData SILENCE = new LevelData(0.0, 0.0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, false);

    public LevelData {
        if (peakLinear < 0) {
            throw new IllegalArgumentException("peakLinear must not be negative: " + peakLinear);
        }
        if (rmsLinear < 0) {
            throw new IllegalArgumentException("rmsLinear must not be negative: " + rmsLinear);
        }
    }
}
