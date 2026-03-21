package com.benesquivelmusic.daw.core.audio;

import java.util.Objects;

/**
 * Immutable description of an audio format used throughout the DAW.
 *
 * @param sampleRate  sample rate in Hz (e.g., 44100, 48000, 96000)
 * @param channels    number of audio channels (1 = mono, 2 = stereo)
 * @param bitDepth    bits per sample (e.g., 16, 24, 32)
 * @param bufferSize  buffer size in sample frames
 */
public record AudioFormat(double sampleRate, int channels, int bitDepth, int bufferSize) {

    /** Standard CD quality: 44.1 kHz, stereo, 16-bit, 512-frame buffer. */
    public static final AudioFormat CD_QUALITY = new AudioFormat(44_100.0, 2, 16, 512);

    /** Professional studio quality: 96 kHz, stereo, 24-bit, 256-frame buffer. */
    public static final AudioFormat STUDIO_QUALITY = new AudioFormat(96_000.0, 2, 24, 256);

    public AudioFormat {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
    }
}
