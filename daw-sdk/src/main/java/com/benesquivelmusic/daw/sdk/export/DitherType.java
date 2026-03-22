package com.benesquivelmusic.daw.sdk.export;

/**
 * Dithering algorithms available during bit-depth reduction.
 *
 * <p>Dithering adds low-level noise before quantization to linearize the
 * quantization error, eliminating audible distortion artifacts when
 * reducing bit depth (e.g., 24→16 bit).</p>
 */
public enum DitherType {

    /** No dithering applied — simple truncation. */
    NONE,

    /**
     * Triangular Probability Density Function dithering.
     *
     * <p>Adds two uniform random noise sources summed to produce a
     * triangular distribution at ±1 LSB. This is the standard dithering
     * algorithm for professional mastering — it completely eliminates
     * quantization distortion with a flat noise floor.</p>
     */
    TPDF,

    /**
     * Noise-shaped dithering.
     *
     * <p>Applies an error-feedback filter that shapes the dither noise
     * spectrum to concentrate energy above 10 kHz where human hearing
     * is less sensitive, resulting in a lower perceived noise floor
     * compared to flat TPDF dithering.</p>
     */
    NOISE_SHAPED
}
