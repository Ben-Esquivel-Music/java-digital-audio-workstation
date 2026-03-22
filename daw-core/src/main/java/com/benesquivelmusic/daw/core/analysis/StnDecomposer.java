package com.benesquivelmusic.daw.core.analysis;

import java.util.Arrays;

/**
 * Spectral decomposition engine that separates an audio signal into three
 * components: sinusoidal (tonal), transient (percussive), and noise (stochastic).
 *
 * <p>The algorithm performs an STFT using {@link FftUtils}, applies median
 * filtering on the magnitude spectrogram — horizontally (across time) for the
 * tonal component and vertically (across frequency) for the transient component —
 * then uses Wiener-type soft masking to produce artifact-free separation. The
 * residual (original minus tonal minus transient) yields the noise component.</p>
 *
 * <p>Reference: "Enhanced Fuzzy Decomposition of Sound Into Sines, Transients,
 * and Noise" (AES, 2023).</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class StnDecomposer {

    /** Result of the STN decomposition containing three time-domain signals. */
    public record StnResult(float[] sines, float[] transients, float[] noise) {}

    private static final double EPSILON = 1e-10;

    private final int fftSize;
    private final int hopSize;
    private final int tonalMedianLength;
    private final int transientMedianLength;
    private final double[] window;
    private final double[] windowSquared;

    /**
     * Creates an STN decomposer.
     *
     * @param fftSize               FFT window size (must be a power of two)
     * @param hopSize               hop size between consecutive frames in samples
     * @param tonalMedianLength     length of the horizontal (time) median filter
     *                              for tonal extraction (must be odd and ≥ 1)
     * @param transientMedianLength length of the vertical (frequency) median filter
     *                              for transient extraction (must be odd and ≥ 1)
     */
    public StnDecomposer(int fftSize, int hopSize,
                         int tonalMedianLength, int transientMedianLength) {
        if (fftSize <= 0 || (fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException("fftSize must be a positive power of two: " + fftSize);
        }
        if (hopSize <= 0 || hopSize > fftSize) {
            throw new IllegalArgumentException("hopSize must be in (0, fftSize]: " + hopSize);
        }
        if (tonalMedianLength < 1 || tonalMedianLength % 2 == 0) {
            throw new IllegalArgumentException(
                    "tonalMedianLength must be odd and >= 1: " + tonalMedianLength);
        }
        if (transientMedianLength < 1 || transientMedianLength % 2 == 0) {
            throw new IllegalArgumentException(
                    "transientMedianLength must be odd and >= 1: " + transientMedianLength);
        }
        this.fftSize = fftSize;
        this.hopSize = hopSize;
        this.tonalMedianLength = tonalMedianLength;
        this.transientMedianLength = transientMedianLength;
        this.window = FftUtils.createHannWindow(fftSize);
        this.windowSquared = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            windowSquared[i] = window[i] * window[i];
        }
    }

    /**
     * Creates an STN decomposer with default median filter lengths (17 frames
     * for tonal, 17 bins for transient) and hop size of fftSize / 4.
     *
     * @param fftSize FFT window size (must be a power of two)
     */
    public StnDecomposer(int fftSize) {
        this(fftSize, fftSize / 4, 17, 17);
    }

    /**
     * Decomposes the input audio into sinusoidal, transient, and noise components.
     *
     * @param samples mono audio samples
     * @return the decomposition result containing three time-domain signals
     *         of the same length as the input
     */
    public StnResult decompose(float[] samples) {
        int inputLength = samples.length;
        if (inputLength < fftSize) {
            return new StnResult(new float[inputLength], new float[inputLength], new float[inputLength]);
        }

        int numFrames = (inputLength - fftSize) / hopSize + 1;
        int binCount = fftSize / 2 + 1;

        // STFT — store complex spectrogram
        double[][] stftReal = new double[numFrames][binCount];
        double[][] stftImag = new double[numFrames][binCount];
        double[][] magnitude = new double[numFrames][binCount];

        double[] frameReal = new double[fftSize];
        double[] frameImag = new double[fftSize];

        for (int frame = 0; frame < numFrames; frame++) {
            int offset = frame * hopSize;
            for (int i = 0; i < fftSize; i++) {
                frameReal[i] = samples[offset + i] * window[i];
                frameImag[i] = 0.0;
            }
            FftUtils.fft(frameReal, frameImag);
            for (int bin = 0; bin < binCount; bin++) {
                stftReal[frame][bin] = frameReal[bin];
                stftImag[frame][bin] = frameImag[bin];
                magnitude[frame][bin] = Math.sqrt(
                        frameReal[bin] * frameReal[bin] + frameImag[bin] * frameImag[bin]);
            }
        }

        // Horizontal (time) median filtering → tonal estimate
        double[][] tonalMag = horizontalMedianFilter(magnitude, numFrames, binCount);

        // Vertical (frequency) median filtering → transient estimate
        double[][] transientMag = verticalMedianFilter(magnitude, numFrames, binCount);

        // Wiener soft masking
        float[] sines = new float[inputLength];
        float[] transients = new float[inputLength];
        float[] noise = new float[inputLength];
        double[] normalization = new double[inputLength];

        for (int frame = 0; frame < numFrames; frame++) {
            double[] sinReal = new double[fftSize];
            double[] sinImag = new double[fftSize];
            double[] trnReal = new double[fftSize];
            double[] trnImag = new double[fftSize];
            double[] nseReal = new double[fftSize];
            double[] nseImag = new double[fftSize];

            for (int bin = 0; bin < binCount; bin++) {
                double tSq = tonalMag[frame][bin] * tonalMag[frame][bin];
                double pSq = transientMag[frame][bin] * transientMag[frame][bin];
                double totalSq = tSq + pSq + EPSILON;

                double maskTonal = tSq / totalSq;
                double maskTransient = pSq / totalSq;
                double maskNoise = Math.max(0.0, 1.0 - maskTonal - maskTransient);

                double re = stftReal[frame][bin];
                double im = stftImag[frame][bin];

                sinReal[bin] = maskTonal * re;
                sinImag[bin] = maskTonal * im;
                trnReal[bin] = maskTransient * re;
                trnImag[bin] = maskTransient * im;
                nseReal[bin] = maskNoise * re;
                nseImag[bin] = maskNoise * im;

                // Mirror for inverse FFT (conjugate symmetry)
                if (bin > 0 && bin < fftSize / 2) {
                    int mirror = fftSize - bin;
                    sinReal[mirror] = sinReal[bin];
                    sinImag[mirror] = -sinImag[bin];
                    trnReal[mirror] = trnReal[bin];
                    trnImag[mirror] = -trnImag[bin];
                    nseReal[mirror] = nseReal[bin];
                    nseImag[mirror] = -nseImag[bin];
                }
            }

            // Inverse FFT each component
            FftUtils.ifft(sinReal, sinImag);
            FftUtils.ifft(trnReal, trnImag);
            FftUtils.ifft(nseReal, nseImag);

            // Overlap-add with synthesis window
            int offset = frame * hopSize;
            for (int i = 0; i < fftSize; i++) {
                int idx = offset + i;
                if (idx < inputLength) {
                    sines[idx] += (float) (sinReal[i] * window[i]);
                    transients[idx] += (float) (trnReal[i] * window[i]);
                    noise[idx] += (float) (nseReal[i] * window[i]);
                    normalization[idx] += windowSquared[i];
                }
            }
        }

        // Normalize by the overlap-add window energy
        for (int i = 0; i < inputLength; i++) {
            if (normalization[i] > EPSILON) {
                sines[i] /= (float) normalization[i];
                transients[i] /= (float) normalization[i];
                noise[i] /= (float) normalization[i];
            }
        }

        return new StnResult(sines, transients, noise);
    }

    /** Returns the FFT size. */
    public int getFftSize() {
        return fftSize;
    }

    /** Returns the hop size. */
    public int getHopSize() {
        return hopSize;
    }

    /** Returns the tonal median filter length. */
    public int getTonalMedianLength() {
        return tonalMedianLength;
    }

    /** Returns the transient median filter length. */
    public int getTransientMedianLength() {
        return transientMedianLength;
    }

    // ----------------------------------------------------------------
    // Median filtering
    // ----------------------------------------------------------------

    /**
     * Applies a median filter horizontally (across time frames) for each
     * frequency bin, extracting the tonal (harmonically stable) component.
     */
    private double[][] horizontalMedianFilter(double[][] magnitude,
                                              int numFrames, int binCount) {
        double[][] result = new double[numFrames][binCount];
        int halfLen = tonalMedianLength / 2;
        double[] medianBuf = new double[tonalMedianLength];

        for (int bin = 0; bin < binCount; bin++) {
            for (int frame = 0; frame < numFrames; frame++) {
                int start = Math.max(0, frame - halfLen);
                int end = Math.min(numFrames, frame + halfLen + 1);
                int count = end - start;
                for (int k = 0; k < count; k++) {
                    medianBuf[k] = magnitude[start + k][bin];
                }
                result[frame][bin] = median(medianBuf, count);
            }
        }
        return result;
    }

    /**
     * Applies a median filter vertically (across frequency bins) for each
     * time frame, extracting the transient (broadband percussive) component.
     */
    private double[][] verticalMedianFilter(double[][] magnitude,
                                            int numFrames, int binCount) {
        double[][] result = new double[numFrames][binCount];
        int halfLen = transientMedianLength / 2;
        double[] medianBuf = new double[transientMedianLength];

        for (int frame = 0; frame < numFrames; frame++) {
            for (int bin = 0; bin < binCount; bin++) {
                int start = Math.max(0, bin - halfLen);
                int end = Math.min(binCount, bin + halfLen + 1);
                int count = end - start;
                for (int k = 0; k < count; k++) {
                    medianBuf[k] = magnitude[frame][start + k];
                }
                result[frame][bin] = median(medianBuf, count);
            }
        }
        return result;
    }

    /**
     * Computes the median of the first {@code count} elements in the buffer.
     */
    private static double median(double[] buffer, int count) {
        Arrays.sort(buffer, 0, count);
        if (count % 2 == 1) {
            return buffer[count / 2];
        }
        return (buffer[count / 2 - 1] + buffer[count / 2]) / 2.0;
    }
}
