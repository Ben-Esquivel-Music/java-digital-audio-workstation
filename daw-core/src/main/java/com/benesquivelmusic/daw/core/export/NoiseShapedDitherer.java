package com.benesquivelmusic.daw.core.export;

import java.util.Random;

/**
 * Noise-shaped dithering for bit-depth reduction with improved perceptual quality.
 *
 * <p>Applies a first-order error-feedback filter that shapes the dither noise
 * spectrum to concentrate energy at higher frequencies (above ~10 kHz) where
 * human hearing is less sensitive. This produces a lower perceived noise floor
 * than flat TPDF dithering while maintaining the same theoretical noise power.</p>
 *
 * <p>The shaping filter uses the simple first-order feedback topology:
 * {@code output = round(input + dither - coefficient × previousError)},
 * where the coefficient pushes the error energy toward higher frequencies.</p>
 */
final class NoiseShapedDitherer {

    /**
     * First-order noise shaping coefficient.
     * A value around 0.5 pushes roughly half the error energy above Nyquist/2.
     */
    private static final double SHAPING_COEFFICIENT = 0.5;

    private final Random random;
    private double previousError;

    /**
     * Creates a noise-shaped ditherer with a random seed.
     */
    NoiseShapedDitherer() {
        this.random = new Random();
    }

    /**
     * Creates a noise-shaped ditherer with a fixed seed for reproducible results.
     *
     * @param seed the random seed
     */
    NoiseShapedDitherer(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Applies noise-shaped dithering and quantizes a floating-point sample to
     * the specified target bit depth.
     *
     * @param sample         the input sample in [-1.0, 1.0]
     * @param targetBitDepth the target bit depth (e.g., 16 or 24)
     * @return the dithered, quantized sample in the target integer range
     */
    double dither(double sample, int targetBitDepth) {
        double maxVal = (1L << (targetBitDepth - 1)) - 1;

        // Scale to target integer range
        double scaled = sample * maxVal;

        // Generate TPDF noise (same base as TpdfDitherer)
        double noise = (random.nextDouble() - 0.5) + (random.nextDouble() - 0.5);

        // Apply error feedback (noise shaping)
        double shaped = scaled + noise - SHAPING_COEFFICIENT * previousError;

        // Quantize
        double quantized = Math.round(shaped);
        quantized = Math.max(-maxVal - 1, Math.min(maxVal, quantized));

        // Update error feedback
        previousError = quantized - scaled;

        return quantized;
    }

    /**
     * Resets the error feedback state. Call between channels or files.
     */
    void reset() {
        previousError = 0.0;
    }
}
