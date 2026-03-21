package com.benesquivelmusic.daw.core.dsp;

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
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
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

        for (int i = 0; i < numFrames; i++) {
            double left = inputBuffer[0][i];
            double right = inputBuffer[1][i];

            if (monoLowFrequency) {
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
            } else {
                // Simple mid/side width control
                double mid = (left + right) * 0.5;
                double side = (left - right) * 0.5;
                side *= width;
                outputBuffer[0][i] = (float) (mid + side);
                outputBuffer[1][i] = (float) (mid - side);
            }
        }
    }

    /**
     * Returns the stereo width (0.0 = mono, 1.0 = unchanged, 2.0 = extra-wide).
     */
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

    @Override
    public void reset() {
        if (lowPassLeft != null) lowPassLeft.reset();
        if (lowPassRight != null) lowPassRight.reset();
        if (highPassLeft != null) highPassLeft.reset();
        if (highPassRight != null) highPassRight.reset();
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
}
