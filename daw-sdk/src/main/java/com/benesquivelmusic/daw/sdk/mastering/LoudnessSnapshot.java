package com.benesquivelmusic.daw.sdk.mastering;

/**
 * Immutable EBU R128 loudness snapshot — the four canonical loudness
 * measurements plus true peak, captured at a single instant in time.
 *
 * <p>This record is the SDK-level data carrier published by the
 * loudness engine to drive the EBU R128 meter view (M, S, I, LRA
 * columns plus a true-peak bar) and the numeric readout panel.</p>
 *
 * <p>Sources: EBU Tech 3341 (loudness metering), EBU Tech 3342
 * (loudness range), and ITU-R BS.1770 (K-weighting and gating).</p>
 *
 * @param momentaryLufs    momentary loudness — 400 ms sliding window (LUFS)
 * @param shortTermLufs    short-term loudness — 3 s sliding window (LUFS)
 * @param integratedLufs   integrated loudness — gated, since last reset (LUFS)
 * @param loudnessRangeLu  loudness range (LRA) — distribution of short-term
 *                         readings in loudness units (LU)
 * @param truePeakDbtp     true-peak level (dBTP)
 */
public record LoudnessSnapshot(
        double momentaryLufs,
        double shortTermLufs,
        double integratedLufs,
        double loudnessRangeLu,
        double truePeakDbtp
) {

    /** Silence — no signal measured. */
    public static final LoudnessSnapshot SILENCE = new LoudnessSnapshot(
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            Double.NEGATIVE_INFINITY
    );

    /**
     * Returns the signed difference between the integrated loudness and
     * the supplied platform target, in loudness units (LU).
     *
     * <p>Positive values mean the program is louder than the target,
     * negative values mean it is quieter. Returns
     * {@link Double#NaN} if the integrated loudness is not yet
     * measurable (i.e. only silence has been processed).</p>
     *
     * @param targetIntegratedLufs the target integrated loudness in LUFS
     * @return signed delta in LU, or {@code NaN} if not measurable
     */
    public double targetDeltaLu(double targetIntegratedLufs) {
        if (Double.isInfinite(integratedLufs) || Double.isNaN(integratedLufs)) {
            return Double.NaN;
        }
        return integratedLufs - targetIntegratedLufs;
    }
}
