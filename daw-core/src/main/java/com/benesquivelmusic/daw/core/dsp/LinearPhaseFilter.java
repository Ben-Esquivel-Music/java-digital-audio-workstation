package com.benesquivelmusic.daw.core.dsp;

import java.util.Arrays;

/**
 * A linear-phase FIR filter generated from biquad filter coefficients
 * via frequency sampling.
 *
 * <p>Converts the magnitude response of one or more minimum-phase biquad
 * filters into a symmetric (Type I) FIR filter that preserves the combined
 * magnitude response while introducing zero phase distortion. This is
 * critical for mastering applications where phase smearing is unacceptable.</p>
 *
 * <p>The FIR filter introduces a latency of {@code (order - 1) / 2} samples.</p>
 */
public final class LinearPhaseFilter {

    static final int DEFAULT_FIR_ORDER = 4095;

    private final float[] coefficients;
    private final float[] delayLine;
    private int writeIndex;

    private LinearPhaseFilter(float[] coefficients) {
        this.coefficients = coefficients;
        this.delayLine = new float[coefficients.length];
        this.writeIndex = 0;
    }

    /**
     * Creates a composite linear-phase FIR filter from one or more biquad filters.
     *
     * <p>The magnitude responses of all provided biquads are multiplied together
     * (cascaded) and a single FIR filter is generated, keeping total latency
     * to {@code (firOrder - 1) / 2} samples regardless of the number of bands.</p>
     *
     * @param biquads  the biquad filters whose combined response to replicate
     * @param firOrder the desired FIR order (will be made odd for Type I symmetry)
     * @return a new linear-phase FIR filter
     */
    public static LinearPhaseFilter fromBiquads(BiquadFilter[] biquads, int firOrder) {
        if (firOrder < 3) {
            throw new IllegalArgumentException("firOrder must be >= 3: " + firOrder);
        }
        if (firOrder % 2 == 0) firOrder++;

        int fftSize = nextPowerOfTwo(Math.max(firOrder * 2, 4096));

        // Compute composite magnitude response
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        for (int k = 0; k <= fftSize / 2; k++) {
            double omega = 2.0 * Math.PI * k / fftSize;
            double mag = 1.0;
            for (BiquadFilter bq : biquads) {
                mag *= bq.evaluateMagnitudeAt(omega);
            }
            real[k] = mag;
        }
        // Mirror for conjugate symmetry (ensures real-valued IFFT output)
        for (int k = 1; k < fftSize / 2; k++) {
            real[fftSize - k] = real[k];
        }

        // IFFT → zero-phase impulse response
        ifft(real, imag, fftSize);

        // Extract symmetric FIR and apply Blackman window
        int center = firOrder / 2;
        float[] coeffs = new float[firOrder];
        for (int i = 0; i < firOrder; i++) {
            int offset = i - center;
            int idx = ((offset % fftSize) + fftSize) % fftSize;
            coeffs[i] = (float) (real[idx] * blackmanWindow(i, firOrder));
        }

        return new LinearPhaseFilter(coeffs);
    }

    /**
     * Creates a linear-phase FIR filter that matches an arbitrary magnitude
     * frequency response specified at half-spectrum FFT bin resolution.
     *
     * <p>Used by higher-level EQ stages (e.g. match EQ) that already compute
     * their target |H(e^{jω})| on a regular FFT grid — no biquad cascade is
     * required. The input is interpreted as the magnitude response at
     * {@code k = 0, 1, …, magnitudeHalfSpectrum.length - 1}, corresponding to
     * FFT bins of a transform of size
     * {@code 2 · (magnitudeHalfSpectrum.length - 1)}.</p>
     *
     * @param magnitudeHalfSpectrum magnitude response |H(e^{jωk})| at bin k,
     *                              for k = 0..fftSize/2 (length fftSize/2+1)
     * @param firOrder              the desired FIR order (will be made odd for
     *                              Type I symmetry)
     * @return a new linear-phase FIR filter approximating the given magnitudes
     */
    public static LinearPhaseFilter fromMagnitudeResponse(double[] magnitudeHalfSpectrum,
                                                           int firOrder) {
        if (magnitudeHalfSpectrum == null || magnitudeHalfSpectrum.length < 2) {
            throw new IllegalArgumentException(
                    "magnitudeHalfSpectrum must have length >= 2");
        }
        if (firOrder < 3) {
            throw new IllegalArgumentException("firOrder must be >= 3: " + firOrder);
        }
        if (firOrder % 2 == 0) firOrder++;

        int halfLen = magnitudeHalfSpectrum.length;
        int inputFftSize = (halfLen - 1) * 2;
        int fftSize = Math.max(inputFftSize, nextPowerOfTwo(Math.max(firOrder * 2, 4096)));

        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];

        // Resample the supplied half-spectrum magnitudes onto the design grid
        // by linear interpolation in k-space.
        for (int k = 0; k <= fftSize / 2; k++) {
            double pos = (double) k * (halfLen - 1) / (fftSize / 2);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, halfLen - 1);
            double frac = pos - lo;
            double m = magnitudeHalfSpectrum[lo] * (1.0 - frac)
                     + magnitudeHalfSpectrum[hi] * frac;
            if (!Double.isFinite(m) || m < 0.0) m = 0.0;
            real[k] = m;
        }
        for (int k = 1; k < fftSize / 2; k++) {
            real[fftSize - k] = real[k];
        }

        ifft(real, imag, fftSize);

        int center = firOrder / 2;
        float[] coeffs = new float[firOrder];
        for (int i = 0; i < firOrder; i++) {
            int offset = i - center;
            int idx = ((offset % fftSize) + fftSize) % fftSize;
            coeffs[i] = (float) (real[idx] * blackmanWindow(i, firOrder));
        }
        return new LinearPhaseFilter(coeffs);
    }

    /**
     * Creates a single-band linear-phase FIR filter from biquad parameters.
     *
     * @param type       the filter type
     * @param sampleRate the sample rate in Hz
     * @param frequency  the center/cutoff frequency in Hz
     * @param q          the Q factor
     * @param gainDb     the gain in dB
     * @param firOrder   the desired FIR order
     * @return a new linear-phase FIR filter
     */
    public static LinearPhaseFilter fromBiquad(BiquadFilter.FilterType type, double sampleRate,
                                               double frequency, double q, double gainDb,
                                               int firOrder) {
        BiquadFilter bq = BiquadFilter.create(type, sampleRate, frequency, q, gainDb);
        return fromBiquads(new BiquadFilter[]{bq}, firOrder);
    }

    /**
     * Processes a single sample through the FIR filter.
     *
     * @param input the input sample
     * @return the filtered output sample
     */
    public float processSample(float input) {
        delayLine[writeIndex] = input;

        double output = 0.0;
        int readIndex = writeIndex;
        for (float coefficient : coefficients) {
            output += coefficient * delayLine[readIndex];
            if (--readIndex < 0) readIndex = delayLine.length - 1;
        }

        if (++writeIndex >= delayLine.length) writeIndex = 0;
        return (float) output;
    }

    /**
     * Processes a buffer of samples in-place.
     *
     * @param buffer the sample buffer
     * @param offset start offset
     * @param length number of samples to process
     */
    public void process(float[] buffer, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            buffer[i] = processSample(buffer[i]);
        }
    }

    /**
     * Returns the latency in samples introduced by this filter.
     */
    public int getLatency() {
        return (coefficients.length - 1) / 2;
    }

    /**
     * Returns the FIR order (number of taps).
     */
    public int getOrder() {
        return coefficients.length;
    }

    /**
     * Resets the filter state (clears the delay line).
     */
    public void reset() {
        Arrays.fill(delayLine, 0.0f);
        writeIndex = 0;
    }

    // ---- FFT helpers (package-private for testing) ----

    /**
     * In-place radix-2 Cooley–Tukey FFT (forward transform).
     */
    static void fft(double[] real, double[] imag, int n) {
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double t = real[i]; real[i] = real[j]; real[j] = t;
                t = imag[i]; imag[i] = imag[j]; imag[j] = t;
            }
        }
        // Butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wR = Math.cos(ang), wI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cR = 1.0, cI = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    double tR = cR * real[v] - cI * imag[v];
                    double tI = cR * imag[v] + cI * real[v];
                    real[v] = real[u] - tR;
                    imag[v] = imag[u] - tI;
                    real[u] += tR;
                    imag[u] += tI;
                    double nR = cR * wR - cI * wI;
                    cI = cR * wI + cI * wR;
                    cR = nR;
                }
            }
        }
    }

    /**
     * In-place inverse FFT.
     */
    static void ifft(double[] real, double[] imag, int n) {
        for (int i = 0; i < n; i++) imag[i] = -imag[i];
        fft(real, imag, n);
        for (int i = 0; i < n; i++) {
            real[i] /= n;
            imag[i] = -imag[i] / n;
        }
    }

    private static double blackmanWindow(int n, int length) {
        return 0.42 - 0.5 * Math.cos(2.0 * Math.PI * n / (length - 1))
                     + 0.08 * Math.cos(4.0 * Math.PI * n / (length - 1));
    }

    private static int nextPowerOfTwo(int v) {
        int p = Integer.highestOneBit(v);
        return (p < v) ? p << 1 : p;
    }
}
