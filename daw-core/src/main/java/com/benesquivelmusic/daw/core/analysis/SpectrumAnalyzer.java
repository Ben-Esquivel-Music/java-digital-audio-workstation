package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

import java.util.Arrays;

/**
 * Real-time FFT-based spectrum analyzer using a pure-Java Cooley–Tukey
 * radix-2 implementation — no JNI required.
 *
 * <p>Processes mono or stereo audio frames through a configurable windowed
 * FFT and produces {@link SpectrumData} snapshots containing per-bin
 * magnitude in dB. The analyzer supports configurable FFT sizes (1024,
 * 2048, 4096, 8192), windowing functions (Hann, Hamming, Blackman-Harris),
 * exponential smoothing, and peak hold display.</p>
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
    private final WindowType windowType;
    private final double[] window;
    private final double[] real;
    private final double[] imag;
    private final float[] smoothedMagnitudes;
    private final boolean peakHoldEnabled;
    private final float[] peakHoldMagnitudes;
    private final double peakDecayRateDbPerUpdate;
    private volatile SpectrumData latestData;
    private volatile SpectrumData preEqData;

    /**
     * Creates a spectrum analyzer with the given FFT size, sample rate,
     * smoothing factor, window type, and peak hold configuration.
     *
     * @param fftSize                  FFT window size (must be a power of two)
     * @param sampleRate               the audio sample rate in Hz
     * @param smoothingFactor          exponential smoothing in [0, 1); 0 = no smoothing
     * @param windowType               the window function to apply before FFT
     * @param peakHoldEnabled          whether to track peak hold magnitudes
     * @param peakDecayRateDbPerUpdate  peak hold decay rate in dB per update (0 = no decay)
     */
    public SpectrumAnalyzer(int fftSize, double sampleRate, double smoothingFactor,
                            WindowType windowType, boolean peakHoldEnabled,
                            double peakDecayRateDbPerUpdate) {
        if (fftSize <= 0 || (fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException("fftSize must be a positive power of two: " + fftSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (smoothingFactor < 0 || smoothingFactor >= 1.0) {
            throw new IllegalArgumentException("smoothingFactor must be in [0, 1): " + smoothingFactor);
        }
        if (peakDecayRateDbPerUpdate < 0) {
            throw new IllegalArgumentException(
                    "peakDecayRateDbPerUpdate must be non-negative: " + peakDecayRateDbPerUpdate);
        }
        this.fftSize = fftSize;
        this.sampleRate = sampleRate;
        this.smoothingFactor = smoothingFactor;
        this.windowType = windowType;
        this.window = FftUtils.createWindow(windowType, fftSize);
        this.real = new double[fftSize];
        this.imag = new double[fftSize];
        this.smoothedMagnitudes = new float[fftSize / 2];
        Arrays.fill(smoothedMagnitudes, (float) DB_FLOOR);
        this.peakHoldEnabled = peakHoldEnabled;
        this.peakHoldMagnitudes = new float[fftSize / 2];
        Arrays.fill(peakHoldMagnitudes, (float) DB_FLOOR);
        this.peakDecayRateDbPerUpdate = peakDecayRateDbPerUpdate;
    }

    /**
     * Creates a spectrum analyzer with peak hold disabled and Hann window.
     *
     * @param fftSize         FFT window size (must be a power of two)
     * @param sampleRate      the audio sample rate in Hz
     * @param smoothingFactor exponential smoothing in [0, 1); 0 = no smoothing
     */
    public SpectrumAnalyzer(int fftSize, double sampleRate, double smoothingFactor) {
        this(fftSize, sampleRate, smoothingFactor, WindowType.HANN, false, 0.0);
    }

    /**
     * Creates a spectrum analyzer with default smoothing (0.8) and Hann window.
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

        FftUtils.fft(real, imag);

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

            if (peakHoldEnabled) {
                if (magnitudes[i] > peakHoldMagnitudes[i]) {
                    peakHoldMagnitudes[i] = magnitudes[i];
                } else if (peakDecayRateDbPerUpdate > 0) {
                    peakHoldMagnitudes[i] = (float) Math.max(
                            peakHoldMagnitudes[i] - peakDecayRateDbPerUpdate, DB_FLOOR);
                }
            }
        }

        float[] peakHold = peakHoldEnabled ? peakHoldMagnitudes.clone() : null;
        latestData = new SpectrumData(magnitudes, peakHold, fftSize, sampleRate);
    }

    /**
     * Processes stereo audio samples by averaging left and right channels.
     *
     * <p>Creates a mono mix of the two channels and processes the result
     * through the FFT pipeline. For separate per-channel analysis, use
     * two independent {@link SpectrumAnalyzer} instances.</p>
     *
     * @param leftSamples  left channel audio samples
     * @param rightSamples right channel audio samples
     */
    public void processStereo(float[] leftSamples, float[] rightSamples) {
        int length = Math.min(leftSamples.length, rightSamples.length);
        float[] mono = new float[length];
        for (int i = 0; i < length; i++) {
            mono[i] = (leftSamples[i] + rightSamples[i]) * 0.5f;
        }
        process(mono);
    }

    /**
     * Stores a snapshot of the current spectrum as the pre-EQ reference.
     *
     * <p>Call this method before applying EQ to capture the "before" spectrum.
     * The display layer can then overlay the pre-EQ and post-EQ spectra to
     * visualize EQ impact.</p>
     */
    public void capturePreEqSnapshot() {
        preEqData = latestData;
    }

    /**
     * Returns the most recently captured pre-EQ spectrum snapshot.
     *
     * @return the pre-EQ spectrum data, or {@code null} if not captured
     */
    public SpectrumData getPreEqData() {
        return preEqData;
    }

    /** Returns the FFT size. */
    public int getFftSize() {
        return fftSize;
    }

    /** Returns the sample rate. */
    public double getSampleRate() {
        return sampleRate;
    }

    /** Returns the window type used for FFT analysis. */
    public WindowType getWindowType() {
        return windowType;
    }

    /** Returns whether peak hold tracking is enabled. */
    public boolean isPeakHoldEnabled() {
        return peakHoldEnabled;
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
     * Resets the analyzer state, clearing all smoothed data and peak hold.
     */
    public void reset() {
        Arrays.fill(smoothedMagnitudes, (float) DB_FLOOR);
        Arrays.fill(peakHoldMagnitudes, (float) DB_FLOOR);
        latestData = null;
        preEqData = null;
    }

}
