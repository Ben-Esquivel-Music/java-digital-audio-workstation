package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Immutable snapshot of loudness metering data based on ITU-R BS.1770.
 *
 * <p>Contains momentary, short-term, and integrated LUFS values along
 * with dynamic range information. Used to drive loudness meters and
 * verify compliance with streaming platform targets.</p>
 *
 * @param momentaryLufs   momentary loudness (400 ms window)
 * @param shortTermLufs   short-term loudness (3 s window)
 * @param integratedLufs  integrated loudness (entire program)
 * @param loudnessRange   loudness range (LRA) in LU
 * @param truePeakDbfs    true peak level in dBFS
 */
public record LoudnessData(
        double momentaryLufs,
        double shortTermLufs,
        double integratedLufs,
        double loudnessRange,
        double truePeakDbfs
) {

    /** Silence — no signal measured. */
    public static final LoudnessData SILENCE = new LoudnessData(
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            Double.NEGATIVE_INFINITY
    );
}
