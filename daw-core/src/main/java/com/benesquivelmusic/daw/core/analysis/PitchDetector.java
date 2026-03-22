package com.benesquivelmusic.daw.core.analysis;

/**
 * Pure-Java pitch detector using the YIN algorithm.
 *
 * <p>Detects the fundamental frequency (F0) of a monophonic audio signal.
 * The YIN algorithm computes a cumulative mean normalized difference function
 * and finds the first dip below an absolute threshold, then refines the
 * estimate with parabolic interpolation for sub-sample accuracy.</p>
 *
 * <p>Reference: de Cheveigné, A., &amp; Kawahara, H. (2002). "YIN, a fundamental
 * frequency estimator for speech and music." JASA 111(4), 1917–1930.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class PitchDetector {

    /** Result of a pitch detection operation. */
    public record PitchResult(double frequencyHz, double probability, boolean pitched) {}

    private static final PitchResult UNPITCHED = new PitchResult(-1.0, 0.0, false);

    private final int bufferSize;
    private final double sampleRate;
    private final double threshold;
    private final double[] yinBuffer;

    /**
     * Creates a pitch detector.
     *
     * @param bufferSize the analysis window size in samples (must be &gt; 2)
     * @param sampleRate the audio sample rate in Hz
     * @param threshold  the YIN absolute threshold in (0, 1]; lower values
     *                   are stricter (typical: 0.10–0.20)
     */
    public PitchDetector(int bufferSize, double sampleRate, double threshold) {
        if (bufferSize <= 2) {
            throw new IllegalArgumentException("bufferSize must be > 2: " + bufferSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (threshold <= 0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in (0, 1]: " + threshold);
        }
        this.bufferSize = bufferSize;
        this.sampleRate = sampleRate;
        this.threshold = threshold;
        this.yinBuffer = new double[bufferSize / 2];
    }

    /**
     * Creates a pitch detector with a default threshold of 0.15.
     *
     * @param bufferSize the analysis window size in samples
     * @param sampleRate the audio sample rate in Hz
     */
    public PitchDetector(int bufferSize, double sampleRate) {
        this(bufferSize, sampleRate, 0.15);
    }

    /**
     * Detects the pitch in the given audio buffer.
     *
     * @param samples mono audio samples (length must be ≥ bufferSize)
     * @return the detected pitch result
     */
    public PitchResult detect(float[] samples) {
        if (samples.length < bufferSize) {
            throw new IllegalArgumentException(
                    "samples length (" + samples.length + ") must be >= bufferSize (" + bufferSize + ")");
        }

        int halfSize = bufferSize / 2;

        // Step 1–2: Difference function
        difference(samples, halfSize);

        // Step 3: Cumulative mean normalized difference function
        cumulativeMeanNormalizedDifference(halfSize);

        // Step 4: Absolute threshold
        int tauEstimate = absoluteThreshold(halfSize);

        if (tauEstimate == -1) {
            return UNPITCHED;
        }

        // Step 5: Parabolic interpolation
        double betterTau = parabolicInterpolation(tauEstimate, halfSize);

        double frequency = sampleRate / betterTau;
        double probability = 1.0 - yinBuffer[tauEstimate];

        return new PitchResult(frequency, probability, true);
    }

    /** Returns the buffer size. */
    public int getBufferSize() {
        return bufferSize;
    }

    /** Returns the sample rate. */
    public double getSampleRate() {
        return sampleRate;
    }

    /** Returns the YIN threshold. */
    public double getThreshold() {
        return threshold;
    }

    // ----------------------------------------------------------------
    // YIN algorithm steps
    // ----------------------------------------------------------------

    /**
     * Step 2: Computes the difference function d(τ).
     */
    private void difference(float[] samples, int halfSize) {
        for (int tau = 0; tau < halfSize; tau++) {
            double sum = 0.0;
            for (int i = 0; i < halfSize; i++) {
                double delta = samples[i] - samples[i + tau];
                sum += delta * delta;
            }
            yinBuffer[tau] = sum;
        }
    }

    /**
     * Step 3: Cumulative mean normalized difference function d'(τ).
     */
    private void cumulativeMeanNormalizedDifference(int halfSize) {
        yinBuffer[0] = 1.0;
        double runningSum = 0.0;
        for (int tau = 1; tau < halfSize; tau++) {
            runningSum += yinBuffer[tau];
            yinBuffer[tau] = yinBuffer[tau] * tau / runningSum;
        }
    }

    /**
     * Step 4: Finds the first dip below the absolute threshold.
     * Returns the lag (τ) or -1 if no pitch is detected.
     */
    private int absoluteThreshold(int halfSize) {
        // Start from tau=2 to avoid trivial zero-lag
        for (int tau = 2; tau < halfSize; tau++) {
            if (yinBuffer[tau] < threshold) {
                // Find the local minimum in this dip
                while (tau + 1 < halfSize && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++;
                }
                return tau;
            }
        }
        return -1;
    }

    /**
     * Step 5: Parabolic interpolation around the estimated tau for
     * sub-sample accuracy.
     */
    private double parabolicInterpolation(int tauEstimate, int halfSize) {
        if (tauEstimate <= 0 || tauEstimate >= halfSize - 1) {
            return tauEstimate;
        }

        double s0 = yinBuffer[tauEstimate - 1];
        double s1 = yinBuffer[tauEstimate];
        double s2 = yinBuffer[tauEstimate + 1];

        double denominator = 2.0 * (2.0 * s1 - s2 - s0);
        if (Math.abs(denominator) < 1e-12) {
            return tauEstimate;
        }

        double adjustment = (s2 - s0) / denominator;
        return tauEstimate + adjustment;
    }
}
