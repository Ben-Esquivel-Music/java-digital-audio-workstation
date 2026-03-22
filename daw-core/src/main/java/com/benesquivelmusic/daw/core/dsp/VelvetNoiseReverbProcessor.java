package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Random;

/**
 * Velvet-noise reverb processor.
 *
 * <p>Implements a reverb based on sparse velvet-noise impulse responses, where
 * pulse values are restricted to {−1, 0, +1}. Convolution with such a sequence
 * requires only additions and subtractions at the non-zero pulse positions,
 * yielding reverb quality perceptually comparable to full convolution reverb
 * at a fraction of the computational cost.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable pulse density (pulses per second)</li>
 *   <li>Exponential decay envelope for natural-sounding reverb tails</li>
 *   <li>Early/late reflection balance control</li>
 *   <li>High-frequency damping via one-pole lowpass on the wet signal</li>
 *   <li>Wet/dry mix control</li>
 *   <li>Segment-based architecture suitable for parallel processing</li>
 *   <li>Stereo-decorrelated velvet-noise sequences per channel</li>
 * </ul>
 *
 * <p>Based on AES research on efficient velvet-noise convolution. Complements
 * the Schroeder–Moorer {@link ReverbProcessor} and the physically modeled
 * {@link SpringReverbProcessor} as a third reverb algorithm.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class VelvetNoiseReverbProcessor implements AudioProcessor {

    private static final double MIN_DECAY_SECONDS = 0.1;
    private static final double MAX_DECAY_SECONDS = 3.0;
    private static final double MIN_DENSITY_PPS = 500.0;
    private static final double MAX_DENSITY_PPS = 4000.0;
    private static final double EARLY_REFLECTION_MS = 50.0;

    private final int channels;
    private final double sampleRate;

    // Parameters
    private double decayTime;
    private double density;
    private double earlyLateMix;
    private double damping;
    private double mix;

    // Sparse velvet-noise impulse response per channel
    private int[][] pulsePositions;   // [channel][pulseIndex]
    private float[][] pulseWeights;   // [channel][pulseIndex] (sign × envelope)
    private int[] pulseCount;         // [channel]
    private int[] earlyPulseCount;    // [channel] — number of pulses in early region

    // Circular input history buffer for sparse convolution
    private float[][] inputHistory;   // [channel][historyLength]
    private int[] writePositions;     // [channel]
    private int historyLength;

    // Per-channel damping filter state (one-pole lowpass)
    private float[] dampingState;     // [channel]

    // Seed for reproducible velvet-noise generation
    private final long seed;

    /**
     * Creates a velvet-noise reverb processor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public VelvetNoiseReverbProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.decayTime = 0.5;
        this.density = 0.5;
        this.earlyLateMix = 0.5;
        this.damping = 0.5;
        this.mix = 0.3;
        this.seed = 42L;

        pulsePositions = new int[channels][];
        pulseWeights = new float[channels][];
        pulseCount = new int[channels];
        earlyPulseCount = new int[channels];
        writePositions = new int[channels];
        dampingState = new float[channels];

        generateVelvetNoiseSequence();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double dampCoeff = damping;
        double earlyGain = 1.0 - earlyLateMix;
        double lateGain = earlyLateMix;

        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < activeCh; ch++) {
                float input = inputBuffer[ch][frame];

                // Write input to circular history buffer
                inputHistory[ch][writePositions[ch]] = input;

                // Sparse convolution: sum contributions at non-zero pulse positions
                float earlyWet = 0.0f;
                float lateWet = 0.0f;
                int earlyCount = earlyPulseCount[ch];
                int totalCount = pulseCount[ch];
                int[] positions = pulsePositions[ch];
                float[] weights = pulseWeights[ch];
                int wp = writePositions[ch];
                int hLen = historyLength;

                // Early reflections
                for (int p = 0; p < earlyCount; p++) {
                    int readPos = (wp - positions[p] + hLen) % hLen;
                    earlyWet += weights[p] * inputHistory[ch][readPos];
                }

                // Late reflections
                for (int p = earlyCount; p < totalCount; p++) {
                    int readPos = (wp - positions[p] + hLen) % hLen;
                    lateWet += weights[p] * inputHistory[ch][readPos];
                }

                // Blend early and late
                float wet = (float) (earlyGain * earlyWet + lateGain * lateWet);

                // Apply damping (one-pole lowpass on wet signal)
                dampingState[ch] = (float) (wet * (1.0 - dampCoeff)
                        + dampingState[ch] * dampCoeff);
                wet = dampingState[ch];

                // Advance write position
                writePositions[ch] = (wp + 1) % hLen;

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (input * (1.0 - mix) + wet * mix);
            }
        }
    }

    // --- Parameter accessors ---

    public double getDecayTime() { return decayTime; }

    public void setDecayTime(double decayTime) {
        if (decayTime < 0 || decayTime > 1.0) {
            throw new IllegalArgumentException("decayTime must be in [0, 1]: " + decayTime);
        }
        this.decayTime = decayTime;
        generateVelvetNoiseSequence();
    }

    public double getDensity() { return density; }

    public void setDensity(double density) {
        if (density < 0 || density > 1.0) {
            throw new IllegalArgumentException("density must be in [0, 1]: " + density);
        }
        this.density = density;
        generateVelvetNoiseSequence();
    }

    public double getEarlyLateMix() { return earlyLateMix; }

    public void setEarlyLateMix(double earlyLateMix) {
        if (earlyLateMix < 0 || earlyLateMix > 1.0) {
            throw new IllegalArgumentException(
                    "earlyLateMix must be in [0, 1]: " + earlyLateMix);
        }
        this.earlyLateMix = earlyLateMix;
    }

    public double getDamping() { return damping; }

    public void setDamping(double damping) {
        if (damping < 0 || damping > 1.0) {
            throw new IllegalArgumentException("damping must be in [0, 1]: " + damping);
        }
        this.damping = damping;
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
            Arrays.fill(inputHistory[ch], 0.0f);
            writePositions[ch] = 0;
            dampingState[ch] = 0.0f;
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    /**
     * Generates the sparse velvet-noise impulse response for all channels.
     *
     * <p>The sequence is generated with configurable density (pulses per second)
     * and an exponential decay envelope. Each pulse is placed at a random
     * position within its grid interval and assigned a sign of +1 or −1.
     * Different channels receive different random sequences for stereo
     * decorrelation.</p>
     */
    private void generateVelvetNoiseSequence() {
        // Map normalized parameters to physical values
        double reverbLengthSeconds = MIN_DECAY_SECONDS
                + decayTime * (MAX_DECAY_SECONDS - MIN_DECAY_SECONDS);
        double pulsesPerSecond = MIN_DENSITY_PPS
                + density * (MAX_DENSITY_PPS - MIN_DENSITY_PPS);

        int totalSamples = (int) (reverbLengthSeconds * sampleRate);
        totalSamples = Math.max(totalSamples, 1);
        historyLength = totalSamples + 1;

        int earlySamples = (int) (EARLY_REFLECTION_MS * 0.001 * sampleRate);

        // Grid interval: average samples between pulses
        double gridInterval = sampleRate / pulsesPerSecond;
        int totalPulses = (int) (reverbLengthSeconds * pulsesPerSecond);
        totalPulses = Math.max(totalPulses, 1);

        // Decay rate: -60dB over reverb length (ln(0.001) ≈ -6.908)
        double decayRate = -6.908 / totalSamples;

        // Re-allocate input history buffers
        inputHistory = new float[channels][historyLength];
        writePositions = new int[channels];

        for (int ch = 0; ch < channels; ch++) {
            Random rng = new Random(seed + ch);

            int[] positions = new int[totalPulses];
            float[] weights = new float[totalPulses];
            int count = 0;
            int earlyCount = 0;

            for (int p = 0; p < totalPulses; p++) {
                // Place pulse at random position within grid interval
                int gridStart = (int) (p * gridInterval);
                int gridEnd = (int) ((p + 1) * gridInterval);
                gridEnd = Math.min(gridEnd, totalSamples);
                if (gridStart >= totalSamples) {
                    break;
                }

                int pos = gridStart + rng.nextInt(Math.max(1, gridEnd - gridStart));
                pos = Math.min(pos, totalSamples - 1);

                // Random sign: +1 or −1
                float sign = rng.nextBoolean() ? 1.0f : -1.0f;

                // Exponential decay envelope
                float envelope = (float) Math.exp(decayRate * pos);

                positions[count] = pos;
                weights[count] = sign * envelope;
                if (pos < earlySamples) {
                    earlyCount = count + 1;
                }
                count++;
            }

            // Trim to actual count
            pulsePositions[ch] = Arrays.copyOf(positions, count);
            pulseWeights[ch] = Arrays.copyOf(weights, count);
            pulseCount[ch] = count;
            earlyPulseCount[ch] = earlyCount;
        }
    }
}
