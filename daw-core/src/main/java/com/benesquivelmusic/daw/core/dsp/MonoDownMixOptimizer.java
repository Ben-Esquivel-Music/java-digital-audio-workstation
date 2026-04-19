package com.benesquivelmusic.daw.core.dsp;

/**
 * Stereo-to-mono down-mix optimizer that minimizes phase cancellation
 * artifacts.
 *
 * <p>Naive {@code (L + R) / 2} summing causes destructive cancellation of
 * out-of-phase stereo content, reducing bass and losing spatial elements.
 * This class offers three down-mix strategies of increasing complexity
 * that trade off CPU cost against mono fidelity:</p>
 *
 * <ul>
 *   <li>{@link Mode#STANDARD_SUM} — baseline {@code (L + R) / 2}.</li>
 *   <li>{@link Mode#POLARITY_ADAPTIVE} — splits the signal into frequency
 *       bands using {@link CrossoverFilter}s, detects negative per-band
 *       correlation between {@code L} and {@code R}, and inverts the right
 *       component's polarity in those bands before summing. Avoids the
 *       band-local cancellation that dominates naive summing of anti-phase
 *       material.</li>
 *   <li>{@link Mode#ENERGY_PRESERVING} — sums with gain compensation so the
 *       mono RMS matches the average of the two channel RMS levels, so
 *       bass and other anti-correlated content is not audibly attenuated.</li>
 * </ul>
 *
 * <p>Implements the low-complexity robust-mono-downmixing methods presented
 * in the AES research "Low Complexity Methods for Robust Stereo-to-Mono
 * Down-mixing" (2022).</p>
 *
 * <h2>Mono compatibility score</h2>
 * <p>{@link #computeReport} quantifies how much energy is lost by the
 * standard {@code (L + R) / 2} sum versus the optimized output and returns
 * a normalized compatibility score in {@code [0, 1]} where {@code 1.0}
 * indicates perfect mono compatibility (no energy loss).</p>
 *
 * <h2>Typical applications</h2>
 * <ul>
 *   <li>Mono compatibility check during mastering</li>
 *   <li>Podcast / voice mono export</li>
 *   <li>Broadcast compatibility (AM radio, telephony)</li>
 * </ul>
 *
 * <p>This class is not real-time safe by itself (the polarity-adaptive
 * path allocates per-band buffers on each {@link #process} call); it is
 * intended for offline export and analysis contexts.</p>
 */
public final class MonoDownMixOptimizer {

    /** Default crossover edges used by {@link Mode#POLARITY_ADAPTIVE}. */
    public static final double[] DEFAULT_BAND_EDGES = {250.0, 2000.0, 6000.0};

    /** Down-mix strategy. */
    public enum Mode {
        /** Baseline {@code (L + R) / 2}. */
        STANDARD_SUM,
        /** Per-band polarity-adaptive summing using {@link CrossoverFilter}. */
        POLARITY_ADAPTIVE,
        /** Energy-preserving sum with RMS gain compensation. */
        ENERGY_PRESERVING
    }

    /**
     * Mono-compatibility report comparing the standard {@code (L + R) / 2}
     * sum against an optimized mono output.
     *
     * @param score           mono compatibility score in {@code [0, 1]};
     *                        {@code 1.0} = no energy lost vs. the channel
     *                        average, {@code 0.0} = total cancellation
     * @param energyLossDb    energy loss of the standard sum compared to the
     *                        average channel energy, in dB (always ≤ 0);
     *                        {@code 0.0} dB indicates perfect mono compatibility
     * @param standardSumRms  RMS of the standard {@code (L + R) / 2} sum
     * @param optimizedRms    RMS of the supplied optimized mono buffer
     */
    public record MonoCompatibilityReport(
            double score,
            double energyLossDb,
            double standardSumRms,
            double optimizedRms) {
    }

    private static final double DB_FLOOR = -120.0;

    private final Mode mode;
    private final double sampleRate;
    private final double[] bandEdges;

    /**
     * Creates an optimizer with the given mode and sample rate, using the
     * {@link #DEFAULT_BAND_EDGES default band edges} for polarity-adaptive
     * mode.
     *
     * @param mode       the down-mix strategy
     * @param sampleRate the audio sample rate in Hz
     */
    public MonoDownMixOptimizer(Mode mode, double sampleRate) {
        this(mode, sampleRate, DEFAULT_BAND_EDGES);
    }

    /**
     * Creates an optimizer with the given mode, sample rate, and crossover
     * band edges.
     *
     * @param mode       the down-mix strategy
     * @param sampleRate the audio sample rate in Hz
     * @param bandEdges  sorted-ascending crossover frequencies in Hz; produces
     *                   {@code bandEdges.length + 1} bands (only used by
     *                   {@link Mode#POLARITY_ADAPTIVE}); a defensive copy is
     *                   stored
     * @throws IllegalArgumentException if any argument is invalid
     */
    public MonoDownMixOptimizer(Mode mode, double sampleRate, double[] bandEdges) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bandEdges == null) {
            throw new IllegalArgumentException("bandEdges must not be null");
        }
        double nyquist = sampleRate / 2.0;
        for (int i = 0; i < bandEdges.length; i++) {
            if (bandEdges[i] <= 0 || bandEdges[i] >= nyquist) {
                throw new IllegalArgumentException(
                        "bandEdges[" + i + "] must be in (0, " + nyquist + "): " + bandEdges[i]);
            }
            if (i > 0 && bandEdges[i] <= bandEdges[i - 1]) {
                throw new IllegalArgumentException(
                        "bandEdges must be strictly ascending");
            }
        }
        this.mode = mode;
        this.sampleRate = sampleRate;
        this.bandEdges = bandEdges.clone();
    }

    /** @return the configured mode. */
    public Mode getMode() {
        return mode;
    }

    /**
     * Produces a mono down-mix of the given stereo buffers.
     *
     * @param left      left channel input
     * @param right     right channel input
     * @param mono      mono output buffer (at least {@code numFrames} long);
     *                  may alias neither {@code left} nor {@code right}
     * @param numFrames number of frames to process
     */
    public void process(float[] left, float[] right, float[] mono, int numFrames) {
        if (left == null || right == null || mono == null) {
            throw new IllegalArgumentException("buffers must not be null");
        }
        if (numFrames < 0) {
            throw new IllegalArgumentException("numFrames must not be negative: " + numFrames);
        }
        if (left.length < numFrames || right.length < numFrames || mono.length < numFrames) {
            throw new IllegalArgumentException("buffers are shorter than numFrames");
        }

        switch (mode) {
            case STANDARD_SUM -> standardSum(left, right, mono, numFrames);
            case POLARITY_ADAPTIVE -> polarityAdaptive(left, right, mono, numFrames);
            case ENERGY_PRESERVING -> energyPreserving(left, right, mono, numFrames);
        }
    }

    private static void standardSum(float[] left, float[] right, float[] mono, int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            mono[i] = (left[i] + right[i]) * 0.5f;
        }
    }

    /**
     * Multiband polarity-adaptive sum:
     * <ol>
     *   <li>Split {@code L} and {@code R} into {@code N = bandEdges.length + 1}
     *       bands using cascaded {@link CrossoverFilter}s.</li>
     *   <li>For each band, compute the normalized cross-correlation between
     *       the band-limited {@code L} and {@code R}.</li>
     *   <li>If the band correlation is negative, invert the polarity of the
     *       band-limited {@code R} before summing — this turns destructive
     *       cancellation into constructive reinforcement.</li>
     *   <li>Sum all bands and scale by {@code 0.5} to preserve the same gain
     *       reference as the standard sum.</li>
     * </ol>
     */
    private void polarityAdaptive(float[] left, float[] right, float[] mono, int numFrames) {
        int numBands = bandEdges.length + 1;
        float[][] bandsL = new float[numBands][numFrames];
        float[][] bandsR = new float[numBands][numFrames];

        splitBands(left, bandsL, numFrames);
        splitBands(right, bandsR, numFrames);

        for (int i = 0; i < numFrames; i++) {
            mono[i] = 0.0f;
        }

        for (int b = 0; b < numBands; b++) {
            double corr = correlation(bandsL[b], bandsR[b], numFrames);
            float sign = corr < 0 ? -1.0f : 1.0f;
            float[] bl = bandsL[b];
            float[] br = bandsR[b];
            for (int i = 0; i < numFrames; i++) {
                mono[i] += (bl[i] + sign * br[i]) * 0.5f;
            }
        }
    }

    /**
     * Splits a mono buffer into {@code bandEdges.length + 1} bands using a
     * cascade of Linkwitz-Riley crossovers. Each crossover splits the
     * remaining high-pass path of the previous stage, so the sum of all
     * bands reconstructs the input.
     */
    private void splitBands(float[] input, float[][] bands, int numFrames) {
        int numBands = bandEdges.length + 1;
        if (numBands == 1) {
            System.arraycopy(input, 0, bands[0], 0, numFrames);
            return;
        }
        float[] remaining = new float[numFrames];
        System.arraycopy(input, 0, remaining, 0, numFrames);
        float[] scratchLow = new float[numFrames];
        float[] scratchHigh = new float[numFrames];
        for (int b = 0; b < bandEdges.length; b++) {
            CrossoverFilter xo = new CrossoverFilter(sampleRate, bandEdges[b]);
            xo.process(remaining, scratchLow, scratchHigh, 0, numFrames);
            System.arraycopy(scratchLow, 0, bands[b], 0, numFrames);
            System.arraycopy(scratchHigh, 0, remaining, 0, numFrames);
        }
        System.arraycopy(remaining, 0, bands[numBands - 1], 0, numFrames);
    }

    private static double correlation(float[] a, float[] b, int numFrames) {
        double sumAB = 0.0;
        double sumAA = 0.0;
        double sumBB = 0.0;
        for (int i = 0; i < numFrames; i++) {
            double x = a[i];
            double y = b[i];
            sumAB += x * y;
            sumAA += x * x;
            sumBB += y * y;
        }
        double denominator = Math.sqrt(sumAA * sumBB);
        if (denominator <= 0.0) {
            return 1.0;
        }
        double c = sumAB / denominator;
        return Math.max(-1.0, Math.min(1.0, c));
    }

    /**
     * Energy-preserving sum: the standard {@code (L + R) / 2} output is
     * scaled so its RMS matches the average RMS of the two input channels.
     * This compensates for the energy loss caused by anti-correlated
     * content (most notably in the low-frequency region).
     */
    private static void energyPreserving(float[] left, float[] right,
                                         float[] mono, int numFrames) {
        if (numFrames == 0) {
            return;
        }
        double sumLL = 0.0;
        double sumRR = 0.0;
        double sumMM = 0.0;
        for (int i = 0; i < numFrames; i++) {
            double l = left[i];
            double r = right[i];
            double m = (l + r) * 0.5;
            sumLL += l * l;
            sumRR += r * r;
            sumMM += m * m;
            mono[i] = (float) m;
        }
        double rmsL = Math.sqrt(sumLL / numFrames);
        double rmsR = Math.sqrt(sumRR / numFrames);
        double rmsM = Math.sqrt(sumMM / numFrames);
        double targetRms = 0.5 * (rmsL + rmsR);
        if (rmsM <= 0.0 || targetRms <= 0.0) {
            return;
        }
        float gain = (float) (targetRms / rmsM);
        for (int i = 0; i < numFrames; i++) {
            mono[i] *= gain;
        }
    }

    /**
     * Computes a mono-compatibility report for the supplied optimized mono
     * output. Compares the standard {@code (L + R) / 2} RMS against the
     * reference {@code 0.5 * (rmsL + rmsR)} that would be preserved by a
     * perfectly mono-compatible signal.
     *
     * @param left          left channel input
     * @param right         right channel input
     * @param optimizedMono optimized mono output produced by
     *                      {@link #process(float[], float[], float[], int)}
     * @param numFrames     number of frames to analyze
     * @return the compatibility report
     */
    public static MonoCompatibilityReport computeReport(float[] left, float[] right,
                                                        float[] optimizedMono, int numFrames) {
        if (left == null || right == null || optimizedMono == null) {
            throw new IllegalArgumentException("buffers must not be null");
        }
        if (numFrames < 0) {
            throw new IllegalArgumentException("numFrames must not be negative: " + numFrames);
        }
        if (left.length < numFrames || right.length < numFrames
                || optimizedMono.length < numFrames) {
            throw new IllegalArgumentException("buffers are shorter than numFrames");
        }

        if (numFrames == 0) {
            return new MonoCompatibilityReport(1.0, 0.0, 0.0, 0.0);
        }

        double sumLL = 0.0;
        double sumRR = 0.0;
        double sumStdStd = 0.0;
        double sumOptOpt = 0.0;
        for (int i = 0; i < numFrames; i++) {
            double l = left[i];
            double r = right[i];
            double std = (l + r) * 0.5;
            double opt = optimizedMono[i];
            sumLL += l * l;
            sumRR += r * r;
            sumStdStd += std * std;
            sumOptOpt += opt * opt;
        }
        double rmsL = Math.sqrt(sumLL / numFrames);
        double rmsR = Math.sqrt(sumRR / numFrames);
        double rmsStd = Math.sqrt(sumStdStd / numFrames);
        double rmsOpt = Math.sqrt(sumOptOpt / numFrames);
        double reference = 0.5 * (rmsL + rmsR);

        double energyLossDb;
        double score;
        if (reference <= 0.0) {
            energyLossDb = 0.0;
            score = 1.0;
        } else {
            double ratio = rmsStd / reference;
            score = Math.max(0.0, Math.min(1.0, ratio));
            energyLossDb = ratio > 0.0
                    ? Math.min(0.0, 20.0 * Math.log10(ratio))
                    : DB_FLOOR;
        }
        return new MonoCompatibilityReport(score, energyLossDb, rmsStd, rmsOpt);
    }
}
