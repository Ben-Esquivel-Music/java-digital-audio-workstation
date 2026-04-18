package com.benesquivelmusic.daw.core.analysis;

/**
 * Real-time transient detector optimized for adaptive block-switching decisions.
 *
 * <p>When a transient is detected, the audio engine can switch to shorter
 * processing blocks to improve temporal resolution — reducing pre-echo and
 * smearing artifacts in time-frequency processing.</p>
 *
 * <p>Uses a dual-domain detection approach combining temporal energy ratio
 * (short/long window energy ratio) with spectral flux, as recommended by
 * AES research on transient detection for audio coding. The temporal energy
 * ratio captures sudden increases in signal energy, while spectral flux
 * detects rapid changes in spectral content. Both indicators are combined
 * to produce a single binary decision per audio block.</p>
 *
 * <p>Unlike {@link OnsetDetector}, which processes an entire audio buffer
 * offline to locate onset positions, this detector operates on individual
 * audio blocks in real time with no look-ahead — making it suitable for
 * block-by-block processing decisions in the audio engine.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 *
 * @see OnsetDetector
 * @see FftUtils
 */
public final class TransientDetector {

    /**
     * Result of transient detection for a single audio block.
     *
     * @param transientDetected {@code true} if a transient was detected
     *                          (engine should switch to short block)
     * @param temporalEnergyRatio ratio of short-term to long-term energy
     * @param spectralFlux positive spectral flux for the block
     */
    public record Result(boolean transientDetected, double temporalEnergyRatio,
                         double spectralFlux) {}

    /** Default sensitivity threshold (typical range: 1.5–4.0). */
    private static final double DEFAULT_SENSITIVITY = 3.0;

    /** Exponential decay factor for long-term energy tracking. */
    private static final double DEFAULT_LONG_TERM_DECAY = 0.99;

    /** Weight of the temporal energy ratio in the combined decision. */
    private static final double TEMPORAL_WEIGHT = 0.5;

    /** Weight of spectral flux in the combined decision. */
    private static final double SPECTRAL_WEIGHT = 0.5;

    /**
     * Noise floor for energy/flux ratios. Values below this are treated
     * as silence — prevents division-by-near-zero from producing spurious
     * infinite ratios.
     */
    private static final double NOISE_FLOOR = 1e-10;

    private final int blockSize;
    private final double sensitivityThreshold;
    private final double longTermDecay;
    private final double[] window;

    // Spectral flux state
    private final double[] prevMagnitudes;
    private final double[] real;
    private final double[] imag;

    // Temporal energy state
    private double longTermEnergy;
    private double prevSpectralFlux;
    private boolean initialized;

    /**
     * Creates a transient detector with full configuration.
     *
     * @param blockSize            audio block size (must be a power of two)
     * @param sensitivityThreshold multiplier for the adaptive threshold;
     *                             higher values are less sensitive (typical: 1.5–4.0)
     * @param longTermDecay        exponential decay factor for long-term energy
     *                             tracking (typical: 0.95–0.999)
     * @throws IllegalArgumentException if blockSize is not a positive power of two,
     *                                  sensitivityThreshold is not positive, or
     *                                  longTermDecay is not in (0, 1)
     */
    public TransientDetector(int blockSize, double sensitivityThreshold,
                             double longTermDecay) {
        if (blockSize <= 0 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    "blockSize must be a positive power of two: " + blockSize);
        }
        if (sensitivityThreshold <= 0) {
            throw new IllegalArgumentException(
                    "sensitivityThreshold must be positive: " + sensitivityThreshold);
        }
        if (longTermDecay <= 0.0 || longTermDecay >= 1.0) {
            throw new IllegalArgumentException(
                    "longTermDecay must be in (0, 1): " + longTermDecay);
        }
        this.blockSize = blockSize;
        this.sensitivityThreshold = sensitivityThreshold;
        this.longTermDecay = longTermDecay;
        this.window = FftUtils.createHannWindow(blockSize);
        this.prevMagnitudes = new double[blockSize / 2];
        this.real = new double[blockSize];
        this.imag = new double[blockSize];
        this.longTermEnergy = 0.0;
        this.prevSpectralFlux = 0.0;
        this.initialized = false;
    }

    /**
     * Creates a transient detector with default long-term decay (0.99).
     *
     * @param blockSize            audio block size (must be a power of two)
     * @param sensitivityThreshold multiplier for the adaptive threshold
     */
    public TransientDetector(int blockSize, double sensitivityThreshold) {
        this(blockSize, sensitivityThreshold, DEFAULT_LONG_TERM_DECAY);
    }

    /**
     * Creates a transient detector with default sensitivity (3.0) and decay (0.99).
     *
     * @param blockSize audio block size (must be a power of two)
     */
    public TransientDetector(int blockSize) {
        this(blockSize, DEFAULT_SENSITIVITY, DEFAULT_LONG_TERM_DECAY);
    }

    /**
     * Analyzes a single audio block for transients.
     *
     * <p>The block is analyzed using two complementary methods:</p>
     * <ol>
     *   <li><strong>Temporal energy ratio:</strong> computes the ratio of the
     *       current block's RMS energy to a long-term running average. A sudden
     *       increase indicates a transient attack.</li>
     *   <li><strong>Spectral flux:</strong> computes the positive spectral flux
     *       (sum of magnitude increases across FFT bins) between the current and
     *       previous block. A large flux indicates rapid spectral change.</li>
     * </ol>
     *
     * <p>The two metrics are combined using a weighted sum and compared to the
     * sensitivity threshold to produce a binary transient/no-transient decision.</p>
     *
     * @param block mono audio samples; length must equal the configured block size
     * @return detection result containing the binary decision and both metrics
     * @throws IllegalArgumentException if the block length does not match the block size
     */
    public Result detect(float[] block) {
        if (block.length != blockSize) {
            throw new IllegalArgumentException(
                    "block length must equal blockSize (" + blockSize + "): " + block.length);
        }

        double shortTermEnergy = computeBlockEnergy(block);
        double spectralFlux = computeSpectralFlux(block);

        if (!initialized) {
            // First block: initialize state, cannot detect transient yet
            longTermEnergy = shortTermEnergy;
            prevSpectralFlux = spectralFlux;
            initialized = true;
            return new Result(false, 1.0, spectralFlux);
        }

        // Temporal energy ratio — uses a noise floor to avoid spurious
        // infinite ratios from numerical near-zero values, but recognizes
        // genuine silence-to-signal transitions as transients.
        double energyRatio;
        if (longTermEnergy > NOISE_FLOOR) {
            energyRatio = shortTermEnergy / longTermEnergy;
        } else if (shortTermEnergy > NOISE_FLOOR) {
            // Genuine transition from silence to signal
            energyRatio = shortTermEnergy / NOISE_FLOOR;
        } else {
            energyRatio = 1.0;
        }

        // Spectral flux ratio — same noise-floor logic
        double fluxRatio;
        if (prevSpectralFlux > NOISE_FLOOR) {
            fluxRatio = spectralFlux / prevSpectralFlux;
        } else if (spectralFlux > NOISE_FLOOR) {
            fluxRatio = spectralFlux / NOISE_FLOOR;
        } else {
            fluxRatio = 1.0;
        }

        // Combined decision score
        double combinedScore = TEMPORAL_WEIGHT * energyRatio
                + SPECTRAL_WEIGHT * fluxRatio;
        boolean transientDetected = combinedScore > sensitivityThreshold;

        // Update long-term energy using exponential moving average
        longTermEnergy = longTermDecay * longTermEnergy
                + (1.0 - longTermDecay) * shortTermEnergy;

        // Update previous spectral flux using exponential moving average
        prevSpectralFlux = longTermDecay * prevSpectralFlux
                + (1.0 - longTermDecay) * spectralFlux;

        return new Result(transientDetected, energyRatio, spectralFlux);
    }

    /**
     * Resets the detector state, clearing all history.
     *
     * <p>Call this when starting analysis of a new audio stream or after a
     * discontinuity in the input signal.</p>
     */
    public void reset() {
        longTermEnergy = 0.0;
        prevSpectralFlux = 0.0;
        initialized = false;
        java.util.Arrays.fill(prevMagnitudes, 0.0);
    }

    /** Returns the configured block size. */
    public int getBlockSize() {
        return blockSize;
    }

    /** Returns the sensitivity threshold. */
    public double getSensitivityThreshold() {
        return sensitivityThreshold;
    }

    /** Returns the long-term energy decay factor. */
    public double getLongTermDecay() {
        return longTermDecay;
    }

    // ----------------------------------------------------------------
    // Internal DSP methods
    // ----------------------------------------------------------------

    /**
     * Computes the RMS energy of a block.
     */
    private double computeBlockEnergy(float[] block) {
        double sum = 0.0;
        for (float sample : block) {
            sum += (double) sample * sample;
        }
        return sum / block.length;
    }

    /**
     * Computes the positive spectral flux for a block using the shared FFT
     * from {@link FftUtils}.
     */
    private double computeSpectralFlux(float[] block) {
        int length = Math.min(blockSize, block.length);

        // Apply Hann window and load into real buffer
        for (int i = 0; i < length; i++) {
            real[i] = block[i] * window[i];
            imag[i] = 0.0;
        }
        for (int i = length; i < blockSize; i++) {
            real[i] = 0.0;
            imag[i] = 0.0;
        }

        // In-place FFT using shared utility
        FftUtils.fft(real, imag);

        // Compute positive spectral flux
        int binCount = blockSize / 2;
        double flux = 0.0;
        for (int i = 0; i < binCount; i++) {
            double magnitude = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
            double diff = magnitude - prevMagnitudes[i];
            if (diff > 0) {
                flux += diff;
            }
            prevMagnitudes[i] = magnitude;
        }

        return flux;
    }
}
