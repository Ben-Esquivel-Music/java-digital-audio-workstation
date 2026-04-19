package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Immutable block of interleaved floating-point audio samples flowing
 * through an {@link AudioBackend}.
 *
 * <p>Samples are stored channel-interleaved. For stereo input, element
 * {@code samples[2*i]} is the left sample of frame {@code i} and
 * {@code samples[2*i + 1]} is the right sample of frame {@code i}.</p>
 *
 * <p>Each sample is a normalized float in {@code [-1.0f, 1.0f]}. Values
 * outside that range will clip when written to a hardware sink.</p>
 *
 * @param sampleRate sample rate of the block in Hz (must be positive)
 * @param channels   number of interleaved channels (must be positive)
 * @param frames     number of sample frames in this block (must be non-negative)
 * @param samples    interleaved sample data of length {@code channels * frames}
 */
public record AudioBlock(double sampleRate, int channels, int frames, float[] samples) {

    public AudioBlock {
        if (!(sampleRate > 0)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames < 0) {
            throw new IllegalArgumentException("frames must be non-negative: " + frames);
        }
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.length != channels * frames) {
            throw new IllegalArgumentException(
                    "samples.length (" + samples.length + ") != channels*frames ("
                            + (channels * frames) + ")");
        }
    }

    /**
     * Creates a silent block of the given shape.
     *
     * @param sampleRate sample rate in Hz
     * @param channels   number of channels
     * @param frames     number of frames
     * @return a new block filled with zeros
     */
    public static AudioBlock silence(double sampleRate, int channels, int frames) {
        return new AudioBlock(sampleRate, channels, frames, new float[channels * frames]);
    }

    /**
     * Returns the total number of interleaved samples ({@code channels * frames}).
     *
     * @return interleaved sample count
     */
    public int totalSamples() {
        return samples.length;
    }
}
