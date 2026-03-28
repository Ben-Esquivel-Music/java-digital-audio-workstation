package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.WindowType;

/**
 * Shared FFT utility methods used by audio analysis components.
 *
 * <p>Centralizes the pure-Java Cooley–Tukey radix-2 FFT and window
 * function computation to avoid duplication across {@link SpectrumAnalyzer}
 * and the quality analysis package.</p>
 */
public final class FftUtils {

    private FftUtils() {
        // utility class
    }

    /**
     * In-place Cooley–Tukey radix-2 FFT.
     *
     * @param real the real part of the input (length must be a power of two); overwritten with output
     * @param imag the imaginary part of the input (same length as {@code real}); overwritten with output
     */
    public static void fft(double[] real, double[] imag) {
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

    /**
     * In-place inverse FFT (conjugate method).
     *
     * <p>Conjugates the input, runs the forward FFT, conjugates the result
     * and divides by N.</p>
     *
     * @param real the real part of the frequency-domain input; overwritten with time-domain output
     * @param imag the imaginary part; overwritten with time-domain output
     */
    public static void ifft(double[] real, double[] imag) {
        int n = real.length;
        // Conjugate
        for (int i = 0; i < n; i++) {
            imag[i] = -imag[i];
        }
        fft(real, imag);
        // Conjugate and scale
        for (int i = 0; i < n; i++) {
            real[i] /= n;
            imag[i] = -imag[i] / n;
        }
    }

    /**
     * Creates a window of the specified type and size.
     *
     * @param windowType the window function type
     * @param size       the window size
     * @return a {@code double[]} containing the window coefficients
     */
    public static double[] createWindow(WindowType windowType, int size) {
        return switch (windowType) {
            case HANN -> createHannWindow(size);
            case HAMMING -> createHammingWindow(size);
            case BLACKMAN_HARRIS -> createBlackmanHarrisWindow(size);
        };
    }

    /**
     * Creates a Hann window of the given size.
     *
     * @param size the window size
     * @return a {@code double[]} containing the Hann window coefficients
     */
    public static double[] createHannWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return window;
    }

    /**
     * Creates a Hamming window of the given size.
     *
     * <p>The Hamming window has slightly less side-lobe leakage than Hann
     * at the cost of a wider main lobe. It uses the coefficients
     * {@code 0.54 - 0.46 * cos(2πn/(N-1))}.</p>
     *
     * @param size the window size
     * @return a {@code double[]} containing the Hamming window coefficients
     */
    public static double[] createHammingWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (size - 1));
        }
        return window;
    }

    /**
     * Creates a Blackman-Harris window of the given size.
     *
     * <p>The 4-term Blackman-Harris window provides excellent side-lobe
     * suppression (–92 dB), making it ideal for high-dynamic-range
     * spectrum analysis where minimal spectral leakage is critical.</p>
     *
     * @param size the window size
     * @return a {@code double[]} containing the Blackman-Harris window coefficients
     */
    public static double[] createBlackmanHarrisWindow(int size) {
        double[] window = new double[size];
        double a0 = 0.35875;
        double a1 = 0.48829;
        double a2 = 0.14128;
        double a3 = 0.01168;
        for (int i = 0; i < size; i++) {
            double ratio = 2.0 * Math.PI * i / (size - 1);
            window[i] = a0 - a1 * Math.cos(ratio) + a2 * Math.cos(2.0 * ratio)
                    - a3 * Math.cos(3.0 * ratio);
        }
        return window;
    }
}
