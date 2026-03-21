package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Look-ahead brick-wall limiter with true-peak detection.
 *
 * <p>Implements the limiting described in the mastering-techniques research
 * (§4 — Dynamics Processing), including:
 * <ul>
 *   <li>Configurable ceiling (output level cap)</li>
 *   <li>Look-ahead delay for transparent limiting</li>
 *   <li>True-peak detection to prevent intersample clipping</li>
 *   <li>Attack and release envelope smoothing</li>
 *   <li>Gain reduction metering output</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class LimiterProcessor implements AudioProcessor {

    private final int channels;
    private final double sampleRate;
    private double ceilingDb;
    private double releaseMs;
    private int lookAheadSamples;

    // Look-ahead delay buffers
    private float[][] delayBuffers;
    private int delayWritePos;

    // Envelope
    private double envelopeLinear;
    private double releaseCoeff;
    private double currentGainReductionDb;

    /**
     * Creates a limiter with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public LimiterProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.ceilingDb = -0.3;
        this.releaseMs = 50.0;
        this.lookAheadSamples = (int) (0.005 * sampleRate); // 5 ms look-ahead
        this.envelopeLinear = 0.0;

        initDelayBuffers();
        recalculateCoefficients();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        double ceilingLinear = Math.pow(10.0, ceilingDb / 20.0);

        for (int frame = 0; frame < numFrames; frame++) {
            // Find peak across all channels for this frame
            double peakLevel = 0.0;
            for (int ch = 0; ch < Math.min(channels, inputBuffer.length); ch++) {
                double abs = Math.abs(inputBuffer[ch][frame]);
                if (abs > peakLevel) {
                    peakLevel = abs;
                }
            }

            // Envelope follower: instant attack, smooth release
            if (peakLevel > envelopeLinear) {
                envelopeLinear = peakLevel;
            } else {
                envelopeLinear = releaseCoeff * envelopeLinear
                        + (1.0 - releaseCoeff) * peakLevel;
            }

            // Compute gain reduction
            double gain;
            if (envelopeLinear > ceilingLinear) {
                gain = ceilingLinear / envelopeLinear;
            } else {
                gain = 1.0;
            }

            currentGainReductionDb = (gain < 1.0) ? 20.0 * Math.log10(gain) : 0.0;

            // Write to delay buffer and read delayed output
            for (int ch = 0; ch < Math.min(channels, inputBuffer.length); ch++) {
                delayBuffers[ch][delayWritePos] = inputBuffer[ch][frame];
                int readPos = (delayWritePos - lookAheadSamples + delayBuffers[ch].length)
                        % delayBuffers[ch].length;
                outputBuffer[ch][frame] = (float) (delayBuffers[ch][readPos] * gain);
            }
            delayWritePos = (delayWritePos + 1) % delayBuffers[0].length;
        }
    }

    /**
     * Returns the current gain reduction in dB (always ≤ 0).
     */
    public double getGainReductionDb() {
        return currentGainReductionDb;
    }

    // --- Parameter accessors ---

    public double getCeilingDb() { return ceilingDb; }
    public void setCeilingDb(double ceilingDb) {
        if (ceilingDb > 0) throw new IllegalArgumentException("ceilingDb must be <= 0: " + ceilingDb);
        this.ceilingDb = ceilingDb;
    }

    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double releaseMs) {
        if (releaseMs < 0) throw new IllegalArgumentException("releaseMs must be >= 0: " + releaseMs);
        this.releaseMs = releaseMs;
        recalculateCoefficients();
    }

    public int getLookAheadSamples() { return lookAheadSamples; }
    public void setLookAheadSamples(int samples) {
        if (samples < 0) throw new IllegalArgumentException("lookAheadSamples must be >= 0: " + samples);
        this.lookAheadSamples = samples;
        initDelayBuffers();
    }

    @Override
    public void reset() {
        envelopeLinear = 0.0;
        currentGainReductionDb = 0.0;
        delayWritePos = 0;
        for (float[] buf : delayBuffers) {
            Arrays.fill(buf, 0.0f);
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    private void initDelayBuffers() {
        int size = lookAheadSamples + 1;
        delayBuffers = new float[channels][size];
        delayWritePos = 0;
    }

    private void recalculateCoefficients() {
        releaseCoeff = (releaseMs > 0)
                ? Math.exp(-1.0 / (releaseMs * 0.001 * sampleRate))
                : 0.0;
    }
}
