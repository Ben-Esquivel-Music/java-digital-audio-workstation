package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Immutable snapshot of audio level metering data for a single channel.
 *
 * <p>Contains peak, RMS, and true peak values in both linear and dB scales,
 * suitable for driving VU meters, peak indicators, and true peak compliance
 * displays for streaming and broadcast.</p>
 *
 * @param peakLinear     peak amplitude in the range [0.0, 1.0+]
 * @param rmsLinear      RMS amplitude in the range [0.0, 1.0+]
 * @param peakDb         peak amplitude in dB (0 dB = full scale)
 * @param rmsDb          RMS amplitude in dB (0 dB = full scale)
 * @param clipping       {@code true} if the signal exceeded 0 dBFS
 * @param truePeakLinear true (intersample) peak amplitude via ITU-R BS.1770-4 oversampling
 * @param truePeakDbtp   true peak amplitude in dBTP
 */
public record LevelData(double peakLinear, double rmsLinear, double peakDb, double rmsDb, boolean clipping,
                        double truePeakLinear, double truePeakDbtp) {

    /** Silence — no signal. */
    public static final LevelData SILENCE = new LevelData(0.0, 0.0,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, false,
            0.0, Double.NEGATIVE_INFINITY);

    public LevelData {
        if (peakLinear < 0) {
            throw new IllegalArgumentException("peakLinear must not be negative: " + peakLinear);
        }
        if (rmsLinear < 0) {
            throw new IllegalArgumentException("rmsLinear must not be negative: " + rmsLinear);
        }
        if (truePeakLinear < 0) {
            throw new IllegalArgumentException("truePeakLinear must not be negative: " + truePeakLinear);
        }
    }

    /**
     * Convenience constructor without true peak data (backward compatible).
     *
     * <p>True peak values default to the sample peak values.</p>
     */
    public LevelData(double peakLinear, double rmsLinear, double peakDb, double rmsDb, boolean clipping) {
        this(peakLinear, rmsLinear, peakDb, rmsDb, clipping, peakLinear, peakDb);
    }
}
