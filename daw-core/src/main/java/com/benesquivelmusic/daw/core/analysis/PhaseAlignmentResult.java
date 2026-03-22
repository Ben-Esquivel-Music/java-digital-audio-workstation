package com.benesquivelmusic.daw.core.analysis;

/**
 * Immutable result of a phase alignment analysis between two audio tracks.
 *
 * <p>Contains the optimal time offset (in samples) to align the tracks, a
 * polarity recommendation, the peak cross-correlation value, and a spectral
 * coherence score quantifying phase cancellation severity.</p>
 *
 * <p>Based on algorithms for automated detection of phase misalignment and
 * polarity inversion in multi-microphone recordings using cross-correlation
 * and spectral coherence.</p>
 *
 * @param delaySamples       optimal delay in samples to apply to the second track
 *                           for alignment; positive means the second track should
 *                           be delayed (it arrived early), negative means it
 *                           should be advanced (it arrived late)
 * @param polarity           recommended polarity for the second track
 * @param correlationPeak    peak normalized cross-correlation value in [-1.0, 1.0]
 * @param coherenceScore     spectral coherence score in [0.0, 1.0]; 1.0 = perfect
 *                           coherence, 0.0 = complete cancellation
 * @param bandCoherences     per-frequency-band coherence values (may be empty if
 *                           band analysis was not performed)
 */
public record PhaseAlignmentResult(
        int delaySamples,
        Polarity polarity,
        double correlationPeak,
        double coherenceScore,
        double[] bandCoherences) {

    /** Polarity recommendation for a track pair. */
    public enum Polarity {
        /** Normal polarity — no inversion needed. */
        NORMAL,
        /** Inverted polarity — the second track should be phase-inverted. */
        INVERTED
    }

    public PhaseAlignmentResult {
        if (correlationPeak < -1.0 || correlationPeak > 1.0) {
            throw new IllegalArgumentException(
                    "correlationPeak must be in [-1.0, 1.0]: " + correlationPeak);
        }
        if (coherenceScore < 0.0 || coherenceScore > 1.0) {
            throw new IllegalArgumentException(
                    "coherenceScore must be in [0.0, 1.0]: " + coherenceScore);
        }
        if (bandCoherences == null) {
            throw new IllegalArgumentException("bandCoherences must not be null");
        }
    }
}
