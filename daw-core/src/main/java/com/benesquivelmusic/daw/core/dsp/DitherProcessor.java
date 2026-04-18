package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Random;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Real-time TPDF dithering processor for the mastering chain.
 *
 * <p>Applies Triangular Probability Density Function dithering and
 * quantization to simulate bit-depth reduction. The audio remains
 * in floating-point format but is quantized to the target bit depth
 * with dither noise added to eliminate quantization distortion.</p>
 *
 * <p>This is the industry-standard dithering algorithm for mastering.
 * Typically placed as the final stage in the mastering chain.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class DitherProcessor implements AudioProcessor {

    private final int channels;
    private final Random random;
    private int targetBitDepth;

    /**
     * Creates a dither processor targeting the specified bit depth.
     *
     * @param channels       number of audio channels
     * @param targetBitDepth the target bit depth (e.g., 16 or 24)
     */
    public DitherProcessor(int channels, int targetBitDepth) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (targetBitDepth < 2 || targetBitDepth > 32) {
            throw new IllegalArgumentException("targetBitDepth must be in [2, 32]: " + targetBitDepth);
        }
        this.channels = channels;
        this.targetBitDepth = targetBitDepth;
        this.random = new Random();
    }

    /**
     * Creates a dither processor with a fixed seed for reproducible results.
     *
     * @param channels       number of audio channels
     * @param targetBitDepth the target bit depth
     * @param seed           the random seed
     */
    DitherProcessor(int channels, int targetBitDepth, long seed) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (targetBitDepth < 2 || targetBitDepth > 32) {
            throw new IllegalArgumentException("targetBitDepth must be in [2, 32]: " + targetBitDepth);
        }
        this.channels = channels;
        this.targetBitDepth = targetBitDepth;
        this.random = new Random(seed);
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double maxVal = (1L << (targetBitDepth - 1)) - 1;
        double invMaxVal = 1.0 / maxVal;

        for (int ch = 0; ch < activeCh; ch++) {
            for (int i = 0; i < numFrames; i++) {
                double sample = inputBuffer[ch][i];

                // Scale to target integer range
                double scaled = sample * maxVal;

                // Add TPDF noise: sum of two uniform randoms in [-0.5, 0.5]
                double tpdfNoise = random.nextDouble() - 0.5 + (random.nextDouble() - 0.5);

                // Quantize (round to nearest integer)
                double quantized = Math.round(scaled + tpdfNoise);

                // Clamp to valid range
                quantized = Math.max(-maxVal - 1, Math.min(maxVal, quantized));

                // Convert back to floating-point
                outputBuffer[ch][i] = (float) (quantized * invMaxVal);
            }
        }
    }

    /** Returns the target bit depth. */
    public int getTargetBitDepth() {
        return targetBitDepth;
    }

    /**
     * Sets the target bit depth.
     *
     * @param targetBitDepth the target bit depth (e.g., 16 or 24)
     */
    public void setTargetBitDepth(int targetBitDepth) {
        if (targetBitDepth < 2 || targetBitDepth > 32) {
            throw new IllegalArgumentException("targetBitDepth must be in [2, 32]: " + targetBitDepth);
        }
        this.targetBitDepth = targetBitDepth;
    }

    @Override
    public void reset() {
        // stateless — no internal state to reset
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
