package com.benesquivelmusic.daw.sdk.mastering;

/**
 * Immutable EBU R128 loudness snapshot — the four canonical loudness
 * measurements plus a peak indicator, captured at a single instant in time.
 *
 * <p>This record is the SDK-level data carrier published by the
 * loudness engine to drive the EBU R128 meter view (M, S, I, LRA
 * columns plus a peak bar) and the numeric readout panel.</p>
 *
 * <p>Sources: EBU Tech 3341 (loudness metering), EBU Tech 3342
 * (loudness range), and ITU-R BS.1770 (K-weighting and gating).</p>
 *
 * <p><b>Peak measurement note:</b> {@link #samplePeakDbfs} is the
 * sample-domain peak (max |sample|) expressed in dBFS, as produced by
 * the current {@code LoudnessMeter} implementation. It is <em>not</em>
 * a true-peak (dBTP) measurement — a true-peak meter requires
 * oversampled inter-sample peak reconstruction, which is tracked as
 * future work and will introduce a separate {@code truePeakDbtp}
 * accessor when implemented.</p>
 *
 * @param momentaryLufs    momentary loudness — 400 ms sliding window (LUFS)
 * @param shortTermLufs    short-term loudness — 3 s sliding window (LUFS)
 * @param integratedLufs   integrated loudness — gated, since last reset (LUFS)
 * @param loudnessRangeLu  loudness range (LRA) — distribution of short-term
 *                         readings in loudness units (LU)
 * @param samplePeakDbfs   sample-domain peak level (dBFS)
 */
public record LoudnessSnapshot(
        double momentaryLufs,
        double shortTermLufs,
        double integratedLufs,
        double loudnessRangeLu,
        double samplePeakDbfs
) {

    /**
     * EBU R128 absolute gating threshold, in LUFS. Integrated readings
     * at or below this floor are treated as "not yet measurable" (no
     * gated blocks have been accumulated).
     */
    public static final double ABSOLUTE_GATE_LUFS = -70.0;

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
     * measurable — that is, when {@link #integratedLufs} is non-finite
     * or at/below the EBU R128 absolute gating threshold of
     * {@value #ABSOLUTE_GATE_LUFS} LUFS (no gated blocks have yet
     * accumulated; only silence or near-silence has been processed).</p>
     *
     * @param targetIntegratedLufs the target integrated loudness in LUFS
     * @return signed delta in LU, or {@code NaN} if not measurable
     */
    public double targetDeltaLu(double targetIntegratedLufs) {
        if (!Double.isFinite(integratedLufs) || integratedLufs <= ABSOLUTE_GATE_LUFS) {
            return Double.NaN;
        }
        return integratedLufs - targetIntegratedLufs;
    }
}
