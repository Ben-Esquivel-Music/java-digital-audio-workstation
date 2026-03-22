package com.benesquivelmusic.daw.core.analysis;

/**
 * Automated phase alignment and polarity detection for multitrack sessions.
 *
 * <p>Detects inter-track time offsets and polarity inversions caused by
 * multi-microphone recordings of the same source (e.g., drum kit, stereo
 * miking, DI/amp combinations). Uses FFT-based cross-correlation to find
 * the optimal sample-accurate time offset, polarity detection by comparing
 * cross-correlation peaks of normal and inverted signals, and spectral
 * coherence measurement to quantify phase cancellation severity.</p>
 *
 * <p>Extends functionality from {@link CorrelationMeter} (real-time
 * correlation) and {@link FftUtils} (FFT computation).</p>
 *
 * <p>Based on research into automated detection of phase misalignment and
 * polarity inversion in multi-microphone drum recordings using
 * cross-correlation and spectral coherence.</p>
 */
public final class PhaseAlignmentAnalyzer {

    private static final double COHERENCE_EPSILON = 1e-12;

    private final int maxDelaySamples;
    private final double sampleRate;

    /**
     * Creates a phase alignment analyzer.
     *
     * @param sampleRate      the audio sample rate in Hz
     * @param maxDelaySamples the maximum delay in samples to search for alignment;
     *                        limits the cross-correlation search window
     */
    public PhaseAlignmentAnalyzer(double sampleRate, int maxDelaySamples) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (maxDelaySamples < 0) {
            throw new IllegalArgumentException(
                    "maxDelaySamples must be non-negative: " + maxDelaySamples);
        }
        this.sampleRate = sampleRate;
        this.maxDelaySamples = maxDelaySamples;
    }

    /**
     * Creates a phase alignment analyzer with a default max delay of 5 ms
     * (converted to samples based on the sample rate).
     *
     * @param sampleRate the audio sample rate in Hz
     */
    public PhaseAlignmentAnalyzer(double sampleRate) {
        this(sampleRate, (int) (sampleRate * 0.005));
    }

    /**
     * Analyzes phase alignment between two audio tracks.
     *
     * <p>Uses FFT-based cross-correlation to find the optimal time offset,
     * detects polarity inversion, and measures spectral coherence. Band
     * coherences are not computed by this method; use
     * {@link #analyze(float[], float[], double[])} to include per-band
     * coherence analysis.</p>
     *
     * @param trackA first track samples (reference)
     * @param trackB second track samples (to be aligned to the reference)
     * @return the phase alignment result
     */
    public PhaseAlignmentResult analyze(float[] trackA, float[] trackB) {
        return analyze(trackA, trackB, new double[0]);
    }

    /**
     * Analyzes phase alignment between two audio tracks with per-band
     * spectral coherence.
     *
     * @param trackA    first track samples (reference)
     * @param trackB    second track samples (to be aligned to the reference)
     * @param bandEdges crossover frequencies in Hz for band coherence analysis;
     *                  produces {@code bandEdges.length + 1} bands. Pass an
     *                  empty array to skip band analysis.
     * @return the phase alignment result
     */
    public PhaseAlignmentResult analyze(float[] trackA, float[] trackB, double[] bandEdges) {
        int length = Math.min(trackA.length, trackB.length);
        if (length == 0) {
            return new PhaseAlignmentResult(0,
                    PhaseAlignmentResult.Polarity.NORMAL, 0.0, 0.0, new double[0]);
        }

        // FFT-based cross-correlation
        int fftSize = nextPowerOfTwo(length * 2);
        double[] crossCorrelation = computeCrossCorrelation(trackA, trackB, length, fftSize);

        // Find peak in the allowed delay range.
        // Cross-correlation R[d] = sum_n a[n] * b[n+d].
        // Peak at positive d means trackB is late by d → delaySamples = -d (advance it).
        // Peak at negative d (index fftSize-d) means trackB is early by d → delaySamples = +d (delay it).
        int searchRange = Math.min(maxDelaySamples, length - 1);
        int bestDelay = 0;
        double bestPeak = Double.NEGATIVE_INFINITY;

        // Search positive lags: trackB is late, needs to be advanced
        for (int d = 0; d <= searchRange; d++) {
            if (crossCorrelation[d] > bestPeak) {
                bestPeak = crossCorrelation[d];
                bestDelay = -d;
            }
        }

        // Search negative lags: trackB is early, needs to be delayed
        for (int d = 1; d <= searchRange; d++) {
            int idx = fftSize - d;
            if (idx >= 0 && idx < crossCorrelation.length && crossCorrelation[idx] > bestPeak) {
                bestPeak = crossCorrelation[idx];
                bestDelay = d;
            }
        }

        // Normalize the peak
        double normA = rmsEnergy(trackA, length);
        double normB = rmsEnergy(trackB, length);
        double normalizer = normA * normB * length;
        double normalizedPeak = (normalizer > COHERENCE_EPSILON) ? bestPeak / normalizer : 0.0;
        normalizedPeak = Math.max(-1.0, Math.min(1.0, normalizedPeak));

        // Polarity detection: compare normal vs inverted correlation peak
        double invertedPeak = computeInvertedPeak(crossCorrelation, searchRange, fftSize);
        double normalizedInvertedPeak = (normalizer > COHERENCE_EPSILON)
                ? invertedPeak / normalizer : 0.0;

        PhaseAlignmentResult.Polarity polarity;
        double effectivePeak;

        if (normalizedInvertedPeak > normalizedPeak) {
            polarity = PhaseAlignmentResult.Polarity.INVERTED;
            effectivePeak = normalizedInvertedPeak;
            // Recompute delay for inverted signal
            bestDelay = findInvertedDelay(crossCorrelation, searchRange, fftSize);
        } else {
            polarity = PhaseAlignmentResult.Polarity.NORMAL;
            effectivePeak = normalizedPeak;
        }
        effectivePeak = Math.max(-1.0, Math.min(1.0, effectivePeak));

        // Spectral coherence
        double coherence = computeSpectralCoherence(trackA, trackB, length, bestDelay);

        // Per-band coherence
        double[] bandCoherences;
        if (bandEdges.length > 0) {
            bandCoherences = computeBandCoherences(trackA, trackB, length, bestDelay, bandEdges);
        } else {
            bandCoherences = new double[0];
        }

        return new PhaseAlignmentResult(bestDelay, polarity, effectivePeak,
                coherence, bandCoherences);
    }

    /**
     * Returns the sample rate used for analysis.
     *
     * @return sample rate in Hz
     */
    public double getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the maximum delay in samples for the search window.
     *
     * @return max delay in samples
     */
    public int getMaxDelaySamples() {
        return maxDelaySamples;
    }

    // --- Internal methods ---

    /**
     * Computes the cross-correlation of two signals using FFT.
     * Result is in the frequency domain layout: indices 0..fftSize-1
     * where index d corresponds to trackB delayed by d samples, and
     * index fftSize-d corresponds to trackB advanced by d samples.
     */
    private static double[] computeCrossCorrelation(
            float[] trackA, float[] trackB, int length, int fftSize) {

        double[] realA = new double[fftSize];
        double[] imagA = new double[fftSize];
        double[] realB = new double[fftSize];
        double[] imagB = new double[fftSize];

        for (int i = 0; i < length; i++) {
            realA[i] = trackA[i];
            realB[i] = trackB[i];
        }

        FftUtils.fft(realA, imagA);
        FftUtils.fft(realB, imagB);

        // Cross-power spectrum: conj(A) * B
        double[] crossReal = new double[fftSize];
        double[] crossImag = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            crossReal[i] = realA[i] * realB[i] + imagA[i] * imagB[i];
            crossImag[i] = realA[i] * imagB[i] - imagA[i] * realB[i];
        }

        FftUtils.ifft(crossReal, crossImag);
        return crossReal;
    }

    /**
     * Finds the peak in the inverted (negated) cross-correlation within the
     * search range. The inverted cross-correlation corresponds to the
     * correlation of trackA with the polarity-inverted trackB.
     */
    private static double computeInvertedPeak(
            double[] crossCorrelation, int searchRange, int fftSize) {

        double bestPeak = Double.NEGATIVE_INFINITY;

        for (int d = 0; d <= searchRange; d++) {
            double inverted = -crossCorrelation[d];
            if (inverted > bestPeak) {
                bestPeak = inverted;
            }
        }

        for (int d = 1; d <= searchRange; d++) {
            int idx = fftSize - d;
            if (idx >= 0 && idx < crossCorrelation.length) {
                double inverted = -crossCorrelation[idx];
                if (inverted > bestPeak) {
                    bestPeak = inverted;
                }
            }
        }

        return bestPeak;
    }

    /**
     * Finds the delay corresponding to the inverted cross-correlation peak,
     * using the same sign convention as the normal delay search.
     */
    private static int findInvertedDelay(
            double[] crossCorrelation, int searchRange, int fftSize) {

        int bestDelay = 0;
        double bestPeak = Double.NEGATIVE_INFINITY;

        for (int d = 0; d <= searchRange; d++) {
            double inverted = -crossCorrelation[d];
            if (inverted > bestPeak) {
                bestPeak = inverted;
                bestDelay = -d;
            }
        }

        for (int d = 1; d <= searchRange; d++) {
            int idx = fftSize - d;
            if (idx >= 0 && idx < crossCorrelation.length) {
                double inverted = -crossCorrelation[idx];
                if (inverted > bestPeak) {
                    bestPeak = inverted;
                    bestDelay = d;
                }
            }
        }

        return bestDelay;
    }

    /**
     * Computes the RMS energy (root mean square) of a signal.
     */
    private static double rmsEnergy(float[] signal, int length) {
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            sum += (double) signal[i] * signal[i];
        }
        return Math.sqrt(sum / length);
    }

    /**
     * Computes spectral coherence between two tracks after applying the
     * detected delay offset, using Welch's method with overlapping segments.
     *
     * <p>Uses the magnitude-squared coherence (MSC) averaged across frequency
     * bins. The MSC requires segment averaging to produce meaningful values:</p>
     * <pre>
     *   MSC(f) = |&lt;Sxy(f)&gt;|² / (&lt;Sxx(f)&gt; · &lt;Syy(f)&gt;)
     * </pre>
     * <p>where &lt;.&gt; denotes averaging over overlapping segments.</p>
     */
    private double computeSpectralCoherence(
            float[] trackA, float[] trackB, int length, int delaySamples) {

        int segmentSize = 1024;
        if (segmentSize > length) {
            segmentSize = nextPowerOfTwo(length);
        }
        int hopSize = segmentSize / 2;
        double[] window = FftUtils.createHannWindow(segmentSize);

        int binCount = segmentSize / 2;
        double[] sumSxx = new double[binCount];
        double[] sumSyy = new double[binCount];
        double[] sumSxyReal = new double[binCount];
        double[] sumSxyImag = new double[binCount];
        int numSegments = 0;

        for (int offset = 0; offset + segmentSize <= length; offset += hopSize) {
            double[] realA = new double[segmentSize];
            double[] imagA = new double[segmentSize];
            double[] realB = new double[segmentSize];
            double[] imagB = new double[segmentSize];

            for (int i = 0; i < segmentSize; i++) {
                realA[i] = trackA[offset + i] * window[i];
                int srcIdx = offset + i + delaySamples;
                if (srcIdx >= 0 && srcIdx < trackB.length) {
                    realB[i] = trackB[srcIdx] * window[i];
                }
            }

            FftUtils.fft(realA, imagA);
            FftUtils.fft(realB, imagB);

            for (int i = 1; i < binCount; i++) {
                sumSxx[i] += realA[i] * realA[i] + imagA[i] * imagA[i];
                sumSyy[i] += realB[i] * realB[i] + imagB[i] * imagB[i];
                sumSxyReal[i] += realA[i] * realB[i] + imagA[i] * imagB[i];
                sumSxyImag[i] += realA[i] * imagB[i] - imagA[i] * realB[i];
            }
            numSegments++;
        }

        if (numSegments == 0) {
            return 0.0;
        }

        double sumCoherence = 0.0;
        int validBins = 0;

        for (int i = 1; i < binCount; i++) {
            double denom = sumSxx[i] * sumSyy[i];
            if (denom > COHERENCE_EPSILON) {
                double sxyMagSq = sumSxyReal[i] * sumSxyReal[i]
                        + sumSxyImag[i] * sumSxyImag[i];
                sumCoherence += sxyMagSq / denom;
                validBins++;
            }
        }

        return (validBins > 0) ? sumCoherence / validBins : 0.0;
    }

    /**
     * Computes per-band spectral coherence after applying the detected delay,
     * using Welch's method with overlapping segments.
     */
    private double[] computeBandCoherences(
            float[] trackA, float[] trackB, int length, int delaySamples,
            double[] bandEdges) {

        int segmentSize = 1024;
        if (segmentSize > length) {
            segmentSize = nextPowerOfTwo(length);
        }
        int hopSize = segmentSize / 2;
        double[] window = FftUtils.createHannWindow(segmentSize);

        int binCount = segmentSize / 2;
        double binFreqStep = sampleRate / segmentSize;
        int numBands = bandEdges.length + 1;

        double[] sumSxx = new double[binCount];
        double[] sumSyy = new double[binCount];
        double[] sumSxyReal = new double[binCount];
        double[] sumSxyImag = new double[binCount];
        int numSegments = 0;

        for (int offset = 0; offset + segmentSize <= length; offset += hopSize) {
            double[] realA = new double[segmentSize];
            double[] imagA = new double[segmentSize];
            double[] realB = new double[segmentSize];
            double[] imagB = new double[segmentSize];

            for (int i = 0; i < segmentSize; i++) {
                realA[i] = trackA[offset + i] * window[i];
                int srcIdx = offset + i + delaySamples;
                if (srcIdx >= 0 && srcIdx < trackB.length) {
                    realB[i] = trackB[srcIdx] * window[i];
                }
            }

            FftUtils.fft(realA, imagA);
            FftUtils.fft(realB, imagB);

            for (int i = 1; i < binCount; i++) {
                sumSxx[i] += realA[i] * realA[i] + imagA[i] * imagA[i];
                sumSyy[i] += realB[i] * realB[i] + imagB[i] * imagB[i];
                sumSxyReal[i] += realA[i] * realB[i] + imagA[i] * imagB[i];
                sumSxyImag[i] += realA[i] * imagB[i] - imagA[i] * realB[i];
            }
            numSegments++;
        }

        double[] bandCoherences = new double[numBands];
        if (numSegments == 0) {
            return bandCoherences;
        }

        for (int b = 0; b < numBands; b++) {
            double lowFreq = (b == 0) ? 0.0 : bandEdges[b - 1];
            double highFreq = (b == numBands - 1) ? sampleRate / 2.0 : bandEdges[b];

            int lowBin = Math.max(1, (int) (lowFreq / binFreqStep));
            int highBin = Math.min(binCount, (int) (highFreq / binFreqStep));

            double sumCoherence = 0.0;
            int validBins = 0;

            for (int i = lowBin; i < highBin; i++) {
                double denom = sumSxx[i] * sumSyy[i];
                if (denom > COHERENCE_EPSILON) {
                    double sxyMagSq = sumSxyReal[i] * sumSxyReal[i]
                            + sumSxyImag[i] * sumSxyImag[i];
                    sumCoherence += sxyMagSq / denom;
                    validBins++;
                }
            }

            bandCoherences[b] = (validBins > 0) ? sumCoherence / validBins : 0.0;
        }

        return bandCoherences;
    }

    /**
     * Returns the smallest power of two ≥ n.
     */
    private static int nextPowerOfTwo(int n) {
        int v = 1;
        while (v < n) {
            v <<= 1;
        }
        return v;
    }
}
