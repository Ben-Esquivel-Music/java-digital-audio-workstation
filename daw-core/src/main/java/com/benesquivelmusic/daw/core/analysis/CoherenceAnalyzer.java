package com.benesquivelmusic.daw.core.analysis;

/**
 * Coherence-based distortion indicator for audio signal chains.
 *
 * <p>Computes the magnitude-squared coherence (MSC) between an input signal
 * {@code x(n)} (pre-effect chain) and its corresponding output {@code y(n)}
 * (post-effect chain) using Welch's method. For a linear, time-invariant
 * system the coherence is 1.0 at every frequency; nonlinear distortion,
 * added noise, and other uncorrelated components cause the coherence to
 * drop below 1.0.</p>
 *
 * <pre>
 *   γ²(f) = |&lt;S_xy(f)&gt;|² / (&lt;S_xx(f)&gt; · &lt;S_yy(f)&gt;)
 * </pre>
 *
 * <p>where {@code S_xx} and {@code S_yy} are the auto-spectral densities of
 * the input and output, {@code S_xy} is the cross-spectral density, and
 * {@code &lt;.&gt;} denotes averaging over overlapping Hann-windowed segments
 * (Welch's method).</p>
 *
 * <p>Unlike scalar THD+N, coherence provides a per-frequency distortion map
 * and works for wideband, non-stationary signals including music. This
 * implementation is based on Hinton &amp; Wagstaff, "Coherence as an Indicator
 * of Distortion for Wide-Band Audio Signals such as M-Noise and Music"
 * (AES, 2019).</p>
 *
 * <p>Extends the shared FFT primitives in {@link FftUtils}.</p>
 *
 * <p>Typical use cases:</p>
 * <ul>
 *     <li>Effect chain quality assessment (plugin chain before vs after).</li>
 *     <li>Master bus distortion monitoring during mastering.</li>
 *     <li>Automated A/B testing of processing modules.</li>
 * </ul>
 */
public final class CoherenceAnalyzer {

    /** Default FFT/segment size for Welch averaging. */
    public static final int DEFAULT_SEGMENT_SIZE = 1024;

    /** Default overlap ratio between consecutive segments (50%). */
    public static final double DEFAULT_OVERLAP = 0.5;

    private static final double SPECTRUM_EPSILON = 1e-12;

    private final double sampleRate;
    private final int segmentSize;
    private final int hopSize;
    private final double[] window;

    /**
     * Creates a coherence analyzer with the default segment size (1024) and
     * 50% overlap.
     *
     * @param sampleRate the audio sample rate in Hz (must be positive)
     */
    public CoherenceAnalyzer(double sampleRate) {
        this(sampleRate, DEFAULT_SEGMENT_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * Creates a coherence analyzer.
     *
     * @param sampleRate  the audio sample rate in Hz (must be positive)
     * @param segmentSize the FFT/segment size for Welch averaging; must be a
     *                    power of two &ge; 2. Larger values give finer
     *                    frequency resolution; smaller values average more
     *                    segments for a given buffer length.
     * @param overlap     fraction of overlap between consecutive segments in
     *                    {@code [0.0, 1.0)}. 0.5 (50%) is typical.
     */
    public CoherenceAnalyzer(double sampleRate, int segmentSize, double overlap) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (segmentSize < 2 || (segmentSize & (segmentSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "segmentSize must be a power of two >= 2: " + segmentSize);
        }
        if (overlap < 0.0 || overlap >= 1.0) {
            throw new IllegalArgumentException(
                    "overlap must be in [0.0, 1.0): " + overlap);
        }
        this.sampleRate = sampleRate;
        this.segmentSize = segmentSize;
        int computedHop = (int) Math.round(segmentSize * (1.0 - overlap));
        this.hopSize = Math.max(1, computedHop);
        this.window = FftUtils.createHannWindow(segmentSize);
    }

    /**
     * Computes magnitude-squared coherence between the input and output
     * buffers of a signal chain.
     *
     * <p>Both buffers must be sample-aligned (the output sample at index
     * {@code n} must correspond to the input sample at index {@code n}).
     * Any latency introduced by the signal chain must be compensated by the
     * caller before invoking this method. The analysis uses the shorter of
     * the two buffer lengths.</p>
     *
     * @param input  the input samples (pre-effect chain)
     * @param output the output samples (post-effect chain)
     * @return a {@link CoherenceResult} containing per-bin coherence values,
     *         bin frequencies, and summary statistics
     */
    public CoherenceResult analyze(float[] input, float[] output) {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output must not be null");
        }

        int length = Math.min(input.length, output.length);
        int binCount = segmentSize / 2 + 1;
        double[] frequencies = new double[binCount];
        double binFreqStep = sampleRate / segmentSize;
        for (int i = 0; i < binCount; i++) {
            frequencies[i] = i * binFreqStep;
        }

        if (length < segmentSize) {
            return new CoherenceResult(
                    new double[binCount], frequencies, 0.0, 1.0, 0);
        }

        double[] sumSxx = new double[binCount];
        double[] sumSyy = new double[binCount];
        double[] sumSxyReal = new double[binCount];
        double[] sumSxyImag = new double[binCount];
        int numSegments = 0;

        double[] realX = new double[segmentSize];
        double[] imagX = new double[segmentSize];
        double[] realY = new double[segmentSize];
        double[] imagY = new double[segmentSize];

        for (int offset = 0; offset + segmentSize <= length; offset += hopSize) {
            for (int i = 0; i < segmentSize; i++) {
                realX[i] = input[offset + i] * window[i];
                imagX[i] = 0.0;
                realY[i] = output[offset + i] * window[i];
                imagY[i] = 0.0;
            }

            FftUtils.fft(realX, imagX);
            FftUtils.fft(realY, imagY);

            // Cross-power spectrum S_xy = conj(X) * Y
            //   conj(X) = (Xr - j·Xi)
            //   (Xr - j·Xi)(Yr + j·Yi) = (Xr·Yr + Xi·Yi) + j·(Xr·Yi - Xi·Yr)
            for (int i = 0; i < binCount; i++) {
                double xr = realX[i];
                double xi = imagX[i];
                double yr = realY[i];
                double yi = imagY[i];
                sumSxx[i] += xr * xr + xi * xi;
                sumSyy[i] += yr * yr + yi * yi;
                sumSxyReal[i] += xr * yr + xi * yi;
                sumSxyImag[i] += xr * yi - xi * yr;
            }
            numSegments++;
        }

        double[] coherence = new double[binCount];
        double sum = 0.0;
        int validBins = 0;

        // Skip DC (bin 0) when averaging, but still report it (as 0) in the array.
        for (int i = 0; i < binCount; i++) {
            double denom = sumSxx[i] * sumSyy[i];
            if (denom > SPECTRUM_EPSILON) {
                double magSq = sumSxyReal[i] * sumSxyReal[i]
                        + sumSxyImag[i] * sumSxyImag[i];
                double msc = magSq / denom;
                // Clamp to [0, 1] to guard against floating-point drift.
                if (msc < 0.0) {
                    msc = 0.0;
                } else if (msc > 1.0) {
                    msc = 1.0;
                }
                coherence[i] = msc;
                if (i > 0) {
                    sum += msc;
                    validBins++;
                }
            }
        }

        double meanCoherence = (validBins > 0) ? sum / validBins : 0.0;
        double distortion = 1.0 - meanCoherence;

        return new CoherenceResult(
                coherence, frequencies, meanCoherence, distortion, numSegments);
    }

    /**
     * Computes the mean coherence within a frequency band from a
     * {@link CoherenceResult}.
     *
     * <p>Useful for band-limited distortion assessment (e.g., "low-frequency
     * coherence" or "presence-band coherence"). The band is inclusive of
     * {@code lowHz} and exclusive of {@code highHz}.</p>
     *
     * @param result  a coherence result produced by this analyzer
     * @param lowHz   lower band edge in Hz (inclusive)
     * @param highHz  upper band edge in Hz (exclusive); must be &gt; {@code lowHz}
     * @return mean coherence in the band in {@code [0.0, 1.0]}, or 0.0 if no
     *         bins fall within the band
     */
    public static double meanCoherenceInBand(CoherenceResult result, double lowHz, double highHz) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (highHz <= lowHz) {
            throw new IllegalArgumentException(
                    "highHz must be greater than lowHz: " + highHz + " <= " + lowHz);
        }
        double[] coherence = result.coherence();
        double[] frequencies = result.frequencies();
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < coherence.length; i++) {
            double f = frequencies[i];
            if (f >= lowHz && f < highHz) {
                sum += coherence[i];
                count++;
            }
        }
        return (count > 0) ? sum / count : 0.0;
    }

    /** @return the sample rate in Hz. */
    public double getSampleRate() {
        return sampleRate;
    }

    /** @return the FFT/segment size used for Welch averaging. */
    public int getSegmentSize() {
        return segmentSize;
    }

    /** @return the hop size (samples between consecutive segment starts). */
    public int getHopSize() {
        return hopSize;
    }
}
