package com.benesquivelmusic.daw.sdk.export;

import java.util.Objects;

/**
 * Configuration for a single audio export operation.
 *
 * <p>Specifies the output format, target sample rate, target bit depth,
 * dithering algorithm, and optional metadata. The export pipeline uses
 * this to determine what conversions (sample rate, bit depth) and
 * processing (dithering) to apply before writing the output file.</p>
 *
 * @param format     the output audio format
 * @param sampleRate the target sample rate in Hz (e.g., 44100, 48000, 96000)
 * @param bitDepth   the target bit depth (e.g., 16, 24, 32)
 * @param ditherType the dithering algorithm to apply during bit-depth reduction
 * @param metadata   metadata to embed in the output file
 * @param quality    lossy encoder quality (0.0–1.0); ignored for lossless formats
 */
public record AudioExportConfig(
        AudioExportFormat format,
        int sampleRate,
        int bitDepth,
        DitherType ditherType,
        AudioMetadata metadata,
        double quality
) {

    public AudioExportConfig {
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(ditherType, "ditherType must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
        if (quality < 0.0 || quality > 1.0) {
            throw new IllegalArgumentException("quality must be in [0.0, 1.0]: " + quality);
        }
    }

    /**
     * Convenience constructor with default quality (0.8) and empty metadata.
     */
    public AudioExportConfig(AudioExportFormat format, int sampleRate, int bitDepth,
                             DitherType ditherType) {
        this(format, sampleRate, bitDepth, ditherType, AudioMetadata.EMPTY, 0.8);
    }
}
