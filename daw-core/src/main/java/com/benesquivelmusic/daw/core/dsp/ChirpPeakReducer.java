package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Peak reduction processor that reduces crest factor (peak-to-average ratio)
 * by spreading signal peaks across time using ultra-short chirp modulation.
 *
 * <p>Unlike traditional limiting which clips or compresses peaks, chirp
 * spreading redistributes peak energy without audible artifacts — enabling
 * louder masters without distortion. This processor is designed to operate
 * <em>before</em> the limiter in the mastering chain for transparent loudness
 * maximization.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Follow the signal envelope to detect peaks above a configurable
 *       threshold.</li>
 *   <li>When a peak is detected, extract the peak component (the portion
 *       of the signal above the threshold) and convolve it with an
 *       ultra-short linear chirp kernel (1–5 ms).</li>
 *   <li>The chirp convolution spreads the peak energy across time, reducing
 *       the instantaneous peak while preserving total energy.</li>
 *   <li>The spread peak replaces the original peak component and is mixed
 *       back with the sub-threshold signal at a configurable dry/wet ratio.</li>
 * </ol>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>Threshold</b> — Level in dB above which peaks are spread
 *       (range: −60 to 0 dB, default −6 dB).</li>
 *   <li><b>Chirp duration</b> — Duration of the chirp kernel in milliseconds
 *       (range: 1–5 ms, default 2 ms).</li>
 *   <li><b>Chirp bandwidth</b> — Frequency sweep range of the chirp in Hz
 *       (range: 1000–20000 Hz, default 8000 Hz).</li>
 *   <li><b>Mix</b> — Dry/wet balance (0.0 = bypass, 1.0 = fully processed,
 *       default 1.0).</li>
 * </ul>
 *
 * <h2>AES Research References</h2>
 * <ul>
 *   <li>Audio Peak Reduction Using Ultra-Short Chirps (2022)</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class ChirpPeakReducer implements AudioProcessor {

    /** Minimum allowed threshold in dB. */
    public static final double MIN_THRESHOLD_DB = -60.0;

    /** Maximum allowed threshold in dB. */
    public static final double MAX_THRESHOLD_DB = 0.0;

    /** Minimum allowed chirp duration in milliseconds. */
    public static final double MIN_CHIRP_DURATION_MS = 1.0;

    /** Maximum allowed chirp duration in milliseconds. */
    public static final double MAX_CHIRP_DURATION_MS = 5.0;

    /** Minimum allowed chirp bandwidth in Hz. */
    public static final double MIN_CHIRP_BANDWIDTH_HZ = 1000.0;

    /** Maximum allowed chirp bandwidth in Hz. */
    public static final double MAX_CHIRP_BANDWIDTH_HZ = 20000.0;

    private final int channels;
    private final double sampleRate;

    private double thresholdDb;
    private double chirpDurationMs;
    private double chirpBandwidthHz;
    private double mix;

    // Precomputed chirp kernel (normalized to unit energy)
    private volatile double[] chirpKernel;
    private int chirpKernelLength;

    // Per-channel convolution overlap-add buffers
    private volatile float[][] overlapBuffers;

    // Per-channel envelope state for attack/release peak detection
    private volatile double[] envelopeState;

    // Envelope follower coefficients (depend only on sampleRate, which is final)
    private final double envelopeAttackCoeff;
    private final double envelopeReleaseCoeff;

    /**
     * Creates a chirp peak reducer with default settings.
     *
     * <p>Defaults: −6 dB threshold, 2 ms chirp duration, 8000 Hz bandwidth,
     * 100% wet mix.</p>
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public ChirpPeakReducer(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.thresholdDb = -6.0;
        this.chirpDurationMs = 2.0;
        this.chirpBandwidthHz = 8000.0;
        this.mix = 1.0;

        // Precompute envelope follower coefficients: ~0.1 ms attack, ~5 ms release
        this.envelopeAttackCoeff = Math.exp(-1.0 / (sampleRate * 0.0001));
        this.envelopeReleaseCoeff = Math.exp(-1.0 / (sampleRate * 0.005));

        rebuildKernel();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));

        if (mix == 0.0) {
            for (int ch = 0; ch < activeCh; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        double thresholdLinear = Math.pow(10.0, thresholdDb / 20.0);

        // Snapshot volatile references
        double[] kernel = chirpKernel;
        int kernelLen = kernel.length;
        float[][] overlap = overlapBuffers;
        double[] envelope = envelopeState;

        double attackCoeff = envelopeAttackCoeff;
        double releaseCoeff = envelopeReleaseCoeff;

        // Ensure overlap buffers are large enough for the current block size.
        // If the host passes a numFrames larger than what was allocated, grow
        // the buffers dynamically so we never index out of bounds.
        int requiredOverlapLen = numFrames + kernelLen;
        if (overlap[0].length < requiredOverlapLen) {
            float[][] grown = new float[channels][];
            for (int ch = 0; ch < channels; ch++) {
                grown[ch] = new float[requiredOverlapLen];
                System.arraycopy(overlap[ch], 0, grown[ch], 0, overlap[ch].length);
            }
            overlap = grown;
            overlapBuffers = grown;
        }

        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < activeCh; ch++) {
                float sample = inputBuffer[ch][frame];
                float absSample = Math.abs(sample);

                // Envelope follower: fast attack, slow release
                double env = envelope[ch];
                if (absSample > env) {
                    env = attackCoeff * env + (1.0 - attackCoeff) * absSample;
                } else {
                    env = releaseCoeff * env + (1.0 - releaseCoeff) * absSample;
                }
                envelope[ch] = env;

                // Detect peaks using the larger of instantaneous level and
                // envelope.  Instantaneous detection catches lone transients;
                // the envelope's slow release keeps the gate open for nearby
                // sub-threshold samples after a transient.
                double detectionLevel = Math.max(absSample, env);

                if (detectionLevel > thresholdLinear) {
                    // Extract the peak component above the threshold
                    float sign = (sample >= 0) ? 1.0f : -1.0f;
                    float peakComponent = (absSample - (float) thresholdLinear) * sign;
                    float baseComponent = (float) thresholdLinear * sign;

                    // Convolve peak component with chirp kernel using overlap-add.
                    // Each peak sample contributes kernel[k] * peakComponent at offset k.
                    for (int k = 0; k < kernelLen; k++) {
                        overlap[ch][frame + k] += (float) (peakComponent * kernel[k]);
                    }

                    // Output = base component + spread peak from overlap buffer
                    float processed = baseComponent + overlap[ch][frame];
                    outputBuffer[ch][frame] = (float) (sample * (1.0 - mix) + processed * mix);
                } else {
                    // Below threshold — add any remaining overlap contribution
                    float processed = sample + overlap[ch][frame];
                    outputBuffer[ch][frame] = (float) (sample * (1.0 - mix) + processed * mix);
                }

                // Clear the consumed overlap position
                overlap[ch][frame] = 0.0f;
            }
        }

        // Shift unconsumed overlap data forward for the next process() call.
        // Data at indices [numFrames .. numFrames+kernelLen-1] wraps to [0 .. kernelLen-1].
        for (int ch = 0; ch < activeCh; ch++) {
            int overlapLen = overlap[ch].length;
            int remaining = overlapLen - numFrames;
            if (remaining > 0) {
                System.arraycopy(overlap[ch], numFrames, overlap[ch], 0, remaining);
                Arrays.fill(overlap[ch], remaining, overlapLen, 0.0f);
            } else {
                Arrays.fill(overlap[ch], 0.0f);
            }
        }
    }

    // --- Parameter accessors ---

    /**
     * Returns the threshold level in dB.
     *
     * @return threshold in dB
     */
    public double getThresholdDb() {
        return thresholdDb;
    }

    /**
     * Sets the threshold level above which peaks are spread.
     *
     * @param thresholdDb threshold in dB, range [−60, 0]
     */
    public void setThresholdDb(double thresholdDb) {
        if (thresholdDb < MIN_THRESHOLD_DB || thresholdDb > MAX_THRESHOLD_DB) {
            throw new IllegalArgumentException(
                    "thresholdDb must be in [" + MIN_THRESHOLD_DB + ", "
                            + MAX_THRESHOLD_DB + "]: " + thresholdDb);
        }
        this.thresholdDb = thresholdDb;
    }

    /**
     * Returns the chirp duration in milliseconds.
     *
     * @return chirp duration in ms
     */
    public double getChirpDurationMs() {
        return chirpDurationMs;
    }

    /**
     * Sets the chirp duration.
     *
     * @param chirpDurationMs duration in ms, range [1, 5]
     */
    public void setChirpDurationMs(double chirpDurationMs) {
        if (chirpDurationMs < MIN_CHIRP_DURATION_MS || chirpDurationMs > MAX_CHIRP_DURATION_MS) {
            throw new IllegalArgumentException(
                    "chirpDurationMs must be in [" + MIN_CHIRP_DURATION_MS + ", "
                            + MAX_CHIRP_DURATION_MS + "]: " + chirpDurationMs);
        }
        this.chirpDurationMs = chirpDurationMs;
        rebuildKernel();
    }

    /**
     * Returns the chirp bandwidth in Hz.
     *
     * @return chirp bandwidth in Hz
     */
    public double getChirpBandwidthHz() {
        return chirpBandwidthHz;
    }

    /**
     * Sets the chirp bandwidth (frequency sweep range).
     *
     * @param chirpBandwidthHz bandwidth in Hz, range [1000, 20000]
     */
    public void setChirpBandwidthHz(double chirpBandwidthHz) {
        if (chirpBandwidthHz < MIN_CHIRP_BANDWIDTH_HZ || chirpBandwidthHz > MAX_CHIRP_BANDWIDTH_HZ) {
            throw new IllegalArgumentException(
                    "chirpBandwidthHz must be in [" + MIN_CHIRP_BANDWIDTH_HZ + ", "
                            + MAX_CHIRP_BANDWIDTH_HZ + "]: " + chirpBandwidthHz);
        }
        this.chirpBandwidthHz = chirpBandwidthHz;
        rebuildKernel();
    }

    /**
     * Returns the dry/wet mix.
     *
     * @return mix in [0.0, 1.0]
     */
    public double getMix() {
        return mix;
    }

    /**
     * Sets the dry/wet mix.
     *
     * @param mix 0.0 = bypass (dry), 1.0 = fully processed (wet)
     */
    public void setMix(double mix) {
        if (mix < 0.0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0.0, 1.0]: " + mix);
        }
        this.mix = mix;
    }

    /**
     * Returns the latency introduced by the chirp kernel, in samples.
     *
     * @return latency in samples
     */
    public int getLatencySamples() {
        return chirpKernelLength;
    }

    @Override
    public void reset() {
        for (float[] buf : overlapBuffers) {
            Arrays.fill(buf, 0.0f);
        }
        Arrays.fill(envelopeState, 0.0);
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }

    /**
     * Rebuilds the chirp kernel and internal buffers based on current
     * duration and bandwidth settings.
     *
     * <p>The chirp kernel is a linear frequency sweep from a start frequency
     * to start + bandwidth, windowed with a raised-cosine (Hann) window,
     * and normalized to unit energy so that the total signal energy is
     * preserved during convolution.</p>
     */
    private void rebuildKernel() {
        int kernelLen = Math.max(2, (int) (chirpDurationMs * 0.001 * sampleRate));
        chirpKernelLength = kernelLen;

        // Generate a linear chirp: frequency sweeps from f0 to f0 + bandwidth
        // over the kernel duration. The start frequency is set at half the
        // bandwidth to center the sweep in a useful range.
        double f0 = chirpBandwidthHz * 0.5;
        double f1 = f0 + chirpBandwidthHz;
        double durationSec = kernelLen / sampleRate;

        double[] kernel = new double[kernelLen];
        for (int n = 0; n < kernelLen; n++) {
            double t = n / sampleRate;
            // Instantaneous frequency = f0 + (f1 - f0) * t / duration
            // Phase = 2π * (f0 * t + 0.5 * (f1 - f0) * t² / duration)
            double phase = 2.0 * Math.PI * (f0 * t + 0.5 * (f1 - f0) * t * t / durationSec);
            double chirpSample = Math.cos(phase);

            // Hann window to taper the edges and avoid spectral leakage
            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (kernelLen - 1)));
            kernel[n] = chirpSample * window;
        }

        // Normalize to unit energy: sum(kernel²) = 1
        double energy = 0.0;
        for (double v : kernel) {
            energy += v * v;
        }
        if (energy > 0.0) {
            double scale = 1.0 / Math.sqrt(energy);
            for (int n = 0; n < kernelLen; n++) {
                kernel[n] *= scale;
            }
        }

        this.chirpKernel = kernel;

        // Overlap-add buffer must accommodate the kernel tail beyond the block.
        // Initial size handles typical block sizes; process() grows dynamically
        // if a larger numFrames is encountered at runtime.
        int overlapSize = kernelLen + 8192;
        float[][] newOverlap = new float[channels][overlapSize];
        this.overlapBuffers = newOverlap;
        this.envelopeState = new double[channels];
    }
}
