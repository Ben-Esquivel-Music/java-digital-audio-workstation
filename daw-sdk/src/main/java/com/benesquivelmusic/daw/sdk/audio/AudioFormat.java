package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Immutable description of a PCM audio format used by {@link AudioBackend}.
 *
 * <p>This is the SDK-level counterpart to {@code javax.sound.sampled.AudioFormat}
 * and the engine's core {@code AudioFormat}. It is intentionally minimal —
 * sample rate, channel count, and bit depth — because buffer size is negotiated
 * separately by {@link AudioBackend#open(DeviceId, AudioFormat, int)}.</p>
 *
 * @param sampleRate sample rate in Hz (must be positive)
 * @param channels   number of audio channels; 1 = mono, 2 = stereo (must be positive)
 * @param bitDepth   bits per sample — typically 16, 24, or 32 (must be positive)
 */
public record AudioFormat(double sampleRate, int channels, int bitDepth) {

    /** Standard CD-quality format: 44.1&nbsp;kHz, stereo, 16-bit. */
    public static final AudioFormat CD_QUALITY = new AudioFormat(44_100.0, 2, 16);

    /** Studio-quality format: 48&nbsp;kHz, stereo, 24-bit. */
    public static final AudioFormat STUDIO_QUALITY_48K = new AudioFormat(48_000.0, 2, 24);

    /** High-resolution format: 96&nbsp;kHz, stereo, 24-bit. */
    public static final AudioFormat STUDIO_QUALITY_96K = new AudioFormat(96_000.0, 2, 24);

    public AudioFormat {
        if (!(sampleRate > 0)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
        }
    }

    /**
     * Returns the number of bytes needed to represent one frame (one sample per channel).
     *
     * @return {@code channels * bitDepth / 8} (rounded up)
     */
    public int bytesPerFrame() {
        return channels * ((bitDepth + 7) / 8);
    }

    /**
     * Returns {@code true} if this format has exactly the same sample rate,
     * channel count, and bit depth as {@code other}.
     *
     * @param other other format; may be null
     * @return true if compatible
     */
    public boolean isCompatibleWith(AudioFormat other) {
        return other != null
                && Double.compare(sampleRate, other.sampleRate) == 0
                && channels == other.channels
                && bitDepth == other.bitDepth;
    }

    /**
     * Returns a canonical string representation such as {@code "48000Hz/2ch/24bit"}.
     *
     * @return canonical string
     */
    public String canonical() {
        return ((long) sampleRate) + "Hz/" + channels + "ch/" + bitDepth + "bit";
    }

    @Override
    public String toString() {
        return Objects.toString(canonical());
    }
}
