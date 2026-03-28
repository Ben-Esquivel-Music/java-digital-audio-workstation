package com.benesquivelmusic.daw.core.browser;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable metadata for an audio sample file.
 *
 * <p>Contains the essential information needed to display sample details
 * in the browser panel: file path, duration, sample rate, channel count,
 * bit depth, and file size in bytes.</p>
 *
 * @param filePath   the absolute path to the audio file
 * @param durationSeconds the duration of the audio in seconds
 * @param sampleRate the sample rate in Hz
 * @param channels   the number of audio channels
 * @param bitDepth   the bit depth of the audio
 * @param fileSizeBytes the file size in bytes
 */
public record SampleMetadata(
        Path filePath,
        double durationSeconds,
        int sampleRate,
        int channels,
        int bitDepth,
        long fileSizeBytes) {

    public SampleMetadata {
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative: " + durationSeconds);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must not be negative: " + fileSizeBytes);
        }
    }

    /**
     * Returns a human-readable duration string in the format {@code m:ss.s}.
     *
     * @return formatted duration string
     */
    public String formattedDuration() {
        int totalSeconds = (int) durationSeconds;
        int minutes = totalSeconds / 60;
        double seconds = durationSeconds - (minutes * 60);
        return String.format("%d:%04.1f", minutes, seconds);
    }

    /**
     * Returns a human-readable channel description.
     *
     * @return "Mono", "Stereo", or "{n}ch" for multi-channel
     */
    public String channelDescription() {
        return switch (channels) {
            case 1 -> "Mono";
            case 2 -> "Stereo";
            default -> channels + "ch";
        };
    }

    /**
     * Returns a summary string suitable for tooltip display.
     *
     * @return metadata summary string
     */
    public String toSummaryString() {
        return String.format("%s | %d Hz | %s | %d-bit",
                formattedDuration(), sampleRate, channelDescription(), bitDepth);
    }
}
