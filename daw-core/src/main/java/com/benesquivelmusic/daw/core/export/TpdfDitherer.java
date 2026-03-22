package com.benesquivelmusic.daw.core.export;

import java.util.Random;

/**
 * Triangular Probability Density Function (TPDF) dithering for bit-depth reduction.
 *
 * <p>TPDF dithering adds noise with a triangular probability distribution at
 * ±1 LSB amplitude before quantization. This completely eliminates quantization
 * distortion and replaces it with a constant, signal-independent noise floor.
 * It is the industry-standard dithering algorithm for professional mastering.</p>
 *
 * <p>The triangular distribution is produced by summing two independent uniform
 * random values, each in the range [−0.5 LSB, +0.5 LSB].</p>
 */
final class TpdfDitherer {

    private final Random random;

    /**
     * Creates a TPDF ditherer with a random seed.
     */
    TpdfDitherer() {
        this.random = new Random();
    }

    /**
     * Creates a TPDF ditherer with a fixed seed for reproducible results.
     *
     * @param seed the random seed
     */
    TpdfDitherer(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Applies TPDF dithering and quantizes a floating-point sample to the
     * specified target bit depth.
     *
     * <p>The input sample is expected in the range {@code [-1.0, 1.0]}.
     * The result is a quantized value scaled to the target integer range
     * and returned as a {@code double} suitable for writing to an integer
     * PCM format.</p>
     *
     * @param sample       the input sample in [-1.0, 1.0]
     * @param targetBitDepth the target bit depth (e.g., 16 or 24)
     * @return the dithered, quantized sample as a double in the target integer range
     */
    double dither(double sample, int targetBitDepth) {
        double maxVal = (1L << (targetBitDepth - 1)) - 1;

        // Scale to target integer range
        double scaled = sample * maxVal;

        // Generate TPDF noise: sum of two independent uniform random values in [-0.5, 0.5]
        double noise = (random.nextDouble() - 0.5) + (random.nextDouble() - 0.5);

        // Add dither noise and round to nearest integer
        return Math.max(-maxVal - 1, Math.min(maxVal, Math.round(scaled + noise)));
    }
}
