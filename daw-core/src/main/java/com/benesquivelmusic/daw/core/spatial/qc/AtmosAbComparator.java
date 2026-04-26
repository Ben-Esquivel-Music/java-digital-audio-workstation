package com.benesquivelmusic.daw.core.spatial.qc;

import java.util.Objects;

/**
 * Quality-control utility that compares a multi-channel mix render
 * (typically the DAW's internal Atmos render) against a multi-channel
 * reference mix (typically a recording of the Dolby Atmos Renderer's
 * monitor output) and reports per-channel and overall match metrics.
 *
 * <p>The comparator does not modify either signal. It produces an
 * {@link AbComparisonResult} containing:</p>
 *
 * <ul>
 *   <li>per-channel level deltas in dB (mix − reference RMS),</li>
 *   <li>full-bed RMS delta in dB,</li>
 *   <li>per-channel Pearson correlation in {@code [-1, 1]},</li>
 *   <li>a sample-accurate time-alignment estimate within
 *       {@code ±50 ms} (positive = mix lags reference),</li>
 *   <li>an overall match score in {@code [0, 1]} for UI colour coding.</li>
 * </ul>
 *
 * <p>The implementation is deliberately allocation-light and
 * single-threaded — it is invoked off the audio thread by the
 * {@code AtmosAbView} when the user requests a fresh comparison.</p>
 *
 * <p>Both buffers must use a {@code [channel][sample]} layout and share
 * the same channel count; an empty buffer or mismatched channel count
 * causes {@link #compare} to throw {@link IllegalArgumentException}.</p>
 */
public final class AtmosAbComparator {

    /** The maximum cross-correlation search window for time alignment. */
    public static final double MAX_ALIGNMENT_MS = 50.0;

    /** Floor (in dB) reported when an RMS measurement underflows. */
    public static final double RMS_FLOOR_DB = -160.0;

    private static final double LINEAR_FLOOR = 1.0e-9;

    private final double sampleRate;

    /**
     * Creates a comparator for buffers sampled at the given rate.
     *
     * @param sampleRate the sample rate in Hz (must be {@code > 0})
     * @throws IllegalArgumentException if {@code sampleRate <= 0}
     */
    public AtmosAbComparator(double sampleRate) {
        if (sampleRate <= 0.0) {
            throw new IllegalArgumentException(
                    "sampleRate must be positive: " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    /** Returns the sample rate this comparator was configured with. */
    public double getSampleRate() {
        return sampleRate;
    }

    /**
     * Compares the {@code mix} render against the {@code reference} mix and
     * returns the per-channel and overall match metrics.
     *
     * @param mix       mix audio in {@code [channel][sample]} layout
     * @param reference reference audio in {@code [channel][sample]} layout
     * @return the comparison result
     * @throws IllegalArgumentException if the buffers are empty or have
     *                                  mismatched channel counts
     * @throws NullPointerException     if either argument is {@code null}
     */
    public AbComparisonResult compare(float[][] mix, float[][] reference) {
        Objects.requireNonNull(mix, "mix must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        if (mix.length == 0 || reference.length == 0) {
            throw new IllegalArgumentException("buffers must not be empty");
        }
        if (mix.length != reference.length) {
            throw new IllegalArgumentException(
                    "channel count mismatch: mix=" + mix.length
                            + " reference=" + reference.length);
        }

        int channels = mix.length;
        double[] mixRmsDb = new double[channels];
        double[] refRmsDb = new double[channels];
        double[] deltasDb = new double[channels];
        double[] correlations = new double[channels];

        double mixSumSq = 0.0;
        double refSumSq = 0.0;
        long totalSamples = 0;

        for (int c = 0; c < channels; c++) {
            float[] m = mix[c];
            float[] r = reference[c];
            if (m == null || r == null) {
                throw new IllegalArgumentException(
                        "channel buffer must not be null at index " + c);
            }
            int n = Math.min(m.length, r.length);

            double mSq = 0.0, rSq = 0.0, mr = 0.0;
            for (int i = 0; i < n; i++) {
                double mv = m[i];
                double rv = r[i];
                mSq += mv * mv;
                rSq += rv * rv;
                mr += mv * rv;
            }
            mixSumSq += mSq;
            refSumSq += rSq;
            totalSamples += n;

            double mRms = n == 0 ? 0.0 : Math.sqrt(mSq / n);
            double rRms = n == 0 ? 0.0 : Math.sqrt(rSq / n);
            mixRmsDb[c] = toDb(mRms);
            refRmsDb[c] = toDb(rRms);
            deltasDb[c] = mixRmsDb[c] - refRmsDb[c];

            double denom = Math.sqrt(mSq * rSq);
            correlations[c] = denom < LINEAR_FLOOR ? 0.0 : mr / denom;
        }

        double mixBedRms = totalSamples == 0 ? 0.0
                : Math.sqrt(mixSumSq / totalSamples);
        double refBedRms = totalSamples == 0 ? 0.0
                : Math.sqrt(refSumSq / totalSamples);
        double bedRmsDeltaDb = toDb(mixBedRms) - toDb(refBedRms);

        int alignmentSamples = estimateAlignmentSamples(mix, reference);
        double alignmentMs = alignmentSamples * 1000.0 / sampleRate;

        double matchScore = computeMatchScore(deltasDb, correlations);

        return new AbComparisonResult(
                mixRmsDb, refRmsDb, deltasDb, correlations,
                bedRmsDeltaDb, alignmentSamples, alignmentMs, matchScore);
    }

    /**
     * Estimates a per-channel gain trim (in dB) that, when added to the
     * mix, would minimise each channel's level delta against the reference.
     *
     * <p>This is the trim returned to the user when they request the
     * comparator's optional auto-trim feature. The resulting array has the
     * same length as the buffers' channel count.</p>
     *
     * @param mix       mix audio in {@code [channel][sample]} layout
     * @param reference reference audio in {@code [channel][sample]} layout
     * @return per-channel gain trims in dB (one per channel)
     * @throws IllegalArgumentException if the buffers are empty or have
     *                                  mismatched channel counts
     */
    public double[] estimateAutoTrim(float[][] mix, float[][] reference) {
        AbComparisonResult r = compare(mix, reference);
        double[] trims = new double[r.deltasDb().length];
        for (int c = 0; c < trims.length; c++) {
            // Mix is `delta` dB louder than reference → subtract `delta` dB
            // from the mix to match.
            trims[c] = -r.deltasDb()[c];
        }
        return trims;
    }

    /**
     * Estimates the integer-sample offset that maximises the cross-correlation
     * of {@code mix} and {@code reference}, using the first available channel
     * pair as the alignment reference. Returns a value such that
     * {@code reference[i] ≈ mix[i + offset]}, i.e. positive means the mix
     * lags the reference.
     *
     * <p>Only offsets within {@code ±50 ms} are searched.</p>
     *
     * @param mix       mix audio in {@code [channel][sample]} layout
     * @param reference reference audio in {@code [channel][sample]} layout
     * @return the integer sample offset of mix relative to reference
     */
    public int estimateAlignmentSamples(float[][] mix, float[][] reference) {
        int maxLag = (int) Math.round(MAX_ALIGNMENT_MS * sampleRate / 1000.0);
        // Sum cross-correlation over channels to be robust against a
        // single mostly-silent channel; this also handles the bed as a
        // whole, which is what the user perceives.
        int channels = Math.min(mix.length, reference.length);
        int bestLag = 0;
        double bestScore = -Double.MAX_VALUE;

        int n = Integer.MAX_VALUE;
        for (int c = 0; c < channels; c++) {
            n = Math.min(n, Math.min(mix[c].length, reference[c].length));
        }
        if (n <= 0) {
            return 0;
        }

        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double sum = 0.0;
            int start = Math.max(0, -lag);
            int end = Math.min(n, n - lag);
            int span = end - start;
            if (span <= 0) {
                continue;
            }
            for (int c = 0; c < channels; c++) {
                float[] m = mix[c];
                float[] r = reference[c];
                for (int i = start; i < end; i++) {
                    sum += (double) m[i + lag] * (double) r[i];
                }
            }
            // Normalise by overlap length so that lags with shorter
            // overlap don't artificially win. On ties (e.g. perfectly
            // periodic signals) prefer the lag closest to zero, which
            // matches the user's intuition for "no offset".
            double score = sum / span;
            if (score > bestScore
                    || (score == bestScore && Math.abs(lag) < Math.abs(bestLag))) {
                bestScore = score;
                bestLag = lag;
            }
        }
        return bestLag;
    }

    private static double toDb(double linear) {
        if (linear <= LINEAR_FLOOR) {
            return RMS_FLOOR_DB;
        }
        return 20.0 * Math.log10(linear);
    }

    /**
     * Combines per-channel deltas and correlations into a single match score
     * in {@code [0, 1]}. {@code 1.0} means an exact match (no level
     * difference, perfect correlation); the score decays smoothly with
     * deviations.
     */
    private static double computeMatchScore(double[] deltasDb,
                                            double[] correlations) {
        if (deltasDb.length == 0) {
            return 0.0;
        }
        double levelScore = 0.0;
        double corrScore = 0.0;
        for (int c = 0; c < deltasDb.length; c++) {
            double d = Math.abs(deltasDb[c]);
            // Map 0 dB → 1.0, 6 dB → ~0.5, 12 dB → ~0.25
            levelScore += Math.exp(-d / 8.6858896);  // 6 dB ≈ ln(2) * 8.69
            corrScore += Math.max(0.0, correlations[c]);
        }
        levelScore /= deltasDb.length;
        corrScore /= deltasDb.length;
        return Math.max(0.0, Math.min(1.0, 0.5 * levelScore + 0.5 * corrScore));
    }
}
