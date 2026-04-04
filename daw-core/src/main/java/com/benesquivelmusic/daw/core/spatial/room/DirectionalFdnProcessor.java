package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.core.spatial.ambisonics.SphericalHarmonics;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Directional Feedback Delay Network reverb processor that produces
 * first-order Ambisonic (FOA) output.
 *
 * <p>Each of the N internal FDN delay lines is assigned a spherical direction
 * (azimuth, elevation). The output of each delay line is encoded to
 * first-order Ambisonics (W, X, Y, Z) using real spherical harmonic
 * coefficients (ACN/SN3D), then summed across all delay lines. This
 * produces spatially distributed late reverberation — reflections arrive
 * from many directions rather than collapsing to a single point.</p>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li><b>Feedback matrix:</b> Householder (H = 2/N · 1 − I), consistent
 *       with the existing {@code FdnRoomSimulator} architecture.</li>
 *   <li><b>Damping:</b> per-delay-line one-pole lowpass filter that attenuates
 *       high frequencies on each feedback loop, modeling air absorption and
 *       surface reflection losses.</li>
 *   <li><b>Directional encoding:</b> each delay-line output is encoded to FOA
 *       via {@link SphericalHarmonics#encode}, then summed to produce the
 *       four Ambisonic output channels.</li>
 * </ul>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>Room size</b> — scales the base delay lengths (larger rooms produce
 *       longer delay taps and lower echo density).</li>
 *   <li><b>Decay</b> — target RT60 in seconds; controls the per-sample feedback
 *       gain of each delay line.</li>
 *   <li><b>Damping</b> — one-pole lowpass coefficient in [0, 1]; higher values
 *       produce more high-frequency absorption per feedback loop.</li>
 *   <li><b>Directional spread</b> — in [0, 1]; 0 collapses all directions to
 *       the front, 1 distributes them uniformly around the sphere.</li>
 * </ul>
 *
 * <p>Thread safety: this processor is <b>not</b> thread-safe. The
 * {@link #process} method must be called from a single thread (typically
 * the real-time audio thread). Parameter setters may be called from another
 * thread; they write to {@code volatile} fields that are read on the next
 * {@code process} call.</p>
 *
 * @see SphericalHarmonics
 * @see com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicEncoder
 * @see FdnRoomSimulator
 */
public final class DirectionalFdnProcessor implements AudioProcessor {

    /** Number of delay lines in the FDN. */
    static final int NUM_DELAY_LINES = 8;

    /** Number of output channels: first-order Ambisonics (W, Y, Z, X). */
    private static final int FOA_CHANNELS = 4;

    /** Speed of sound in air at 20 °C (m/s). */
    private static final double SPEED_OF_SOUND = 343.0;

    /** Prime-based delay-length multipliers for mutually coprime tap spacing. */
    private static final double[] DELAY_PRIMES = {
            1.000, 1.069, 1.151, 1.237, 1.327, 1.423, 1.523, 1.619
    };

    // ---- Configuration (volatile for safe cross-thread parameter updates) ----
    private final int sampleRate;
    private volatile double roomSize;
    private volatile double decaySeconds;
    private volatile double damping;
    private volatile double directionalSpread;

    // ---- FDN state ----
    private float[][] delayBuffers;
    private int[] delayLengths;
    private int[] writePositions;

    /** Per-delay-line feedback gain (derived from decay and delay length). */
    private float[] feedbackGains;

    /** Per-delay-line one-pole lowpass filter state. */
    private float[] dampingState;

    /** Per-delay-line FOA encoding coefficients [delayLine][4]. */
    private double[][] ambiCoefficients;

    /** Azimuth per delay line (radians). */
    private double[] azimuths;

    /** Elevation per delay line (radians). */
    private double[] elevations;

    /**
     * Creates a directional FDN reverb processor.
     *
     * @param sampleRate        audio sample rate in Hz (must be positive)
     * @param roomSize          room size in metres (must be positive)
     * @param decaySeconds      RT60 decay time in seconds (must be positive)
     * @param damping           high-frequency damping in [0, 1]
     * @param directionalSpread directional spread in [0, 1]
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public DirectionalFdnProcessor(int sampleRate, double roomSize,
                                   double decaySeconds, double damping,
                                   double directionalSpread) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (roomSize <= 0) {
            throw new IllegalArgumentException("roomSize must be positive: " + roomSize);
        }
        if (decaySeconds <= 0) {
            throw new IllegalArgumentException("decaySeconds must be positive: " + decaySeconds);
        }
        if (damping < 0 || damping > 1) {
            throw new IllegalArgumentException("damping must be in [0, 1]: " + damping);
        }
        if (directionalSpread < 0 || directionalSpread > 1) {
            throw new IllegalArgumentException("directionalSpread must be in [0, 1]: " + directionalSpread);
        }

        this.sampleRate = sampleRate;
        this.roomSize = roomSize;
        this.decaySeconds = decaySeconds;
        this.damping = damping;
        this.directionalSpread = directionalSpread;

        this.delayBuffers = new float[NUM_DELAY_LINES][];
        this.delayLengths = new int[NUM_DELAY_LINES];
        this.writePositions = new int[NUM_DELAY_LINES];
        this.feedbackGains = new float[NUM_DELAY_LINES];
        this.dampingState = new float[NUM_DELAY_LINES];
        this.azimuths = new double[NUM_DELAY_LINES];
        this.elevations = new double[NUM_DELAY_LINES];
        this.ambiCoefficients = new double[NUM_DELAY_LINES][];

        buildDelayLines();
        buildDirections();
    }

    // ---- Parameter getters/setters ----

    /** Returns the sample rate in Hz. */
    public int getSampleRate() { return sampleRate; }

    /** Returns the current room size in metres. */
    public double getRoomSize() { return roomSize; }

    /** Returns the current decay time (RT60) in seconds. */
    public double getDecaySeconds() { return decaySeconds; }

    /** Returns the current damping coefficient. */
    public double getDamping() { return damping; }

    /** Returns the current directional spread. */
    public double getDirectionalSpread() { return directionalSpread; }

    /**
     * Sets the room size and rebuilds delay lines.
     *
     * @param roomSize the new room size in metres (must be positive)
     */
    public void setRoomSize(double roomSize) {
        if (roomSize <= 0) {
            throw new IllegalArgumentException("roomSize must be positive: " + roomSize);
        }
        this.roomSize = roomSize;
        buildDelayLines();
    }

    /**
     * Sets the decay time and recomputes feedback gains.
     *
     * @param decaySeconds the new RT60 in seconds (must be positive)
     */
    public void setDecaySeconds(double decaySeconds) {
        if (decaySeconds <= 0) {
            throw new IllegalArgumentException("decaySeconds must be positive: " + decaySeconds);
        }
        this.decaySeconds = decaySeconds;
        computeFeedbackGains();
    }

    /**
     * Sets the high-frequency damping coefficient.
     *
     * @param damping the new damping in [0, 1]
     */
    public void setDamping(double damping) {
        if (damping < 0 || damping > 1) {
            throw new IllegalArgumentException("damping must be in [0, 1]: " + damping);
        }
        this.damping = damping;
    }

    /**
     * Sets the directional spread and recomputes per-line directions.
     *
     * @param directionalSpread the new spread in [0, 1]
     */
    public void setDirectionalSpread(double directionalSpread) {
        if (directionalSpread < 0 || directionalSpread > 1) {
            throw new IllegalArgumentException("directionalSpread must be in [0, 1]: " + directionalSpread);
        }
        this.directionalSpread = directionalSpread;
        buildDirections();
    }

    /**
     * Returns the azimuth (in radians) assigned to a specific delay line.
     *
     * @param delayLineIndex the delay line index in [0, {@link #NUM_DELAY_LINES})
     * @return the azimuth in radians
     */
    public double getDelayLineAzimuth(int delayLineIndex) {
        return azimuths[delayLineIndex];
    }

    /**
     * Returns the elevation (in radians) assigned to a specific delay line.
     *
     * @param delayLineIndex the delay line index in [0, {@link #NUM_DELAY_LINES})
     * @return the elevation in radians
     */
    public double getDelayLineElevation(int delayLineIndex) {
        return elevations[delayLineIndex];
    }

    // ---- AudioProcessor implementation ----

    /**
     * Processes mono input through the directional FDN and produces
     * first-order Ambisonic (4-channel) output.
     *
     * @param inputBuffer  mono input buffer ({@code [1][numFrames]})
     * @param outputBuffer FOA output buffer ({@code [4][numFrames]}: W, Y, Z, X)
     * @param numFrames    the number of sample frames to process
     */
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int outChannels = Math.min(outputBuffer.length, FOA_CHANNELS);

        // Zero the output channels
        for (int ch = 0; ch < outChannels; ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
        }

        // Read volatile parameters once per process call
        double currentDamping = this.damping;
        float dampCoeff = (float) currentDamping;

        // Temporary array for Householder feedback computation
        float[] delayOutputs = new float[NUM_DELAY_LINES];

        for (int n = 0; n < numFrames; n++) {
            // Downmix input to mono
            float monoIn = 0.0f;
            for (int inCh = 0; inCh < inputBuffer.length; inCh++) {
                monoIn += inputBuffer[inCh][n];
            }
            if (inputBuffer.length > 1) {
                monoIn /= inputBuffer.length;
            }

            // Read from all delay lines
            for (int i = 0; i < NUM_DELAY_LINES; i++) {
                int readPos = (writePositions[i] - delayLengths[i] + delayBuffers[i].length)
                        % delayBuffers[i].length;
                delayOutputs[i] = delayBuffers[i][readPos];
            }

            // Householder feedback: H*y = (2/N) * sum(y) * ones - y
            float sum = 0.0f;
            for (int i = 0; i < NUM_DELAY_LINES; i++) {
                sum += delayOutputs[i];
            }
            float householderEntry = (2.0f / NUM_DELAY_LINES) * sum;

            // Write new samples into delay lines (input + feedback)
            for (int i = 0; i < NUM_DELAY_LINES; i++) {
                float feedback = householderEntry - delayOutputs[i];

                // Apply per-line feedback gain (decay control)
                feedback *= feedbackGains[i];

                // Apply one-pole lowpass damping filter
                dampingState[i] = dampingState[i] + dampCoeff * (feedback - dampingState[i]);
                float dampedFeedback = dampingState[i];

                // Write: mono input + damped feedback
                delayBuffers[i][writePositions[i]] = monoIn + dampedFeedback;
                writePositions[i] = (writePositions[i] + 1) % delayBuffers[i].length;
            }

            // Encode each delay line output to FOA and accumulate
            for (int i = 0; i < NUM_DELAY_LINES; i++) {
                double[] coeffs = ambiCoefficients[i];
                float output = delayOutputs[i];
                for (int ch = 0; ch < outChannels; ch++) {
                    outputBuffer[ch][n] += (float) (output * coeffs[ch]);
                }
            }
        }

        // Normalize output by number of delay lines to prevent clipping
        float normFactor = 1.0f / NUM_DELAY_LINES;
        for (int ch = 0; ch < outChannels; ch++) {
            for (int n = 0; n < numFrames; n++) {
                outputBuffer[ch][n] *= normFactor;
            }
        }
    }

    @Override
    public void reset() {
        for (int i = 0; i < NUM_DELAY_LINES; i++) {
            Arrays.fill(delayBuffers[i], 0.0f);
            writePositions[i] = 0;
            dampingState[i] = 0.0f;
        }
    }

    @Override
    public int getInputChannelCount() {
        return 1;
    }

    @Override
    public int getOutputChannelCount() {
        return FOA_CHANNELS;
    }

    // ---- Internal construction methods ----

    /**
     * Builds delay lines with lengths derived from room size and prime-based
     * multipliers. Delay lengths are mutually coprime to ensure maximal
     * echo density and avoid coloration.
     */
    private void buildDelayLines() {
        double baseDelaySeconds = roomSize / SPEED_OF_SOUND;

        for (int i = 0; i < NUM_DELAY_LINES; i++) {
            int length = Math.max(1, (int) (baseDelaySeconds * DELAY_PRIMES[i] * sampleRate));
            delayLengths[i] = length;
            delayBuffers[i] = new float[length];
            writePositions[i] = 0;
        }

        makeDelaysMutuallyPrime();
        computeFeedbackGains();
    }

    /**
     * Computes per-delay-line feedback gains from the target RT60 decay time
     * and each line's delay length.
     *
     * <p>The gain for each delay line is: g = 10^(−3 · T_d / RT60) where
     * T_d is the delay time in seconds. This ensures that after RT60 seconds,
     * the energy has decayed by 60 dB.</p>
     */
    private void computeFeedbackGains() {
        double rt60 = this.decaySeconds;
        for (int i = 0; i < NUM_DELAY_LINES; i++) {
            double delayTimeSeconds = (double) delayLengths[i] / sampleRate;
            feedbackGains[i] = (float) Math.pow(10.0, -3.0 * delayTimeSeconds / rt60);
        }
    }

    /**
     * Distributes spherical directions across delay lines based on the
     * directional spread parameter.
     *
     * <p>With spread = 1.0, directions are uniformly distributed around the
     * horizontal plane and include elevated positions. With spread = 0.0,
     * all directions collapse to the front (azimuth = 0, elevation = 0).</p>
     */
    private void buildDirections() {
        double spread = this.directionalSpread;

        for (int i = 0; i < NUM_DELAY_LINES; i++) {
            // Base azimuth: uniformly distributed around the full circle
            double baseAzimuth = (2.0 * Math.PI * i) / NUM_DELAY_LINES;

            // Base elevation: alternate slightly above and below horizontal
            // for 3D distribution (±15° at full spread)
            double baseElevation = (i % 2 == 0 ? 1.0 : -1.0) * Math.PI / 12.0;

            azimuths[i] = baseAzimuth * spread;
            elevations[i] = baseElevation * spread;

            ambiCoefficients[i] = SphericalHarmonics.encode(azimuths[i], elevations[i], 1);
        }
    }

    /**
     * Adjusts delay lengths to be mutually coprime, preventing
     * resonant build-up at common multiples.
     */
    private void makeDelaysMutuallyPrime() {
        for (int i = 1; i < delayLengths.length; i++) {
            while (!isMutuallyPrime(i)) {
                delayLengths[i]++;
                delayBuffers[i] = new float[delayLengths[i]];
            }
        }
    }

    private boolean isMutuallyPrime(int idx) {
        for (int j = 0; j < delayLengths.length; j++) {
            if (j == idx) continue;
            if (gcd(delayLengths[j], delayLengths[idx]) != 1) return false;
        }
        return true;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
