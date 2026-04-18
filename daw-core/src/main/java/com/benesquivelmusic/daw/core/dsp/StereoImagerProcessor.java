package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Stereo imaging processor using mid/side encoding.
 *
 * <p>Controls the stereo width of a signal by adjusting the balance
 * between mid (center) and side (difference) components. Implements
 * the stereo imaging described in the mastering-techniques research
 * (§7 — Stereo Imaging), including:
 * <ul>
 *   <li>Width control from 0.0 (mono) to 2.0 (extra-wide)</li>
 *   <li>Mid/side encoding and decoding</li>
 *   <li>Low-frequency mono summing for playback consistency</li>
 *   <li>Frequency-dependent stereo width (independent width per band)</li>
 *   <li>Stereo widening via M/S gain manipulation</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@InsertEffect(type = "STEREO_IMAGER", displayName = "Stereo Imager", stereoOnly = true)
public final class StereoImagerProcessor implements AudioProcessor {

    private double width;
    private boolean monoLowFrequency;
    private double monoLowCutoffHz;
    private double sampleRate;

    // Low-frequency crossover filter state
    private BiquadFilter lowPassLeft;
    private BiquadFilter lowPassRight;
    private BiquadFilter highPassLeft;
    private BiquadFilter highPassRight;

    // Frequency-dependent (multi-band) width state
    private boolean multiBandEnabled;
    private double[] crossoverFrequencies;
    private double[] bandWidths;
    private BiquadFilter[] bandLpLeft;
    private BiquadFilter[] bandLpRight;
    private BiquadFilter[] bandHpLeft;
    private BiquadFilter[] bandHpRight;

    /**
     * Creates a stereo imager with default settings.
     *
     * @param sampleRate the sample rate in Hz
     */
    public StereoImagerProcessor(double sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.width = 1.0; // Unity (no change)
        this.monoLowFrequency = false;
        this.monoLowCutoffHz = 120.0;
        this.multiBandEnabled = false;
        rebuildCrossoverFilters();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (inputBuffer.length < 2 || outputBuffer.length < 2) {
            // Pass through for mono signals
            int channels = Math.min(inputBuffer.length, outputBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        if (multiBandEnabled) {
            processMultiBand(inputBuffer, outputBuffer, numFrames);
        } else if (monoLowFrequency) {
            processMonoLow(inputBuffer, outputBuffer, numFrames);
        } else {
            processSimple(inputBuffer, outputBuffer, numFrames);
        }
    }

    private void processSimple(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            double left = inputBuffer[0][i];
            double right = inputBuffer[1][i];
            double mid = (left + right) * 0.5;
            double side = (left - right) * 0.5;
            side *= width;
            outputBuffer[0][i] = (float) (mid + side);
            outputBuffer[1][i] = (float) (mid - side);
        }
    }

    private void processMonoLow(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            double left = inputBuffer[0][i];
            double right = inputBuffer[1][i];

            // Split into low and high frequency bands
            double lowL = lowPassLeft.processSample((float) left);
            double lowR = lowPassRight.processSample((float) right);
            double highL = highPassLeft.processSample((float) left);
            double highR = highPassRight.processSample((float) right);

            // Mono-sum the low frequencies
            double lowMono = (lowL + lowR) * 0.5;

            // Apply width to high frequencies only
            double midHigh = (highL + highR) * 0.5;
            double sideHigh = (highL - highR) * 0.5;
            sideHigh *= width;
            highL = midHigh + sideHigh;
            highR = midHigh - sideHigh;

            outputBuffer[0][i] = (float) (lowMono + highL);
            outputBuffer[1][i] = (float) (lowMono + highR);
        }
    }

    private void processMultiBand(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int numCrossovers = crossoverFrequencies.length;

        for (int i = 0; i < numFrames; i++) {
            double remainingL = inputBuffer[0][i];
            double remainingR = inputBuffer[1][i];
            double outL = 0.0;
            double outR = 0.0;

            // Split at each crossover and apply per-band width
            for (int c = 0; c < numCrossovers; c++) {
                double bandL = bandLpLeft[c].processSample((float) remainingL);
                double bandR = bandLpRight[c].processSample((float) remainingR);
                remainingL = bandHpLeft[c].processSample((float) remainingL);
                remainingR = bandHpRight[c].processSample((float) remainingR);

                // Apply M/S width to this band
                double mid = (bandL + bandR) * 0.5;
                double side = (bandL - bandR) * 0.5;
                side *= bandWidths[c];
                outL += mid + side;
                outR += mid - side;
            }

            // Final (highest) band uses remaining signal
            double mid = (remainingL + remainingR) * 0.5;
            double side = (remainingL - remainingR) * 0.5;
            side *= bandWidths[numCrossovers];
            outL += mid + side;
            outR += mid - side;

            outputBuffer[0][i] = (float) outL;
            outputBuffer[1][i] = (float) outR;
        }
    }

    /**
     * Returns the stereo width (0.0 = mono, 1.0 = unchanged, 2.0 = extra-wide).
     */
    @ProcessorParam(id = 0, name = "Width", min = 0.0, max = 2.0, defaultValue = 1.0)
    public double getWidth() { return width; }

    /**
     * Sets the stereo width.
     *
     * @param width width in the range [0.0, 2.0]
     */
    public void setWidth(double width) {
        if (width < 0.0 || width > 2.0) {
            throw new IllegalArgumentException("width must be in [0.0, 2.0]: " + width);
        }
        this.width = width;
    }

    /** Returns whether low-frequency mono summing is enabled. */
    public boolean isMonoLowFrequency() { return monoLowFrequency; }

    /** Enables or disables low-frequency mono summing. */
    public void setMonoLowFrequency(boolean enabled) { this.monoLowFrequency = enabled; }

    /** Returns the low-frequency mono cutoff in Hz. */
    public double getMonoLowCutoffHz() { return monoLowCutoffHz; }

    /** Sets the low-frequency mono cutoff in Hz. */
    public void setMonoLowCutoffHz(double hz) {
        if (hz <= 0) throw new IllegalArgumentException("cutoff must be positive: " + hz);
        this.monoLowCutoffHz = hz;
        rebuildCrossoverFilters();
    }

    /** Returns whether frequency-dependent (multi-band) width is enabled. */
    public boolean isMultiBandEnabled() { return multiBandEnabled; }

    /**
     * Returns a copy of the crossover frequencies, or an empty array if
     * multi-band mode is not configured.
     */
    public double[] getCrossoverFrequencies() {
        return crossoverFrequencies == null
                ? new double[0]
                : crossoverFrequencies.clone();
    }

    /**
     * Returns a copy of the per-band widths, or an empty array if
     * multi-band mode is not configured.
     */
    public double[] getBandWidths() {
        return bandWidths == null
                ? new double[0]
                : bandWidths.clone();
    }

    /**
     * Configures frequency-dependent stereo width control.
     *
     * <p>Splits the signal into {@code crossoverFreqs.length + 1} bands
     * at the given crossover frequencies and applies an independent width
     * to each band using M/S gain manipulation.</p>
     *
     * <p>When multi-band mode is enabled it supersedes both the global
     * {@link #setWidth width} and the {@link #setMonoLowFrequency mono-low}
     * feature. To achieve mono-bass in multi-band mode, set the lowest
     * band's width to {@code 0.0}.</p>
     *
     * @param crossoverFreqs sorted crossover frequencies in Hz (ascending)
     * @param widths         per-band widths in [0.0, 2.0]; length must be
     *                       {@code crossoverFreqs.length + 1}
     * @throws IllegalArgumentException if any argument is invalid
     */
    public void setBandWidths(double[] crossoverFreqs, double[] widths) {
        if (crossoverFreqs == null || widths == null) {
            throw new IllegalArgumentException("arrays must not be null");
        }
        if (widths.length != crossoverFreqs.length + 1) {
            throw new IllegalArgumentException(
                    "widths.length must be crossoverFreqs.length + 1");
        }
        for (int i = 0; i < crossoverFreqs.length; i++) {
            if (crossoverFreqs[i] <= 0 || crossoverFreqs[i] >= sampleRate / 2) {
                throw new IllegalArgumentException(
                        "crossover frequency out of range: " + crossoverFreqs[i]);
            }
            if (i > 0 && crossoverFreqs[i] <= crossoverFreqs[i - 1]) {
                throw new IllegalArgumentException(
                        "crossover frequencies must be in ascending order");
            }
        }
        for (double w : widths) {
            if (w < 0.0 || w > 2.0) {
                throw new IllegalArgumentException(
                        "band width must be in [0.0, 2.0]: " + w);
            }
        }

        this.crossoverFrequencies = crossoverFreqs.clone();
        this.bandWidths = widths.clone();
        rebuildMultiBandFilters();
        this.multiBandEnabled = true;
    }

    /**
     * Disables multi-band mode and reverts to single-band processing.
     */
    public void clearBandWidths() {
        multiBandEnabled = false;
        crossoverFrequencies = null;
        bandWidths = null;
        bandLpLeft = null;
        bandLpRight = null;
        bandHpLeft = null;
        bandHpRight = null;
    }

    @Override
    public void reset() {
        if (lowPassLeft != null) lowPassLeft.reset();
        if (lowPassRight != null) lowPassRight.reset();
        if (highPassLeft != null) highPassLeft.reset();
        if (highPassRight != null) highPassRight.reset();
        resetMultiBandFilters();
    }

    @Override
    public int getInputChannelCount() { return 2; }

    @Override
    public int getOutputChannelCount() { return 2; }

    private void rebuildCrossoverFilters() {
        lowPassLeft = BiquadFilter.create(
                BiquadFilter.FilterType.LOW_PASS, sampleRate, monoLowCutoffHz, 0.707, 0);
        lowPassRight = BiquadFilter.create(
                BiquadFilter.FilterType.LOW_PASS, sampleRate, monoLowCutoffHz, 0.707, 0);
        highPassLeft = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_PASS, sampleRate, monoLowCutoffHz, 0.707, 0);
        highPassRight = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_PASS, sampleRate, monoLowCutoffHz, 0.707, 0);
    }

    private void rebuildMultiBandFilters() {
        int n = crossoverFrequencies.length;
        bandLpLeft = new BiquadFilter[n];
        bandLpRight = new BiquadFilter[n];
        bandHpLeft = new BiquadFilter[n];
        bandHpRight = new BiquadFilter[n];

        for (int i = 0; i < n; i++) {
            double freq = crossoverFrequencies[i];
            bandLpLeft[i] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, freq, 0.707, 0);
            bandLpRight[i] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, freq, 0.707, 0);
            bandHpLeft[i] = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_PASS, sampleRate, freq, 0.707, 0);
            bandHpRight[i] = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_PASS, sampleRate, freq, 0.707, 0);
        }
    }

    private void resetMultiBandFilters() {
        if (bandLpLeft != null) {
            for (int i = 0; i < bandLpLeft.length; i++) {
                bandLpLeft[i].reset();
                bandLpRight[i].reset();
                bandHpLeft[i].reset();
                bandHpRight[i].reset();
            }
        }
    }
}
