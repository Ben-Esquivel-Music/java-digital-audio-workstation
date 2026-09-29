package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * The single WAV sample encoder shared by the whole-buffer {@link WavExporter}
 * and the streaming capture writer
 * ({@code com.benesquivelmusic.daw.core.recording.SegmentWriter}, story 323).
 *
 * <p>Both writers must produce bit-identical data chunks for the same frames
 * at the same bit depth when the export does not dither
 * ({@code DitherType.NONE}; capture never dithers) — a sealed recording
 * segment is a plain WAV file and an undithered export of the same audio
 * must not differ by a single LSB. Keeping the
 * quantiser in one place is what makes that a property of the code rather
 * than a coincidence; {@code SegmentWriterTest} pins it by byte equality.</p>
 *
 * <p>Encoding rules (unchanged from the original {@code WavExporter}):</p>
 * <ul>
 *   <li>Samples are clamped to {@code [-1.0, 1.0]} before encoding.</li>
 *   <li>Integer PCM ({@link #FORMAT_PCM}) quantises with
 *       {@code Math.round(sample * (2^(bits-1) - 1))} when no ditherer is
 *       supplied, or with the supplied TPDF / noise-shaped ditherer.</li>
 *   <li>8-bit PCM is unsigned with silence at 128; 16/24/32-bit PCM is
 *       signed little-endian.</li>
 *   <li>32-bit with {@link DitherType#NONE} is IEEE float
 *       ({@link #FORMAT_IEEE_FLOAT}), stored as little-endian
 *       {@code float}.</li>
 * </ul>
 *
 * <p>Thread-safety: stateless apart from the caller-supplied ditherers; may be
 * called from any non-real-time thread (export workers, the
 * {@code capture-flush} thread). Never called on the audio callback.</p>
 */
public final class PcmSampleEncoding {

    /** WAV format code for integer PCM data. */
    public static final short FORMAT_PCM = 1;

    /** WAV format code for IEEE floating-point data. */
    public static final short FORMAT_IEEE_FLOAT = 3;

    private PcmSampleEncoding() {
        // utility class
    }

    /** Returns whether {@code bitDepth} is one of the WAV depths this encoder writes (8, 16, 24, 32). */
    public static boolean isSupportedBitDepth(int bitDepth) {
        return bitDepth == 8 || bitDepth == 16 || bitDepth == 24 || bitDepth == 32;
    }

    /**
     * Returns whether the combination encodes as IEEE float: 32-bit with no
     * dithering. Every other combination is integer PCM.
     */
    public static boolean isIeeeFloat(int bitDepth, DitherType ditherType) {
        return bitDepth == 32 && ditherType == DitherType.NONE;
    }

    /** Returns the WAV {@code fmt} format code for the combination. */
    public static short formatCode(int bitDepth, DitherType ditherType) {
        return isIeeeFloat(bitDepth, ditherType) ? FORMAT_IEEE_FLOAT : FORMAT_PCM;
    }

    /** Returns the container bytes per sample for {@code bitDepth}. */
    public static int bytesPerSample(int bitDepth) {
        if (!isSupportedBitDepth(bitDepth)) {
            throw new IllegalArgumentException("bitDepth must be 8, 16, 24, or 32: " + bitDepth);
        }
        return bitDepth / 8;
    }

    /**
     * Encodes {@code numFrames} interleaved frames of {@code audio} (starting
     * at {@code frameOffset}) into {@code dest} at absolute byte offset
     * {@code destOffset}, without dithering.
     *
     * @param audio       {@code [channel][sample]} in {@code [-1.0, 1.0]}
     * @param frameOffset first frame to encode
     * @param numFrames   number of frames to encode
     * @param channels    number of channels to interleave (the first
     *                    {@code channels} rows of {@code audio})
     * @param bitDepth    8, 16, 24 or 32
     * @param ieeeFloat   {@code true} to store 32-bit IEEE float samples
     * @param dest        little-endian destination buffer
     * @param destOffset  absolute byte offset of the first encoded sample
     * @return the number of bytes written ({@code numFrames * channels * bytesPerSample})
     */
    public static int encodeBlock(float[][] audio, int frameOffset, int numFrames, int channels,
                                  int bitDepth, boolean ieeeFloat, ByteBuffer dest, int destOffset) {
        return encodeBlock(audio, frameOffset, numFrames, channels, bitDepth, ieeeFloat,
                dest, destOffset, null, null);
    }

    /**
     * Encodes {@code numFrames} interleaved frames of {@code audio} (starting
     * at {@code frameOffset}) into {@code dest} at absolute byte offset
     * {@code destOffset}, applying the supplied ditherer when one is given.
     *
     * <p>At most one of {@code tpdf} and {@code noiseShaped} is used; both
     * may be {@code null} (plain rounding). {@code noiseShaped} is indexed by
     * channel because noise shaping carries per-channel error state.</p>
     *
     * @param audio       {@code [channel][sample]} in {@code [-1.0, 1.0]}
     * @param frameOffset first frame to encode
     * @param numFrames   number of frames to encode
     * @param channels    number of channels to interleave
     * @param bitDepth    8, 16, 24 or 32
     * @param ieeeFloat   {@code true} to store 32-bit IEEE float samples
     *                    (ditherers are then ignored)
     * @param dest        little-endian destination buffer
     * @param destOffset  absolute byte offset of the first encoded sample
     * @param tpdf        TPDF ditherer, or {@code null}
     * @param noiseShaped per-channel noise-shaped ditherers, or {@code null}
     * @return the number of bytes written
     */
    public static int encodeBlock(float[][] audio, int frameOffset, int numFrames, int channels,
                                  int bitDepth, boolean ieeeFloat, ByteBuffer dest, int destOffset,
                                  TpdfDitherer tpdf, NoiseShapedDitherer[] noiseShaped) {
        Objects.requireNonNull(audio, "audio must not be null");
        Objects.requireNonNull(dest, "dest must not be null");
        if (dest.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("dest must be little-endian");
        }
        if (channels <= 0 || channels > audio.length) {
            throw new IllegalArgumentException(
                    "channels must be in 1.." + audio.length + ": " + channels);
        }
        if (numFrames < 0 || frameOffset < 0) {
            throw new IllegalArgumentException(
                    "frameOffset/numFrames must not be negative: " + frameOffset + "/" + numFrames);
        }
        int bytesPerSample = bytesPerSample(bitDepth);
        if (ieeeFloat && bitDepth != 32) {
            throw new IllegalArgumentException("IEEE float encoding requires 32-bit: " + bitDepth);
        }
        int offset = destOffset;
        for (int i = 0; i < numFrames; i++) {
            int frame = frameOffset + i;
            for (int ch = 0; ch < channels; ch++) {
                double sample = audio[ch][frame];
                sample = Math.max(-1.0, Math.min(1.0, sample));
                if (ieeeFloat) {
                    dest.putFloat(offset, (float) sample);
                } else {
                    long quantized = quantize(sample, bitDepth, tpdf,
                            noiseShaped != null ? noiseShaped[ch] : null);
                    putIntSample(dest, offset, quantized, bitDepth);
                }
                offset += bytesPerSample;
            }
        }
        return offset - destOffset;
    }

    /**
     * Quantises one clamped sample to an integer code at {@code bitDepth}.
     * Package-private so the ditherer tests can pin it directly.
     */
    static long quantize(double sample, int bitDepth,
                         TpdfDitherer tpdf,
                         NoiseShapedDitherer noiseShaped) {
        long value;

        if (tpdf != null) {
            value = (long) tpdf.dither(sample, bitDepth);
        } else if (noiseShaped != null) {
            value = (long) noiseShaped.dither(sample, bitDepth);
        } else {
            // Simple rounding (no dithering)
            double maxVal = (1L << (bitDepth - 1)) - 1;
            value = Math.round(sample * maxVal);
        }

        // 8-bit WAV is unsigned: 0–255, silence at 128.
        // The ditherers produce signed values in [-128, 127]; shift to unsigned.
        if (bitDepth == 8) {
            value = Math.max(0, Math.min(255, value + 128));
        }

        return value;
    }

    /** Stores one integer sample code little-endian at {@code offset}. */
    static void putIntSample(ByteBuffer dest, int offset, long value, int bitDepth) {
        switch (bitDepth) {
            case 8 -> dest.put(offset, (byte) (value & 0xFF));
            case 16 -> dest.putShort(offset, (short) value);
            case 24 -> {
                dest.put(offset, (byte) (value & 0xFF));
                dest.put(offset + 1, (byte) ((value >> 8) & 0xFF));
                dest.put(offset + 2, (byte) ((value >> 16) & 0xFF));
            }
            case 32 -> dest.putInt(offset, (int) value);
            default -> throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        }
    }
}
