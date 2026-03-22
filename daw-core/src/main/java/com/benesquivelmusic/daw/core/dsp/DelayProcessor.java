package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Stereo delay effect processor with feedback and mix controls.
 *
 * <p>Implements a classic digital delay line with configurable delay time,
 * feedback, and wet/dry mix. Supports up to the configured maximum delay
 * time for creative and corrective applications.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable delay time in milliseconds</li>
 *   <li>Adjustable feedback (0.0–0.99) for repeating echoes</li>
 *   <li>Wet/dry mix control</li>
 *   <li>Per-channel delay lines for stereo operation</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class DelayProcessor implements AudioProcessor {

    private static final double MAX_FEEDBACK = 0.99;

    private final int channels;
    private final double sampleRate;
    private final int maxDelaySamples;
    private final float[][] delayLines;
    private final int[] writePositions;
    private double delayMs;
    private int delaySamples;
    private double feedback;
    private double mix;

    /**
     * Creates a delay processor.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     * @param maxDelayMs maximum delay time in milliseconds
     */
    public DelayProcessor(int channels, double sampleRate, double maxDelayMs) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (maxDelayMs <= 0) {
            throw new IllegalArgumentException("maxDelayMs must be positive: " + maxDelayMs);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.maxDelaySamples = (int) (maxDelayMs * 0.001 * sampleRate) + 1;
        this.delayLines = new float[channels][maxDelaySamples];
        this.writePositions = new int[channels];
        this.delayMs = maxDelayMs / 2.0;
        this.delaySamples = (int) (this.delayMs * 0.001 * sampleRate);
        this.feedback = 0.3;
        this.mix = 0.5;
    }

    /**
     * Creates a delay processor with a default maximum delay of 2000 ms.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public DelayProcessor(int channels, double sampleRate) {
        this(channels, sampleRate, 2000.0);
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);

        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < activeCh; ch++) {
                float input = inputBuffer[ch][frame];

                // Read from delay line
                int readPos = (writePositions[ch] - delaySamples + maxDelaySamples)
                        % maxDelaySamples;
                float delayed = delayLines[ch][readPos];

                // Write to delay line (input + feedback)
                delayLines[ch][writePositions[ch]] = (float) (input + delayed * feedback);

                // Advance write position
                writePositions[ch] = (writePositions[ch] + 1) % maxDelaySamples;

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (input * (1.0 - mix) + delayed * mix);
            }
        }
    }

    // --- Parameter accessors ---

    public double getDelayMs() { return delayMs; }

    public void setDelayMs(double delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0: " + delayMs);
        }
        double maxMs = (maxDelaySamples - 1) / (0.001 * sampleRate);
        if (delayMs > maxMs) {
            throw new IllegalArgumentException(
                    "delayMs (" + delayMs + ") exceeds maximum (" + maxMs + ")");
        }
        this.delayMs = delayMs;
        this.delaySamples = (int) (delayMs * 0.001 * sampleRate);
    }

    public double getFeedback() { return feedback; }

    public void setFeedback(double feedback) {
        if (feedback < 0 || feedback > MAX_FEEDBACK) {
            throw new IllegalArgumentException(
                    "feedback must be in [0, " + MAX_FEEDBACK + "]: " + feedback);
        }
        this.feedback = feedback;
    }

    public double getMix() { return mix; }

    public void setMix(double mix) {
        if (mix < 0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + mix);
        }
        this.mix = mix;
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            Arrays.fill(delayLines[ch], 0.0f);
            writePositions[ch] = 0;
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }
}
