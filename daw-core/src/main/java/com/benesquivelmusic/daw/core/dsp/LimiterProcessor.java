package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Look-ahead brick-wall limiter with true-peak detection.
 *
 * <p>Implements the limiting described in the mastering-techniques research
 * (§4 — Dynamics Processing), including:
 * <ul>
 *   <li>Configurable ceiling (output level cap) in dBTP</li>
 *   <li>Configurable look-ahead delay (1–5 ms) for transparent transient limiting</li>
 *   <li>ITU-R BS.1770-4 true-peak detection via 4× oversampling</li>
 *   <li>Attack and release envelope smoothing</li>
 *   <li>Auto-release mode that adapts release time based on signal dynamics</li>
 *   <li>Gain reduction metering and true-peak metering output</li>
 *   <li>Latency compensation reporting for the look-ahead delay</li>
 *   <li>Platform-specific ceiling presets via {@link TruePeakCeilingPreset}</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class LimiterProcessor implements AudioProcessor {

    private static final double MIN_LOOK_AHEAD_MS = 1.0;
    private static final double MAX_LOOK_AHEAD_MS = 5.0;
    private static final double DEFAULT_LOOK_AHEAD_MS = 5.0;
    private static final double DEFAULT_ATTACK_MS = 0.1;
    private static final double DEFAULT_RELEASE_MS = 50.0;
    private static final double AUTO_RELEASE_FAST_MS = 20.0;
    private static final double AUTO_RELEASE_SLOW_MS = 200.0;
    private static final double AUTO_RELEASE_THRESHOLD_DB = -3.0;

    private final int channels;
    private final double sampleRate;
    private double ceilingDb;
    private double attackMs;
    private double releaseMs;
    private int lookAheadSamples;
    private boolean autoRelease;

    // Look-ahead delay buffers
    private float[][] delayBuffers;
    private int delayWritePos;

    // Envelope
    private double envelopeLinear;
    private double attackCoeff;
    private double releaseCoeff;
    private double currentGainReductionDb;

    // True peak detection
    private TruePeakDetector[] truePeakDetectors;
    private double currentTruePeakLinear;
    private double currentTruePeakDbtp;

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
        this.attackMs = DEFAULT_ATTACK_MS;
        this.releaseMs = DEFAULT_RELEASE_MS;
        this.lookAheadSamples = (int) (DEFAULT_LOOK_AHEAD_MS * 0.001 * sampleRate);
        this.envelopeLinear = 0.0;
        this.autoRelease = false;

        initDelayBuffers();
        initTruePeakDetectors();
        recalculateCoefficients();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        double ceilingLinear = Math.pow(10.0, ceilingDb / 20.0);

        for (int frame = 0; frame < numFrames; frame++) {
            // Find true peak across all channels for this frame using 4× oversampling
            double truePeakLevel = 0.0;
            double samplePeakLevel = 0.0;
            int activeCh = Math.min(channels, inputBuffer.length);
            for (int ch = 0; ch < activeCh; ch++) {
                double sampleAbs = Math.abs(inputBuffer[ch][frame]);
                if (sampleAbs > samplePeakLevel) {
                    samplePeakLevel = sampleAbs;
                }
                double tp = truePeakDetectors[ch].processSample(inputBuffer[ch][frame]);
                if (tp > truePeakLevel) {
                    truePeakLevel = tp;
                }
            }

            // Use the higher of sample peak and true peak for envelope detection
            double peakLevel = Math.max(samplePeakLevel, truePeakLevel);

            // Update true peak metering
            if (truePeakLevel > currentTruePeakLinear) {
                currentTruePeakLinear = truePeakLevel;
                currentTruePeakDbtp = (currentTruePeakLinear > 0.0)
                        ? 20.0 * Math.log10(currentTruePeakLinear)
                        : -120.0;
            }

            // Envelope follower: smoothed attack and release
            if (peakLevel > envelopeLinear) {
                envelopeLinear = attackCoeff * envelopeLinear
                        + (1.0 - attackCoeff) * peakLevel;
            } else {
                double effectiveReleaseCoeff = releaseCoeff;
                if (autoRelease) {
                    effectiveReleaseCoeff = computeAutoReleaseCoeff();
                }
                envelopeLinear = effectiveReleaseCoeff * envelopeLinear
                        + (1.0 - effectiveReleaseCoeff) * peakLevel;
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
            for (int ch = 0; ch < activeCh; ch++) {
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

    /**
     * Returns the current true peak level in linear units.
     *
     * <p>This value is the highest true peak observed since the last call
     * to {@link #resetTruePeakMetering()} or {@link #reset()}.</p>
     */
    public double getTruePeakLinear() {
        return currentTruePeakLinear;
    }

    /**
     * Returns the current true peak level in dBTP.
     *
     * <p>This value is the highest true peak observed since the last call
     * to {@link #resetTruePeakMetering()} or {@link #reset()}.</p>
     */
    public double getTruePeakDbtp() {
        return currentTruePeakDbtp;
    }

    /**
     * Resets the true peak metering accumulators without resetting the
     * limiter envelope or delay buffers.
     */
    public void resetTruePeakMetering() {
        currentTruePeakLinear = 0.0;
        currentTruePeakDbtp = -120.0;
        for (var detector : truePeakDetectors) {
            detector.reset();
        }
    }

    /**
     * Returns the latency introduced by the look-ahead buffer, in samples.
     *
     * <p>Downstream processing should compensate for this delay to maintain
     * time alignment with other tracks.</p>
     */
    public int getLatencySamples() {
        return lookAheadSamples;
    }

    /**
     * Returns the latency introduced by the look-ahead buffer, in seconds.
     */
    public double getLatencySeconds() {
        return lookAheadSamples / sampleRate;
    }

    // --- Parameter accessors ---

    public double getCeilingDb() { return ceilingDb; }
    public void setCeilingDb(double ceilingDb) {
        if (ceilingDb > 0) throw new IllegalArgumentException("ceilingDb must be <= 0: " + ceilingDb);
        this.ceilingDb = ceilingDb;
    }

    /**
     * Sets the ceiling from a platform-specific preset.
     *
     * @param preset the platform preset to apply
     */
    public void setCeilingPreset(TruePeakCeilingPreset preset) {
        setCeilingDb(preset.getCeilingDbtp());
    }

    public double getAttackMs() { return attackMs; }
    public void setAttackMs(double attackMs) {
        if (attackMs < 0) throw new IllegalArgumentException("attackMs must be >= 0: " + attackMs);
        this.attackMs = attackMs;
        recalculateCoefficients();
    }

    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double releaseMs) {
        if (releaseMs < 0) throw new IllegalArgumentException("releaseMs must be >= 0: " + releaseMs);
        this.releaseMs = releaseMs;
        recalculateCoefficients();
    }

    public boolean isAutoRelease() { return autoRelease; }
    public void setAutoRelease(boolean autoRelease) {
        this.autoRelease = autoRelease;
    }

    public int getLookAheadSamples() { return lookAheadSamples; }
    public void setLookAheadSamples(int samples) {
        if (samples < 0) throw new IllegalArgumentException("lookAheadSamples must be >= 0: " + samples);
        this.lookAheadSamples = samples;
        initDelayBuffers();
    }

    /**
     * Sets the look-ahead delay in milliseconds (clamped to 1–5 ms).
     *
     * @param ms look-ahead time in milliseconds
     */
    public void setLookAheadMs(double ms) {
        double clamped = Math.max(MIN_LOOK_AHEAD_MS, Math.min(MAX_LOOK_AHEAD_MS, ms));
        this.lookAheadSamples = (int) (clamped * 0.001 * sampleRate);
        initDelayBuffers();
    }

    /**
     * Returns the look-ahead delay in milliseconds.
     */
    public double getLookAheadMs() {
        return lookAheadSamples / (0.001 * sampleRate);
    }

    @Override
    public void reset() {
        envelopeLinear = 0.0;
        currentGainReductionDb = 0.0;
        currentTruePeakLinear = 0.0;
        currentTruePeakDbtp = -120.0;
        delayWritePos = 0;
        for (float[] buf : delayBuffers) {
            Arrays.fill(buf, 0.0f);
        }
        for (var detector : truePeakDetectors) {
            detector.reset();
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

    private void initTruePeakDetectors() {
        truePeakDetectors = new TruePeakDetector[channels];
        for (int ch = 0; ch < channels; ch++) {
            truePeakDetectors[ch] = new TruePeakDetector();
        }
    }

    private void recalculateCoefficients() {
        attackCoeff = DspUtils.envelopeCoefficient(attackMs, sampleRate);
        releaseCoeff = DspUtils.envelopeCoefficient(releaseMs, sampleRate);
    }

    /**
     * Computes an adaptive release coefficient based on current gain reduction.
     * Heavy limiting uses fast release; light limiting uses slow release.
     */
    private double computeAutoReleaseCoeff() {
        double blend;
        if (currentGainReductionDb <= AUTO_RELEASE_THRESHOLD_DB) {
            blend = 1.0; // Heavy limiting → fast release
        } else if (currentGainReductionDb >= 0.0) {
            blend = 0.0; // No limiting → slow release
        } else {
            blend = currentGainReductionDb / AUTO_RELEASE_THRESHOLD_DB;
        }
        double adaptiveMs = AUTO_RELEASE_SLOW_MS + blend * (AUTO_RELEASE_FAST_MS - AUTO_RELEASE_SLOW_MS);
        return DspUtils.envelopeCoefficient(adaptiveMs, sampleRate);
    }
}
