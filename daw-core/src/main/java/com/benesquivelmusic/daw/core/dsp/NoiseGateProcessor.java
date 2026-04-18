package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Noise gate processor with threshold, attack, hold, and release controls.
 *
 * <p>Attenuates audio below the threshold to reduce noise floor during
 * quiet passages. Implements the noise reduction capabilities referenced
 * in the mastering-techniques research (§5 — Noise Reduction and
 * Restoration).</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Adjustable threshold (open/close)</li>
 *   <li>Configurable attack, hold, and release times</li>
 *   <li>Adjustable range (attenuation depth) from 0 dB to full cutoff</li>
 *   <li>External sidechain input for keyed gating</li>
 * </ul>
 *
 * <p>When used with a sidechain source, the external signal drives the
 * gate trigger while gating is applied to the main input. This enables
 * techniques such as keyed gating (e.g., triggering a snare gate from a
 * dedicated trigger track).</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@InsertEffect(type = "NOISE_GATE", displayName = "Noise Gate")
public final class NoiseGateProcessor implements SidechainAwareProcessor {

    /** Gate state. */
    private enum GateState { CLOSED, ATTACK, OPEN, HOLD, RELEASE }

    private final int channels;
    private final double sampleRate;
    private double thresholdDb;
    private double attackMs;
    private double holdMs;
    private double releaseMs;
    private double rangeDb;

    private GateState state;
    private double envelope;
    private double attackCoeff;
    private double releaseCoeff;
    private int holdSamples;
    private int holdCounter;

    /**
     * Creates a noise gate with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public NoiseGateProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.thresholdDb = -40.0;
        this.attackMs = 1.0;
        this.holdMs = 50.0;
        this.releaseMs = 100.0;
        this.rangeDb = -80.0;
        this.state = GateState.CLOSED;
        this.envelope = 0.0;
        recalculateCoefficients();
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        processInternal(inputBuffer, inputBuffer, outputBuffer, numFrames);
    }

    @RealTimeSafe
    @Override
    public void processSidechain(float[][] inputBuffer, float[][] sidechainBuffer,
                                 float[][] outputBuffer, int numFrames) {
        processInternal(inputBuffer, sidechainBuffer, outputBuffer, numFrames);
    }

    private void processInternal(float[][] inputBuffer, float[][] detectionBuffer,
                                 float[][] outputBuffer, int numFrames) {
        double thresholdLinear = Math.pow(10.0, thresholdDb / 20.0);
        double rangeLinear = Math.pow(10.0, rangeDb / 20.0);
        int detectionChannels = Math.min(channels, detectionBuffer.length);
        int outputChannels = Math.min(channels, inputBuffer.length);

        for (int frame = 0; frame < numFrames; frame++) {
            // Detect peak from detection source (sidechain or main input)
            double peak = 0.0;
            for (int ch = 0; ch < detectionChannels; ch++) {
                double abs = Math.abs(detectionBuffer[ch][frame]);
                if (abs > peak) peak = abs;
            }

            // State machine
            switch (state) {
                case CLOSED -> {
                    if (peak >= thresholdLinear) {
                        state = GateState.ATTACK;
                    }
                }
                case ATTACK -> {
                    envelope += (1.0 - envelope) * (1.0 - attackCoeff);
                    if (envelope >= 0.999) {
                        envelope = 1.0;
                        state = GateState.OPEN;
                    }
                }
                case OPEN -> {
                    envelope = 1.0;
                    if (peak < thresholdLinear) {
                        state = GateState.HOLD;
                        holdCounter = holdSamples;
                    }
                }
                case HOLD -> {
                    envelope = 1.0;
                    holdCounter--;
                    if (holdCounter <= 0) {
                        state = GateState.RELEASE;
                    }
                    if (peak >= thresholdLinear) {
                        state = GateState.OPEN;
                    }
                }
                case RELEASE -> {
                    envelope *= releaseCoeff;
                    if (envelope <= rangeLinear + 0.001) {
                        envelope = 0.0;
                        state = GateState.CLOSED;
                    }
                    if (peak >= thresholdLinear) {
                        state = GateState.ATTACK;
                    }
                }
            }

            // Apply gate envelope to main input
            double gain = rangeLinear + envelope * (1.0 - rangeLinear);
            for (int ch = 0; ch < outputChannels; ch++) {
                outputBuffer[ch][frame] = (float) (inputBuffer[ch][frame] * gain);
            }
        }
    }

    // --- Parameter accessors ---

    @ProcessorParam(id = 0, name = "Threshold", min = -80.0, max = 0.0, defaultValue = -40.0, unit = "dB")
    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double thresholdDb) { this.thresholdDb = thresholdDb; }

    @ProcessorParam(id = 1, name = "Attack", min = 0.01, max = 50.0, defaultValue = 1.0, unit = "ms")
    public double getAttackMs() { return attackMs; }
    public void setAttackMs(double attackMs) {
        this.attackMs = attackMs;
        recalculateCoefficients();
    }

    @ProcessorParam(id = 2, name = "Hold", min = 0.0, max = 500.0, defaultValue = 50.0, unit = "ms")
    public double getHoldMs() { return holdMs; }
    public void setHoldMs(double holdMs) {
        this.holdMs = holdMs;
        recalculateCoefficients();
    }

    @ProcessorParam(id = 3, name = "Release", min = 1.0, max = 500.0, defaultValue = 50.0, unit = "ms")
    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double releaseMs) {
        this.releaseMs = releaseMs;
        recalculateCoefficients();
    }

    @ProcessorParam(id = 4, name = "Range", min = -80.0, max = 0.0, defaultValue = -80.0, unit = "dB")
    public double getRangeDb() { return rangeDb; }
    public void setRangeDb(double rangeDb) { this.rangeDb = rangeDb; }

    @Override
    public void reset() {
        state = GateState.CLOSED;
        envelope = 0.0;
        holdCounter = 0;
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    private void recalculateCoefficients() {
        attackCoeff = DspUtils.envelopeCoefficient(attackMs, sampleRate);
        releaseCoeff = DspUtils.envelopeCoefficient(releaseMs, sampleRate);
        holdSamples = (int) (holdMs * 0.001 * sampleRate);
    }
}
