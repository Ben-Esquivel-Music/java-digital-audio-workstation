package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

import java.util.Arrays;

/**
 * Real-time FFT-based spectrum analyzer using a pure-Java Cooley–Tukey
 * radix-2 implementation — no JNI required.
 *
 * <p>Processes mono audio frames through a Hann-windowed FFT and produces
 * {@link SpectrumData} snapshots containing per-bin magnitude in dB. The
 * analyzer applies optional exponential smoothing for stable visual display.</p>
 *
 * <p>Inspired by the spectrum analysis workflows described in the
 * mastering-techniques research document (§3 — Equalization and §8 —
 * Loudness Standards and Metering).</p>
 */
public final class SpectrumAnalyzer implements VisualizationProvider<SpectrumData> {

    private static final double DB_FLOOR = -120.0;

    private final int fftSize;
    private final double sampleRate;
    private final double smoothingFactor;
    private final double[] window;
    private final double[] real;
    private final double[] imag;
    private final float[] smoothedMagnitudes;
    private volatile SpectrumData latestData;

    /**
     * Creates a spectrum analyzer with the given FFT size, sample rate,
     * and smoothing factor.
     *
     * @param fftSize         FFT window size (must be a power of two)
     * @param sampleRate      the audio sample rate in Hz
     * @param smoothingFactor exponential smoothing in [0, 1); 0 = no smoothing
     */
    public SpectrumAnalyzer(int fftSize, double sampleRate, double smoothingFactor) {
        if (fftSize <= 0 || (fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException("fftSize must be a positive power of two: " + fftSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (smoothingFactor < 0 || smoothingFactor >= 1.0) {
            throw new IllegalArgumentException("smoothingFactor must be in [0, 1): " + smoothingFactor);
        }
        this.fftSize = fftSize;
        this.sampleRate = sampleRate;
        this.smoothingFactor = smoothingFactor;
        this.window = createHannWindow(fftSize);
        this.real = new double[fftSize];
        this.imag = new double[fftSize];
        this.smoothedMagnitudes = new float[fftSize / 2];
        Arrays.fill(smoothedMagnitudes, (float) DB_FLOOR);
    }

    /**
     * Creates a spectrum analyzer with default smoothing (0.8).
     *
     * @param fftSize    FFT window size (must be a power of two)
     * @param sampleRate the audio sample rate in Hz
     */
    public SpectrumAnalyzer(int fftSize, double sampleRate) {
        this(fftSize, sampleRate, 0.8);
    }

    /**
     * Processes a block of mono audio samples and updates the spectrum data.
     *
     * @param samples mono audio samples (length must be ≥ fftSize)
     */
    public void process(float[] samples) {
        int length = Math.min(samples.length, fftSize);
        for (int i = 0; i < length; i++) {
            real[i] = samples[i] * window[i];
            imag[i] = 0.0;
        }
        for (int i = length; i < fftSize; i++) {
            real[i] = 0.0;
            imag[i] = 0.0;
        }

        fft(real, imag);

        int binCount = fftSize / 2;
        float[] magnitudes = new float[binCount];
        for (int i = 0; i < binCount; i++) {
            double mag = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]) / fftSize;
            double db = (mag > 0) ? 20.0 * Math.log10(mag) : DB_FLOOR;
            db = Math.max(db, DB_FLOOR);

            if (smoothingFactor > 0) {
                smoothedMagnitudes[i] = (float) (smoothingFactor * smoothedMagnitudes[i]
                        + (1.0 - smoothingFactor) * db);
            } else {
                smoothedMagnitudes[i] = (float) db;
            }
            magnitudes[i] = smoothedMagnitudes[i];
        }

        latestData = new SpectrumData(magnitudes, fftSize, sampleRate);
    }

    /** Returns the FFT size. */
    public int getFftSize() {
        return fftSize;
    }

    /** Returns the sample rate. */
    public double getSampleRate() {
        return sampleRate;
    }

    @Override
    public SpectrumData getLatestData() {
        return latestData;
    }

    @Override
    public boolean hasData() {
        return latestData != null;
    }

    /**
     * Resets the analyzer state, clearing all smoothed data.
     */
    public void reset() {
        Arrays.fill(smoothedMagnitudes, (float) DB_FLOOR);
        latestData = null;
    }

    // ----------------------------------------------------------------
    // Pure-Java Cooley–Tukey radix-2 FFT (in-place)
    // ----------------------------------------------------------------

    static void fft(double[] real, double[] imag) {
        int n = real.length;
        if (n <= 1) return;

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tempR = real[i]; real[i] = real[j]; real[j] = tempR;
                double tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI;
            }
        }

        // Cooley–Tukey butterfly
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wR = Math.cos(angle);
            double wI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curR = 1.0, curI = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double tR = curR * real[i + j + len / 2] - curI * imag[i + j + len / 2];
                    double tI = curR * imag[i + j + len / 2] + curI * real[i + j + len / 2];
                    real[i + j + len / 2] = real[i + j] - tR;
                    imag[i + j + len / 2] = imag[i + j] - tI;
                    real[i + j] += tR;
                    imag[i + j] += tI;
                    double newCurR = curR * wR - curI * wI;
                    curI = curR * wI + curI * wR;
                    curR = newCurR;
                }
            }
        }
    }

    private static double[] createHannWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return window;
    }
}
