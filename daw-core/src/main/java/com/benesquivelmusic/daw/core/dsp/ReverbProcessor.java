package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Algorithmic reverb processor using a Schroeder–Moorer topology.
 *
 * <p>Implements a classic feedback-comb/allpass reverb with configurable
 * room size, decay time, damping, and wet/dry mix. The design uses four
 * parallel comb filters feeding into two cascaded allpass filters, which
 * produces a dense, smooth reverb tail suitable for a wide range of
 * sources.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable room size (scales comb delay lengths)</li>
 *   <li>Decay control (feedback amount in comb filters)</li>
 *   <li>High-frequency damping for natural-sounding reverb tails</li>
 *   <li>Wet/dry mix control</li>
 *   <li>Mono-in/stereo-out or stereo operation</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@InsertEffect(type = "REVERB", displayName = "Reverb")
public final class ReverbProcessor implements AudioProcessor {

    private static final int NUM_COMBS = 4;
    private static final int NUM_ALLPASSES = 2;

    // Comb filter delay lengths in samples at 44100 Hz (prime-ish for diffusion)
    private static final int[] COMB_DELAYS = {1116, 1188, 1277, 1356};

    // Allpass delay lengths in samples at 44100 Hz
    private static final int[] ALLPASS_DELAYS = {556, 441};

    private static final double ALLPASS_FEEDBACK = 0.5;

    private final int channels;
    private final double sampleRate;
    private double roomSize;
    private double decay;
    private double damping;
    private double mix;

    // Per-channel comb filters
    private final float[][][] combBuffers;
    private final int[][] combPositions;
    private final int[][] combLengths;
    private final float[][] combFilterStore; // damping state

    // Per-channel allpass filters
    private final float[][][] allpassBuffers;
    private final int[][] allpassPositions;
    private final int[][] allpassLengths;

    /**
     * Creates a reverb processor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public ReverbProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.roomSize = 0.5;
        this.decay = 0.5;
        this.damping = 0.5;
        this.mix = 0.3;

        // Allocate comb filters
        combBuffers = new float[channels][NUM_COMBS][];
        combPositions = new int[channels][NUM_COMBS];
        combLengths = new int[channels][NUM_COMBS];
        combFilterStore = new float[channels][NUM_COMBS];

        // Allocate allpass filters
        allpassBuffers = new float[channels][NUM_ALLPASSES][];
        allpassPositions = new int[channels][NUM_ALLPASSES];
        allpassLengths = new int[channels][NUM_ALLPASSES];

        initializeDelayLines();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double feedbackGain = decay * 0.9 + 0.1; // Map [0,1] to [0.1, 1.0]
        double dampingCoeff = damping;

        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < activeCh; ch++) {
                float input = inputBuffer[ch][frame];
                float wet = 0.0f;

                // Process parallel comb filters
                for (int c = 0; c < NUM_COMBS; c++) {
                    float[] buffer = combBuffers[ch][c];
                    int pos = combPositions[ch][c];
                    int len = combLengths[ch][c];

                    float delayed = buffer[pos];

                    // Apply damping (one-pole lowpass on feedback)
                    combFilterStore[ch][c] = (float) (delayed * (1.0 - dampingCoeff)
                            + combFilterStore[ch][c] * dampingCoeff);

                    buffer[pos] = (float) (input + combFilterStore[ch][c] * feedbackGain);
                    combPositions[ch][c] = (pos + 1) % len;

                    wet += delayed;
                }

                // Average the comb outputs
                wet /= NUM_COMBS;

                // Process cascaded allpass filters
                for (int a = 0; a < NUM_ALLPASSES; a++) {
                    float[] buffer = allpassBuffers[ch][a];
                    int pos = allpassPositions[ch][a];
                    int len = allpassLengths[ch][a];

                    float delayed = buffer[pos];
                    float temp = -wet + delayed;
                    buffer[pos] = (float) (wet + delayed * ALLPASS_FEEDBACK);
                    allpassPositions[ch][a] = (pos + 1) % len;

                    wet = temp;
                }

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (input * (1.0 - mix) + wet * mix);
            }
        }
    }

    // --- Parameter accessors ---

    @ProcessorParam(id = 0, name = "Room Size", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getRoomSize() { return roomSize; }

    public void setRoomSize(double roomSize) {
        if (roomSize < 0 || roomSize > 1.0) {
            throw new IllegalArgumentException("roomSize must be in [0, 1]: " + roomSize);
        }
        this.roomSize = roomSize;
        initializeDelayLines();
    }

    @ProcessorParam(id = 1, name = "Decay", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getDecay() { return decay; }

    public void setDecay(double decay) {
        if (decay < 0 || decay > 1.0) {
            throw new IllegalArgumentException("decay must be in [0, 1]: " + decay);
        }
        this.decay = decay;
    }

    @ProcessorParam(id = 2, name = "Damping", min = 0.0, max = 1.0, defaultValue = 0.3)
    public double getDamping() { return damping; }

    public void setDamping(double damping) {
        if (damping < 0 || damping > 1.0) {
            throw new IllegalArgumentException("damping must be in [0, 1]: " + damping);
        }
        this.damping = damping;
    }

    @ProcessorParam(id = 3, name = "Mix", min = 0.0, max = 1.0, defaultValue = 0.3)
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
            for (int c = 0; c < NUM_COMBS; c++) {
                Arrays.fill(combBuffers[ch][c], 0.0f);
                combPositions[ch][c] = 0;
                combFilterStore[ch][c] = 0.0f;
            }
            for (int a = 0; a < NUM_ALLPASSES; a++) {
                Arrays.fill(allpassBuffers[ch][a], 0.0f);
                allpassPositions[ch][a] = 0;
            }
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    /**
     * Initializes delay line buffers scaled to the current sample rate and room size.
     */
    private void initializeDelayLines() {
        double scaleRatio = sampleRate / 44100.0;
        double sizeScale = 0.5 + roomSize; // [0.5, 1.5]

        for (int ch = 0; ch < channels; ch++) {
            for (int c = 0; c < NUM_COMBS; c++) {
                // Add a small stereo spread for channels > 0
                int spread = ch * 23;
                int length = (int) ((COMB_DELAYS[c] + spread) * scaleRatio * sizeScale);
                length = Math.max(length, 1);
                combLengths[ch][c] = length;
                combBuffers[ch][c] = new float[length];
                combPositions[ch][c] = 0;
                combFilterStore[ch][c] = 0.0f;
            }
            for (int a = 0; a < NUM_ALLPASSES; a++) {
                int spread = ch * 11;
                int length = (int) ((ALLPASS_DELAYS[a] + spread) * scaleRatio * sizeScale);
                length = Math.max(length, 1);
                allpassLengths[ch][a] = length;
                allpassBuffers[ch][a] = new float[length];
                allpassPositions[ch][a] = 0;
            }
        }
    }
}
