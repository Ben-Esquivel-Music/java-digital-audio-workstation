package com.benesquivelmusic.daw.core.dsp.mastering;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Random;

/**
 * Real-time dithered bit-depth reduction stage for the mastering chain.
 *
 * <p>Quantizes a floating-point mix bus to a target integer bit depth with
 * a configurable dither type and noise-shaping curve. The audio remains in
 * floating-point format on output, but is quantized to the chosen target
 * bit depth so that downstream integer encoding (16-bit CD, 24-bit stream)
 * is mathematically equivalent to writing the integer samples directly.</p>
 *
 * <p>This is the industry-standard final stage of a mastering chain — placed
 * after the brick-wall limiter — and is intentionally <em>terminal</em>: the
 * {@link com.benesquivelmusic.daw.core.mastering.MasteringChain} forbids
 * inserting any non-terminal stage after it.</p>
 *
 * <h2>Supported dither types</h2>
 * <ul>
 *   <li>{@link DitherType#NONE} — no dither (truncation; produces audible
 *       harmonic distortion on quiet signals)</li>
 *   <li>{@link DitherType#RPDF} — Rectangular PDF (one uniform sample,
 *       still has signal-dependent noise modulation)</li>
 *   <li>{@link DitherType#TPDF} — Triangular PDF (sum of two uniform samples,
 *       eliminates signal-dependent noise modulation — the AES recommended
 *       default)</li>
 *   <li>{@link DitherType#NOISE_SHAPED} — TPDF dither with a noise-shaping
 *       feedback filter (see {@link NoiseShape})</li>
 * </ul>
 *
 * <h2>Noise-shaping curves</h2>
 * Noise shaping uses an error-feedback filter to push quantization noise
 * energy out of the most audible 2–6 kHz band into the less-audible
 * sub-Nyquist region. Five curves are provided ({@link NoiseShape}):
 * flat (no shaping), weighted (psychoacoustic 9-zone Fletcher-Munson-derived
 * minimum-audibility curve), and three POW-r-like profiles for material at
 * different sample rates.
 *
 * <p>This is a pure-Java, allocation-free, real-time-safe implementation.</p>
 */
public final class DitherProcessor implements AudioProcessor {

    /** Dither probability density function / type. */
    public enum DitherType {
        /** No dither (pure truncation/round). */
        NONE,
        /** Rectangular PDF — single uniform random in [-0.5, +0.5] LSB. */
        RPDF,
        /** Triangular PDF — sum of two uniform randoms in [-0.5, +0.5] LSB. */
        TPDF,
        /** TPDF dither plus an error-feedback noise-shaping filter. */
        NOISE_SHAPED
    }

    /**
     * Noise-shaping curves. Coefficients are applied to a feedback chain of
     * past quantization errors; positive feedback at low orders pushes noise
     * energy upward in frequency (away from the ear's most sensitive band).
     */
    public enum NoiseShape {
        /** Flat — no shaping (purely TPDF). Equivalent to {@link DitherType#TPDF}. */
        FLAT(new double[] {}),
        /**
         * Psychoacoustic-weighted curve — derived from the inverse of the
         * Fletcher–Munson minimum-audibility contour. Best for 44.1/48 kHz
         * 16-bit material. ~14 dB perceived noise reduction in the 2–6 kHz
         * range at the cost of higher noise above 16 kHz.
         */
        WEIGHTED(new double[] {2.033, -2.165, 1.959, -1.590, 0.6149}),
        /** POW-r-like #1 — gentle high-frequency shape, suited to classical / acoustic. */
        POWR_1(new double[] {1.623, -0.982, 0.109}),
        /** POW-r-like #2 — moderate shaping, suited to pop / jazz. */
        POWR_2(new double[] {1.534, -0.620, 0.066, 0.077, -0.085}),
        /** POW-r-like #3 — aggressive shaping, suited to dense, full-bandwidth masters. */
        POWR_3(new double[] {2.412, -3.370, 3.937, -4.174, 3.353, -2.205, 1.281, -0.569, 0.0847});

        private final double[] coefficients;

        NoiseShape(double[] coefficients) {
            this.coefficients = coefficients;
        }

        /** Returns the feedback coefficients for this shape (defensive copy). */
        public double[] coefficients() {
            return coefficients.clone();
        }

        /** Returns the filter order (number of feedback taps). */
        public int order() {
            return coefficients.length;
        }
    }

    private final int channels;
    private final Random random;
    private int targetBitDepth;
    private DitherType type;
    private NoiseShape shape;

    /** Per-channel ring buffers of past quantization errors for noise shaping. */
    private double[][] errorHistory;
    /** Per-channel ring buffer write index. */
    private int[] errorIndex;

    /**
     * Creates a dither processor with TPDF dither and a flat (unshaped) curve.
     *
     * @param channels       number of audio channels
     * @param targetBitDepth the target bit depth (e.g., 16, 20, or 24)
     */
    public DitherProcessor(int channels, int targetBitDepth) {
        this(channels, targetBitDepth, DitherType.TPDF, NoiseShape.FLAT, new Random());
    }

    /**
     * Creates a dither processor with a fully specified configuration.
     *
     * @param channels       number of audio channels
     * @param targetBitDepth the target bit depth (e.g., 16, 20, or 24)
     * @param type           the dither type
     * @param shape          the noise-shaping curve (ignored unless type is
     *                       {@link DitherType#NOISE_SHAPED})
     */
    public DitherProcessor(int channels, int targetBitDepth, DitherType type, NoiseShape shape) {
        this(channels, targetBitDepth, type, shape, new Random());
    }

    /**
     * Creates a dither processor with a fixed seed for reproducible output.
     *
     * @param channels       number of audio channels
     * @param targetBitDepth the target bit depth
     * @param type           the dither type
     * @param shape          the noise-shaping curve
     * @param seed           the random seed
     */
    public DitherProcessor(int channels, int targetBitDepth,
                           DitherType type, NoiseShape shape, long seed) {
        this(channels, targetBitDepth, type, shape, new Random(seed));
    }

    /**
     * Package-private back-compat ctor — TPDF, flat shape, fixed seed.
     */
    DitherProcessor(int channels, int targetBitDepth, long seed) {
        this(channels, targetBitDepth, DitherType.TPDF, NoiseShape.FLAT, new Random(seed));
    }

    private DitherProcessor(int channels, int targetBitDepth,
                            DitherType type, NoiseShape shape, Random random) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (targetBitDepth < 2 || targetBitDepth > 32) {
            throw new IllegalArgumentException("targetBitDepth must be in [2, 32]: " + targetBitDepth);
        }
        this.channels = channels;
        this.targetBitDepth = targetBitDepth;
        this.type = (type == null) ? DitherType.TPDF : type;
        this.shape = (shape == null) ? NoiseShape.FLAT : shape;
        this.random = random;
        allocateErrorBuffers();
    }

    private void allocateErrorBuffers() {
        int order = Math.max(1, shape.order());
        this.errorHistory = new double[channels][order];
        this.errorIndex = new int[channels];
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double maxVal = (1L << (targetBitDepth - 1)) - 1;
        double invMaxVal = 1.0 / maxVal;
        double minVal = -maxVal - 1.0;

        DitherType t = this.type;
        NoiseShape sh = this.shape;
        double[] coeffs = sh.coefficients;
        int order = coeffs.length;
        boolean shapingActive = t == DitherType.NOISE_SHAPED && order > 0;

        for (int ch = 0; ch < activeCh; ch++) {
            double[] history = errorHistory[ch];
            int idx = errorIndex[ch];

            for (int i = 0; i < numFrames; i++) {
                double sample = inputBuffer[ch][i];
                double scaled = sample * maxVal;

                // Subtract error-feedback noise shaping (feedback is in LSBs).
                // Standard error-feedback topology: x'[n] = x[n] - Σ h_k · e[n-k].
                // history[(idx-1) mod order] is the most recent error.
                if (shapingActive) {
                    double feedback = 0.0;
                    for (int k = 0; k < order; k++) {
                        int hIdx = Math.floorMod(idx - 1 - k, order);
                        feedback += coeffs[k] * history[hIdx];
                    }
                    scaled -= feedback;
                }

                // Generate dither noise (in LSBs).
                double noise = switch (t) {
                    case NONE        -> 0.0;
                    case RPDF        -> random.nextDouble() - 0.5;
                    case TPDF, NOISE_SHAPED ->
                            (random.nextDouble() - 0.5) + (random.nextDouble() - 0.5);
                };

                double quantized = Math.round(scaled + noise);
                if (quantized > maxVal) quantized = maxVal;
                if (quantized < minVal) quantized = minVal;

                if (shapingActive) {
                    // Round-off error (in LSBs) including dither: y[n] - (x'[n] + d[n]).
                    // This is the high-frequency-shaped error that gets fed back.
                    history[idx] = quantized - (scaled + noise);
                    idx = (idx + 1) % order;
                }

                outputBuffer[ch][i] = (float) (quantized * invMaxVal);
            }
            errorIndex[ch] = idx;
        }
    }

    /** Returns the target bit depth. */
    public int getTargetBitDepth() {
        return targetBitDepth;
    }

    /**
     * Sets the target bit depth.
     *
     * @param targetBitDepth the target bit depth (e.g., 16, 20, or 24)
     */
    public void setTargetBitDepth(int targetBitDepth) {
        if (targetBitDepth < 2 || targetBitDepth > 32) {
            throw new IllegalArgumentException("targetBitDepth must be in [2, 32]: " + targetBitDepth);
        }
        this.targetBitDepth = targetBitDepth;
    }

    /** Returns the active dither type. */
    public DitherType getType() {
        return type;
    }

    /** Sets the dither type (TPDF/RPDF/NOISE_SHAPED/NONE). */
    public void setType(DitherType type) {
        this.type = (type == null) ? DitherType.TPDF : type;
    }

    /** Returns the active noise-shaping curve. */
    public NoiseShape getShape() {
        return shape;
    }

    /**
     * Sets the noise-shaping curve. Re-allocates the error-history buffer
     * to match the new filter order; do not call from the audio thread.
     */
    public void setShape(NoiseShape shape) {
        NoiseShape ns = (shape == null) ? NoiseShape.FLAT : shape;
        if (ns != this.shape) {
            this.shape = ns;
            allocateErrorBuffers();
        }
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            java.util.Arrays.fill(errorHistory[ch], 0.0);
            errorIndex[ch] = 0;
        }
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }
}
