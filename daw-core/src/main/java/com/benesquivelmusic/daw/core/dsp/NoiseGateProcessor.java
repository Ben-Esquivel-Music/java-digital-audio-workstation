package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

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
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class NoiseGateProcessor implements AudioProcessor {

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

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        double thresholdLinear = Math.pow(10.0, thresholdDb / 20.0);
        double rangeLinear = Math.pow(10.0, rangeDb / 20.0);

        for (int frame = 0; frame < numFrames; frame++) {
            // Detect peak across channels
            double peak = 0.0;
            for (int ch = 0; ch < Math.min(channels, inputBuffer.length); ch++) {
                double abs = Math.abs(inputBuffer[ch][frame]);
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
                        envelope = rangeLinear;
                        state = GateState.CLOSED;
                    }
                    if (peak >= thresholdLinear) {
                        state = GateState.ATTACK;
                    }
                }
            }

            // Apply gate envelope
            double gain = rangeLinear + envelope * (1.0 - rangeLinear);
            for (int ch = 0; ch < Math.min(channels, inputBuffer.length); ch++) {
                outputBuffer[ch][frame] = (float) (inputBuffer[ch][frame] * gain);
            }
        }
    }

    // --- Parameter accessors ---

    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double thresholdDb) { this.thresholdDb = thresholdDb; }

    public double getAttackMs() { return attackMs; }
    public void setAttackMs(double attackMs) {
        this.attackMs = attackMs;
        recalculateCoefficients();
    }

    public double getHoldMs() { return holdMs; }
    public void setHoldMs(double holdMs) {
        this.holdMs = holdMs;
        recalculateCoefficients();
    }

    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double releaseMs) {
        this.releaseMs = releaseMs;
        recalculateCoefficients();
    }

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
