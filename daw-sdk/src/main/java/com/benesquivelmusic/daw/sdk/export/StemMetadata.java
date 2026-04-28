package com.benesquivelmusic.daw.sdk.export;

import java.util.Objects;

/**
 * Per-stem (or per-master) descriptor inside a {@link BundleMetadata}:
 * the file name written inside the zip, the audio format, channel layout,
 * sample rate, bit depth, and the measured peak / RMS / integrated LUFS.
 *
 * <p>Mastering engineers and supervisors use these per-stem measurements
 * to spot any track that is clipping, abnormally hot, or anomalously
 * quiet without opening every file.</p>
 *
 * @param fileName        the file name inside the zip (with extension)
 * @param format          the audio format (e.g., "WAV", "FLAC")
 * @param channels        the channel count (1 = mono, 2 = stereo)
 * @param sampleRate      the sample rate in Hz
 * @param bitDepth        the bit depth (e.g., 16, 24, 32)
 * @param peakDbfs        the absolute peak level in dBFS
 *                        ({@link Double#NEGATIVE_INFINITY} for digital silence)
 * @param rmsDbfs         the RMS level in dBFS
 *                        ({@link Double#NEGATIVE_INFINITY} for digital silence)
 * @param integratedLufs  the gated integrated loudness in LUFS
 *                        ({@link Double#NEGATIVE_INFINITY} for digital silence
 *                        or signals below the {@code -70 LUFS} absolute gate)
 */
public record StemMetadata(
        String fileName,
        String format,
        int channels,
        int sampleRate,
        int bitDepth,
        double peakDbfs,
        double rmsDbfs,
        double integratedLufs
) {

    public StemMetadata {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
    }
}
