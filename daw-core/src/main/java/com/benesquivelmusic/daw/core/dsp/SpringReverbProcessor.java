package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Physically modeled spring reverb processor.
 *
 * <p>Models the physical behavior of a helical spring reverb tank, incorporating:
 * <ul>
 *   <li><b>Dispersive delay line</b> — a cascade of allpass filters that simulate
 *       frequency-dependent propagation delay (higher frequencies arrive later),
 *       producing the characteristic spring reverb "chirp"</li>
 *   <li><b>Helix angle</b> — controls the amount of allpass dispersion, mapping
 *       to the physical helix geometry of the spring</li>
 *   <li><b>Frequency-dependent damping</b> — a one-pole lowpass filter in the
 *       feedback path models high-frequency losses along the spring</li>
 *   <li><b>Magnetic bead coupling</b> — bandpass filtering at the input and output
 *       of the delay network models the resonant characteristics of the
 *       electromagnetic driving and pickup transducers</li>
 *   <li><b>Pre-delay</b> — a simple delay line before the reverb network</li>
 * </ul>
 *
 * <p>Based on AES research: "Physical Modeling of a Spring Reverb Tank
 * Incorporating Helix Angle, Damping, and Magnetic Bead Coupling."</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class SpringReverbProcessor implements AudioProcessor {

    private static final int NUM_ALLPASS_STAGES = 8;
    private static final double MAX_PRE_DELAY_MS = 200.0;

    // Allpass delay lengths in samples at 44100 Hz (tuned for spring character)
    private static final int[] ALLPASS_BASE_DELAYS = {142, 107, 379, 277, 503, 353, 619, 457};

    private final int channels;
    private final double sampleRate;

    // Parameters
    private double springTension;
    private double decayTime;
    private double damping;
    private double mix;
    private double preDelayMs;
    private double helixAngle;

    // Per-channel dispersive allpass cascade
    private final float[][][] allpassBuffers;
    private final int[][] allpassPositions;
    private final int[][] allpassLengths;

    // Per-channel feedback delay line (main reverb body)
    private final float[][] feedbackBuffer;
    private final int[] feedbackPositions;
    private int feedbackLength;

    // Per-channel damping filter state (one-pole lowpass)
    private final float[] dampingState;

    // Per-channel pre-delay line
    private final float[][] preDelayBuffer;
    private final int[] preDelayWritePos;
    private int preDelaySamples;
    private final int maxPreDelaySamples;

    // Per-channel coupling bandpass filter (models magnetic bead transducer)
    private final BiquadFilter[] inputCouplingFilters;
    private final BiquadFilter[] outputCouplingFilters;

    /**
     * Creates a spring reverb processor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public SpringReverbProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.springTension = 0.5;
        this.decayTime = 0.5;
        this.damping = 0.5;
        this.mix = 0.3;
        this.preDelayMs = 10.0;
        this.helixAngle = 0.5;

        // Allocate dispersive allpass cascade
        allpassBuffers = new float[channels][NUM_ALLPASS_STAGES][];
        allpassPositions = new int[channels][NUM_ALLPASS_STAGES];
        allpassLengths = new int[channels][NUM_ALLPASS_STAGES];

        // Allocate feedback delay line
        int maxFeedbackLength = (int) (sampleRate * 0.15) + 1; // ~150ms max
        feedbackBuffer = new float[channels][maxFeedbackLength];
        feedbackPositions = new int[channels];
        feedbackLength = maxFeedbackLength;

        // Damping state
        dampingState = new float[channels];

        // Pre-delay
        maxPreDelaySamples = (int) (MAX_PRE_DELAY_MS * 0.001 * sampleRate) + 1;
        preDelayBuffer = new float[channels][maxPreDelaySamples];
        preDelayWritePos = new int[channels];
        preDelaySamples = (int) (preDelayMs * 0.001 * sampleRate);

        // Magnetic bead coupling filters (bandpass centered around spring resonance)
        inputCouplingFilters = new BiquadFilter[channels];
        outputCouplingFilters = new BiquadFilter[channels];
        for (int ch = 0; ch < channels; ch++) {
            inputCouplingFilters[ch] = createCouplingFilter();
            outputCouplingFilters[ch] = createCouplingFilter();
        }

        initializeAllpassDelays();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double feedbackGain = decayTime * 0.85 + 0.05; // Map [0,1] to [0.05, 0.9]
        double dampCoeff = damping;
        double allpassCoeff = computeAllpassCoefficient();

        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < activeCh; ch++) {
                float input = inputBuffer[ch][frame];

                // Apply input coupling filter (magnetic bead driving transducer)
                float coupled = inputCouplingFilters[ch].processSample(input);

                // Pre-delay
                preDelayBuffer[ch][preDelayWritePos[ch]] = coupled;
                int preDelayReadPos = (preDelayWritePos[ch] - preDelaySamples
                        + maxPreDelaySamples) % maxPreDelaySamples;
                float preDelayed = preDelayBuffer[ch][preDelayReadPos];
                preDelayWritePos[ch] = (preDelayWritePos[ch] + 1) % maxPreDelaySamples;

                // Read from feedback delay line
                int fbReadPos = (feedbackPositions[ch] - feedbackLength
                        + feedbackBuffer[ch].length) % feedbackBuffer[ch].length;
                float feedbackSample = feedbackBuffer[ch][fbReadPos];

                // Apply damping (one-pole lowpass in feedback path)
                dampingState[ch] = (float) (feedbackSample * (1.0 - dampCoeff)
                        + dampingState[ch] * dampCoeff);

                // Combine pre-delayed input with damped feedback
                float signal = preDelayed + dampingState[ch] * (float) feedbackGain;

                // Dispersive allpass cascade (Schroeder allpass — unity gain)
                for (int s = 0; s < NUM_ALLPASS_STAGES; s++) {
                    float[] buffer = allpassBuffers[ch][s];
                    int pos = allpassPositions[ch][s];
                    int len = allpassLengths[ch][s];

                    float delayed = buffer[pos];
                    float v = signal + (float) allpassCoeff * delayed;
                    signal = delayed - (float) allpassCoeff * v;
                    buffer[pos] = v;
                    allpassPositions[ch][s] = (pos + 1) % len;
                }

                // Write processed signal to feedback delay line
                feedbackBuffer[ch][feedbackPositions[ch]] = signal;
                feedbackPositions[ch] = (feedbackPositions[ch] + 1)
                        % feedbackBuffer[ch].length;

                // Apply output coupling filter (magnetic bead pickup transducer)
                float wet = outputCouplingFilters[ch].processSample(signal);

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (input * (1.0 - mix) + wet * mix);
            }
        }
    }

    // --- Parameter accessors ---

    public double getSpringTension() { return springTension; }

    public void setSpringTension(double springTension) {
        if (springTension < 0 || springTension > 1.0) {
            throw new IllegalArgumentException(
                    "springTension must be in [0, 1]: " + springTension);
        }
        this.springTension = springTension;
        initializeAllpassDelays();
    }

    public double getDecayTime() { return decayTime; }

    public void setDecayTime(double decayTime) {
        if (decayTime < 0 || decayTime > 1.0) {
            throw new IllegalArgumentException("decayTime must be in [0, 1]: " + decayTime);
        }
        this.decayTime = decayTime;
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

    public double getPreDelayMs() { return preDelayMs; }

    public void setPreDelayMs(double preDelayMs) {
        if (preDelayMs < 0 || preDelayMs > MAX_PRE_DELAY_MS) {
            throw new IllegalArgumentException(
                    "preDelayMs must be in [0, " + MAX_PRE_DELAY_MS + "]: " + preDelayMs);
        }
        this.preDelayMs = preDelayMs;
        this.preDelaySamples = (int) (preDelayMs * 0.001 * sampleRate);
    }

    public double getHelixAngle() { return helixAngle; }

    public void setHelixAngle(double helixAngle) {
        if (helixAngle < 0 || helixAngle > 1.0) {
            throw new IllegalArgumentException("helixAngle must be in [0, 1]: " + helixAngle);
        }
        this.helixAngle = helixAngle;
        initializeAllpassDelays();
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            for (int s = 0; s < NUM_ALLPASS_STAGES; s++) {
                Arrays.fill(allpassBuffers[ch][s], 0.0f);
                allpassPositions[ch][s] = 0;
            }
            Arrays.fill(feedbackBuffer[ch], 0.0f);
            feedbackPositions[ch] = 0;
            dampingState[ch] = 0.0f;
            Arrays.fill(preDelayBuffer[ch], 0.0f);
            preDelayWritePos[ch] = 0;
            inputCouplingFilters[ch].reset();
            outputCouplingFilters[ch].reset();
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    /**
     * Computes the allpass coefficient from spring tension and helix angle.
     * Higher tension and helix angle increase dispersion.
     */
    private double computeAllpassCoefficient() {
        return 0.3 + 0.4 * helixAngle * (0.5 + 0.5 * springTension);
    }

    /**
     * Creates a bandpass coupling filter modeling the magnetic bead transducer.
     * Centered at the spring's natural resonance frequency.
     */
    private BiquadFilter createCouplingFilter() {
        double centerFreq = 800.0 + springTension * 2400.0; // 800–3200 Hz
        double q = 0.7;
        return BiquadFilter.create(BiquadFilter.FilterType.BAND_PASS,
                sampleRate, Math.min(centerFreq, sampleRate / 2.0 - 1.0), q, 0.0);
    }

    /**
     * Initializes allpass delay lengths based on sample rate, spring tension,
     * and helix angle. The helix angle scales the dispersion amount —
     * larger angles produce more frequency spreading.
     */
    private void initializeAllpassDelays() {
        double scaleRatio = sampleRate / 44100.0;
        double tensionScale = 0.6 + 0.8 * springTension; // [0.6, 1.4]
        double helixScale = 0.5 + helixAngle;             // [0.5, 1.5]

        for (int ch = 0; ch < channels; ch++) {
            for (int s = 0; s < NUM_ALLPASS_STAGES; s++) {
                int spread = ch * 13; // small stereo spread
                int length = (int) ((ALLPASS_BASE_DELAYS[s] + spread)
                        * scaleRatio * tensionScale * helixScale);
                length = Math.max(length, 1);
                allpassLengths[ch][s] = length;
                allpassBuffers[ch][s] = new float[length];
                allpassPositions[ch][s] = 0;
            }
        }

        // Update feedback delay length based on tension
        feedbackLength = Math.max(1,
                (int) (sampleRate * 0.08 * tensionScale)); // ~80ms base
        feedbackLength = Math.min(feedbackLength, feedbackBuffer[0].length);
    }
}
