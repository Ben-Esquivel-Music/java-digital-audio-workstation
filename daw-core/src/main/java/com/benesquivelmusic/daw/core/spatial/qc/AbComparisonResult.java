package com.benesquivelmusic.daw.core.spatial.qc;

import java.util.Objects;

/**
 * Immutable result of an A/B comparison between a multi-channel mix render
 * and a multi-channel reference mix.
 *
 * @param mixRmsDb       per-channel mix RMS levels in dBFS
 * @param refRmsDb       per-channel reference RMS levels in dBFS
 * @param deltasDb       per-channel level deltas in dB ({@code mix − reference})
 * @param correlations   per-channel Pearson correlation in {@code [-1, 1]}
 * @param bedRmsDeltaDb  full-bed RMS delta in dB
 * @param alignmentSamples integer sample offset of mix relative to reference
 *                         (positive = mix lags), search bounded by
 *                         {@link AtmosAbComparator#MAX_ALIGNMENT_MS}
 * @param alignmentMs    same value as {@code alignmentSamples} expressed in ms
 * @param matchScore     overall match score in {@code [0, 1]} for UI use
 *                       (1.0 = perfect, 0.0 = completely uncorrelated)
 *
 * @see AtmosAbComparator
 */
public record AbComparisonResult(
        double[] mixRmsDb,
        double[] refRmsDb,
        double[] deltasDb,
        double[] correlations,
        double bedRmsDeltaDb,
        int alignmentSamples,
        double alignmentMs,
        double matchScore) {

    /** Validates the per-channel arrays are non-null and equally sized. */
    public AbComparisonResult {
        Objects.requireNonNull(mixRmsDb, "mixRmsDb must not be null");
        Objects.requireNonNull(refRmsDb, "refRmsDb must not be null");
        Objects.requireNonNull(deltasDb, "deltasDb must not be null");
        Objects.requireNonNull(correlations, "correlations must not be null");
        if (mixRmsDb.length != refRmsDb.length
                || mixRmsDb.length != deltasDb.length
                || mixRmsDb.length != correlations.length) {
            throw new IllegalArgumentException(
                    "per-channel arrays must share the same length");
        }
    }

    /** Returns the channel count this result describes. */
    public int channelCount() {
        return mixRmsDb.length;
    }
}
