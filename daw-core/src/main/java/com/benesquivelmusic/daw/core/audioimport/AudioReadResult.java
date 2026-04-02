package com.benesquivelmusic.daw.core.audioimport;

import java.util.Objects;

/**
 * Result of reading an audio file in any supported format.
 *
 * <p>This is the common return type shared by all audio file readers
 * ({@link WavFileReader}, {@link FlacFileReader}, {@link AiffFileReader},
 * {@link Mp3FileReader}, {@link OggVorbisFileReader}).</p>
 *
 * @param audioData  decoded audio samples as {@code [channel][sample]} in [-1.0, 1.0]
 * @param sampleRate the sample rate in Hz
 * @param channels   the number of channels
 * @param bitDepth   the original bit depth (or 0 if unknown for lossy formats)
 */
public record AudioReadResult(float[][] audioData, int sampleRate, int channels, int bitDepth) {

    public AudioReadResult {
        Objects.requireNonNull(audioData, "audioData must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (bitDepth < 0) {
            throw new IllegalArgumentException("bitDepth must not be negative: " + bitDepth);
        }
        if (audioData.length != channels) {
            throw new IllegalArgumentException(
                    "audioData length must match channels: audioData.length=" + audioData.length
                            + ", channels=" + channels);
        }
        int expectedFrames = -1;
        for (int channel = 0; channel < audioData.length; channel++) {
            float[] channelData = Objects.requireNonNull(
                    audioData[channel],
                    "audioData[" + channel + "] must not be null");
            if (expectedFrames == -1) {
                expectedFrames = channelData.length;
            } else if (channelData.length != expectedFrames) {
                throw new IllegalArgumentException(
                        "All channel arrays must have the same frame count: audioData[" + channel
                                + "].length=" + channelData.length + ", expected=" + expectedFrames);
            }
        }
    }

    /** Returns the total number of sample frames. */
    public int numFrames() {
        return (channels > 0 && audioData.length > 0) ? audioData[0].length : 0;
    }

    /** Returns the duration in seconds. */
    public double durationSeconds() {
        return (double) numFrames() / sampleRate;
    }
}
