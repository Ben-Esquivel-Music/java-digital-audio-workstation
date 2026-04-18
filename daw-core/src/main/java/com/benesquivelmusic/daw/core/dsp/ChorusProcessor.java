package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Chorus effect processor using a modulated delay line.
 *
 * <p>Creates a rich, shimmering sound by mixing the dry signal with one or
 * more copies that are delayed by a slowly varying amount controlled by
 * a low-frequency oscillator (LFO). The time-varying delay introduces
 * subtle pitch modulation that thickens the sound.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable LFO rate (0.1–10 Hz) and depth</li>
 *   <li>Base delay offset for chorus character</li>
 *   <li>Wet/dry mix control</li>
 *   <li>Per-channel processing for stereo operation</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class ChorusProcessor implements AudioProcessor {

    private static final double MAX_DELAY_MS = 50.0;

    private final int channels;
    private final double sampleRate;
    private final int maxDelaySamples;
    private final float[][] delayLines;
    private final int[] writePositions;
    private double rateHz;
    private double depthMs;
    private double baseDelayMs;
    private double mix;
    private double lfoPhase;
    private final double lfoPhaseIncrement;

    /**
     * Creates a chorus processor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public ChorusProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.maxDelaySamples = (int) (MAX_DELAY_MS * 0.001 * sampleRate) + 1;
        this.delayLines = new float[channels][maxDelaySamples];
        this.writePositions = new int[channels];
        this.rateHz = 1.5;
        this.depthMs = 3.0;
        this.baseDelayMs = 7.0;
        this.mix = 0.5;
        this.lfoPhase = 0.0;
        this.lfoPhaseIncrement = 2.0 * Math.PI / sampleRate;
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);

        for (int frame = 0; frame < numFrames; frame++) {
            // Compute LFO value (sine wave)
            double lfoValue = Math.sin(lfoPhase);
            lfoPhase += lfoPhaseIncrement * rateHz;
            if (lfoPhase >= 2.0 * Math.PI) {
                lfoPhase -= 2.0 * Math.PI;
            }

            // Compute modulated delay in samples
            double delayMs = baseDelayMs + depthMs * lfoValue;
            double delaySamplesFloat = delayMs * 0.001 * sampleRate;

            // Clamp to valid range
            delaySamplesFloat = Math.max(0.0, Math.min(delaySamplesFloat, maxDelaySamples - 2));

            for (int ch = 0; ch < activeCh; ch++) {
                float input = inputBuffer[ch][frame];

                // Write to delay line
                delayLines[ch][writePositions[ch]] = input;

                // Read with linear interpolation for smooth modulation
                float delayed = DspUtils.readInterpolated(delayLines[ch],
                        writePositions[ch], delaySamplesFloat, maxDelaySamples);

                // Advance write position
                writePositions[ch] = (writePositions[ch] + 1) % maxDelaySamples;

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (input * (1.0 - mix) + delayed * mix);
            }
        }
    }

    // --- Parameter accessors ---

    @ProcessorParam(id = 0, name = "Rate", min = 0.1, max = 10.0, defaultValue = 1.0, unit = "Hz")
    public double getRateHz() { return rateHz; }

    public void setRateHz(double rateHz) {
        if (rateHz <= 0 || rateHz > 10.0) {
            throw new IllegalArgumentException("rateHz must be in (0, 10]: " + rateHz);
        }
        this.rateHz = rateHz;
    }

    @ProcessorParam(id = 1, name = "Depth", min = 0.1, max = 20.0, defaultValue = 5.0, unit = "ms")
    public double getDepthMs() { return depthMs; }

    public void setDepthMs(double depthMs) {
        if (depthMs < 0) {
            throw new IllegalArgumentException("depthMs must be >= 0: " + depthMs);
        }
        this.depthMs = depthMs;
    }

    @ProcessorParam(id = 2, name = "Base Delay", min = 1.0, max = 50.0, defaultValue = 10.0, unit = "ms")
    public double getBaseDelayMs() { return baseDelayMs; }

    public void setBaseDelayMs(double baseDelayMs) {
        if (baseDelayMs < 0 || baseDelayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException(
                    "baseDelayMs must be in [0, " + MAX_DELAY_MS + "]: " + baseDelayMs);
        }
        this.baseDelayMs = baseDelayMs;
    }

    @ProcessorParam(id = 3, name = "Mix", min = 0.0, max = 1.0, defaultValue = 0.5)
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
        lfoPhase = 0.0;
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }
}
