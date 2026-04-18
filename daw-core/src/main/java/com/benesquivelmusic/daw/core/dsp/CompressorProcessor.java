package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;

/**
 * Dynamic range compressor with standard professional controls.
 *
 * <p>Implements the dynamics processing described in the mastering-techniques
 * research (§4 — Dynamics Processing), with:
 * <ul>
 *   <li>Adjustable threshold, ratio, attack, release, and knee</li>
 *   <li>Makeup gain with auto-gain option</li>
 *   <li>Peak or RMS detection modes</li>
 *   <li>Gain reduction metering output</li>
 *   <li>External sidechain input for keyed compression</li>
 * </ul>
 *
 * <p>The envelope follower uses logarithmic-domain smoothing for musically
 * accurate behavior. This is a pure-Java implementation — no JNI required.</p>
 *
 * <p>When used with a sidechain source, the external signal drives the
 * envelope detector while gain reduction is applied to the main input.
 * This enables classic sidechain compression techniques such as kick-driven
 * bass ducking and dialogue ducking.</p>
 */
@InsertEffect(type = "COMPRESSOR", displayName = "Compressor")
public final class CompressorProcessor implements SidechainAwareProcessor, GainReductionProvider {

    /** Detection mode for the compressor. */
    public enum DetectionMode { PEAK, RMS }

    private final int channels;
    private double thresholdDb;
    private double ratio;
    private double attackMs;
    private double releaseMs;
    private double kneeDb;
    private double makeupGainDb;
    private DetectionMode detectionMode;

    private double sampleRate;
    private double attackCoeff;
    private double releaseCoeff;
    private double envelopeDb;
    private double currentGainReductionDb;

    /**
     * Creates a compressor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public CompressorProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.thresholdDb = -20.0;
        this.ratio = 4.0;
        this.attackMs = 10.0;
        this.releaseMs = 100.0;
        this.kneeDb = 6.0;
        this.makeupGainDb = 0.0;
        this.detectionMode = DetectionMode.PEAK;
        this.envelopeDb = -120.0;
        recalculateCoefficients();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        processInternal(inputBuffer, inputBuffer, outputBuffer, numFrames);
    }

    @Override
    public void processSidechain(float[][] inputBuffer, float[][] sidechainBuffer,
                                 float[][] outputBuffer, int numFrames) {
        processInternal(inputBuffer, sidechainBuffer, outputBuffer, numFrames);
    }

    private void processInternal(float[][] inputBuffer, float[][] detectionBuffer,
                                 float[][] outputBuffer, int numFrames) {
        double makeupLinear = Math.pow(10.0, makeupGainDb / 20.0);
        int detectionChannels = Math.min(channels, detectionBuffer.length);
        int outputChannels = Math.min(channels, inputBuffer.length);

        for (int frame = 0; frame < numFrames; frame++) {
            // Detect level from detection source (sidechain or main input)
            double level = 0.0;
            for (int ch = 0; ch < detectionChannels; ch++) {
                double s = Math.abs(detectionBuffer[ch][frame]);
                if (detectionMode == DetectionMode.RMS) {
                    level += s * s;
                } else {
                    level = Math.max(level, s);
                }
            }
            if (detectionMode == DetectionMode.RMS) {
                level = Math.sqrt(level / channels);
            }

            // Convert to dB
            double inputDb = (level > 0) ? 20.0 * Math.log10(level) : -120.0;

            // Envelope follower (log domain)
            double coeff = (inputDb > envelopeDb) ? attackCoeff : releaseCoeff;
            envelopeDb = coeff * envelopeDb + (1.0 - coeff) * inputDb;

            // Gain computer with soft knee
            double gainReductionDb = computeGainReduction(envelopeDb);
            currentGainReductionDb = gainReductionDb;

            // Apply gain to main input
            double gainLinear = Math.pow(10.0, gainReductionDb / 20.0) * makeupLinear;
            for (int ch = 0; ch < outputChannels; ch++) {
                outputBuffer[ch][frame] = (float) (inputBuffer[ch][frame] * gainLinear);
            }
        }
    }

    private double computeGainReduction(double inputDb) {
        double halfKnee = kneeDb / 2.0;

        if (inputDb <= thresholdDb - halfKnee) {
            return 0.0; // Below threshold
        } else if (inputDb >= thresholdDb + halfKnee) {
            // Above threshold — full compression
            return (thresholdDb - inputDb) * (1.0 - 1.0 / ratio);
        } else {
            // In the knee region — quadratic interpolation
            double x = inputDb - thresholdDb + halfKnee;
            return (1.0 - 1.0 / ratio) * x * x / (2.0 * kneeDb) * -1.0;
        }
    }

    /**
     * Returns the current gain reduction in dB (always ≤ 0).
     */
    public double getGainReductionDb() {
        return currentGainReductionDb;
    }

    // --- Parameter accessors ---

    @ProcessorParam(id = 0, name = "Threshold", min = -60.0, max = 0.0, defaultValue = -20.0, unit = "dB")
    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double thresholdDb) { this.thresholdDb = thresholdDb; }

    @ProcessorParam(id = 1, name = "Ratio", min = 1.0, max = 20.0, defaultValue = 4.0)
    public double getRatio() { return ratio; }
    public void setRatio(double ratio) {
        if (ratio < 1.0) throw new IllegalArgumentException("ratio must be >= 1.0: " + ratio);
        this.ratio = ratio;
    }

    @ProcessorParam(id = 2, name = "Attack", min = 0.01, max = 100.0, defaultValue = 10.0, unit = "ms")
    public double getAttackMs() { return attackMs; }
    public void setAttackMs(double attackMs) {
        if (attackMs < 0) throw new IllegalArgumentException("attackMs must be >= 0: " + attackMs);
        this.attackMs = attackMs;
        recalculateCoefficients();
    }

    @ProcessorParam(id = 3, name = "Release", min = 10.0, max = 1000.0, defaultValue = 100.0, unit = "ms")
    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double releaseMs) {
        if (releaseMs < 0) throw new IllegalArgumentException("releaseMs must be >= 0: " + releaseMs);
        this.releaseMs = releaseMs;
        recalculateCoefficients();
    }

    @ProcessorParam(id = 4, name = "Knee", min = 0.0, max = 24.0, defaultValue = 6.0, unit = "dB")
    public double getKneeDb() { return kneeDb; }
    public void setKneeDb(double kneeDb) {
        if (kneeDb < 0) throw new IllegalArgumentException("kneeDb must be >= 0: " + kneeDb);
        this.kneeDb = kneeDb;
    }

    @ProcessorParam(id = 5, name = "Makeup Gain", min = 0.0, max = 30.0, defaultValue = 0.0, unit = "dB")
    public double getMakeupGainDb() { return makeupGainDb; }
    public void setMakeupGainDb(double makeupGainDb) { this.makeupGainDb = makeupGainDb; }

    public DetectionMode getDetectionMode() { return detectionMode; }
    public void setDetectionMode(DetectionMode mode) {
        this.detectionMode = java.util.Objects.requireNonNull(mode);
    }

    @Override
    public void reset() {
        envelopeDb = -120.0;
        currentGainReductionDb = 0.0;
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    private void recalculateCoefficients() {
        attackCoeff = DspUtils.envelopeCoefficient(attackMs, sampleRate);
        releaseCoeff = DspUtils.envelopeCoefficient(releaseMs, sampleRate);
    }
}
