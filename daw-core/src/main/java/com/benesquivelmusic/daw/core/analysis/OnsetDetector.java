package com.benesquivelmusic.daw.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java onset detector using spectral flux with peak picking.
 *
 * <p>Detects the onset times of audio events (transients) in a monophonic
 * or mixed audio signal. The algorithm computes the positive spectral flux
 * between consecutive FFT frames, applies an adaptive threshold, and
 * reports frames where the flux exceeds the threshold.</p>
 *
 * <p>Spectral flux measures the increase in spectral energy between frames,
 * making it sensitive to percussive onsets, note attacks, and other
 * transient events while being relatively robust to sustained tones.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class OnsetDetector {

    /** Represents a detected onset. */
    public record Onset(int frameIndex, double strength) {}

    private final int fftSize;
    private final int hopSize;
    private final double sampleRate;
    private final double sensitivityThreshold;
    private final double[] window;
    private final double[] prevMagnitudes;
    private final double[] real;
    private final double[] imag;

    /**
     * Creates an onset detector.
     *
     * @param fftSize              FFT window size (must be a power of two)
     * @param hopSize              hop size between consecutive frames in samples
     * @param sampleRate           the audio sample rate in Hz
     * @param sensitivityThreshold multiplier for the adaptive threshold;
     *                             higher values are less sensitive (typical: 1.3–2.0)
     */
    public OnsetDetector(int fftSize, int hopSize, double sampleRate,
                         double sensitivityThreshold) {
        if (fftSize <= 0 || (fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException("fftSize must be a positive power of two: " + fftSize);
        }
        if (hopSize <= 0 || hopSize > fftSize) {
            throw new IllegalArgumentException(
                    "hopSize must be in (0, fftSize]: " + hopSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (sensitivityThreshold <= 0) {
            throw new IllegalArgumentException(
                    "sensitivityThreshold must be positive: " + sensitivityThreshold);
        }
        this.fftSize = fftSize;
        this.hopSize = hopSize;
        this.sampleRate = sampleRate;
        this.sensitivityThreshold = sensitivityThreshold;
        this.window = createHannWindow(fftSize);
        this.prevMagnitudes = new double[fftSize / 2];
        this.real = new double[fftSize];
        this.imag = new double[fftSize];
    }

    /**
     * Creates an onset detector with default sensitivity (1.5) and hop size (fftSize / 2).
     *
     * @param fftSize    FFT window size (must be a power of two)
     * @param sampleRate the audio sample rate in Hz
     */
    public OnsetDetector(int fftSize, double sampleRate) {
        this(fftSize, fftSize / 2, sampleRate, 1.5);
    }

    /**
     * Detects onsets in the given audio buffer.
     *
     * <p>The buffer is divided into overlapping frames of size {@code fftSize}
     * with the configured hop size. Spectral flux is computed for each frame,
     * and frames exceeding the adaptive threshold are reported as onsets.</p>
     *
     * @param samples mono audio samples
     * @return list of detected onsets with frame indices and strength values
     */
    public List<Onset> detect(float[] samples) {
        List<Onset> onsets = new ArrayList<>();
        List<Double> fluxValues = new ArrayList<>();

        // Reset previous magnitudes for fresh analysis
        java.util.Arrays.fill(prevMagnitudes, 0.0);

        int numFrames = (samples.length - fftSize) / hopSize + 1;
        if (numFrames <= 0) {
            return onsets;
        }

        // Compute spectral flux for each frame
        for (int frame = 0; frame < numFrames; frame++) {
            int offset = frame * hopSize;
            double flux = computeSpectralFlux(samples, offset);
            fluxValues.add(flux);
        }

        // Peak picking with adaptive threshold
        pickPeaks(fluxValues, onsets);

        return onsets;
    }

    /**
     * Converts a frame index to a time in seconds.
     *
     * @param frameIndex the frame index from an {@link Onset}
     * @return the time in seconds
     */
    public double frameIndexToSeconds(int frameIndex) {
        return (double) frameIndex * hopSize / sampleRate;
    }

    /** Returns the FFT size. */
    public int getFftSize() {
        return fftSize;
    }

    /** Returns the hop size. */
    public int getHopSize() {
        return hopSize;
    }

    /** Returns the sample rate. */
    public double getSampleRate() {
        return sampleRate;
    }

    /** Returns the sensitivity threshold. */
    public double getSensitivityThreshold() {
        return sensitivityThreshold;
    }

    // ----------------------------------------------------------------
    // Internal DSP methods
    // ----------------------------------------------------------------

    /**
     * Computes the positive spectral flux for a single frame.
     */
    private double computeSpectralFlux(float[] samples, int offset) {
        int length = Math.min(fftSize, samples.length - offset);

        // Apply window and load into real buffer
        for (int i = 0; i < length; i++) {
            real[i] = samples[offset + i] * window[i];
            imag[i] = 0.0;
        }
        for (int i = length; i < fftSize; i++) {
            real[i] = 0.0;
            imag[i] = 0.0;
        }

        // In-place FFT
        FftUtils.fft(real, imag);

        // Compute positive spectral flux
        int binCount = fftSize / 2;
        double flux = 0.0;
        for (int i = 0; i < binCount; i++) {
            double magnitude = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
            double diff = magnitude - prevMagnitudes[i];
            if (diff > 0) {
                flux += diff;
            }
            prevMagnitudes[i] = magnitude;
        }

        return flux;
    }

    /**
     * Picks peaks from the flux values using an adaptive threshold.
     * The threshold is based on a running mean of recent flux values,
     * combined with a global noise floor to avoid triggering on
     * numerically insignificant variations.
     */
    private void pickPeaks(List<Double> fluxValues, List<Onset> onsets) {
        if (fluxValues.size() < 3) {
            return;
        }

        int windowSize = Math.max(1, (int) (sampleRate / hopSize * 0.1)); // ~100ms window

        // Compute global mean as noise floor estimate — the presence of
        // genuine onsets raises the mean, preventing tiny numerical
        // variations from being treated as onsets in steady-state signals.
        double globalSum = 0.0;
        for (double v : fluxValues) {
            globalSum += v;
        }
        double globalMean = globalSum / fluxValues.size();

        for (int i = 1; i < fluxValues.size() - 1; i++) {
            double current = fluxValues.get(i);
            double prev = fluxValues.get(i - 1);
            double next = fluxValues.get(i + 1);

            // Must be a local peak
            if (current <= prev || current <= next) {
                continue;
            }

            // Compute adaptive threshold from local mean
            int start = Math.max(0, i - windowSize);
            int end = Math.min(fluxValues.size(), i + windowSize + 1);
            double sum = 0.0;
            for (int j = start; j < end; j++) {
                sum += fluxValues.get(j);
            }
            double localMean = sum / (end - start);
            double adaptiveThreshold = localMean * sensitivityThreshold;

            // Must exceed both the adaptive threshold and the global noise floor
            if (current > adaptiveThreshold && current > globalMean * sensitivityThreshold) {
                onsets.add(new Onset(i, current));
            }
        }
    }

    private static double[] createHannWindow(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return w;
    }
}
