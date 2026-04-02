package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads AIFF and AIFC (Audio Interchange File Format) files and decodes the
 * audio data into normalized {@code float[][]} arrays.
 *
 * <p>Supports PCM integer AIFF (8, 16, 24, 32-bit) and uncompressed AIFC.
 * The decoded audio is returned in the range [-1.0, 1.0] with channels as
 * the first dimension.</p>
 *
 * <p>AIFF stores audio samples in big-endian byte order (unlike WAV which
 * is little-endian). The sample rate is stored as an 80-bit IEEE 754
 * extended precision float in the COMM chunk.</p>
 */
public final class AiffFileReader {

    /** AIFF compression type for uncompressed PCM. */
    private static final String COMPRESSION_NONE = "NONE";

    /** AIFC compression type for big-endian uncompressed PCM. */
    private static final String COMPRESSION_RAW = "raw ";

    private AiffFileReader() {
        // utility class
    }

    /**
     * Reads an AIFF or AIFC file and returns the decoded audio data.
     *
     * @param path the path to the AIFF/AIFC file
     * @return the decoded audio result containing samples and format info
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the file is not a valid AIFF/AIFC file
     */
    public static AudioReadResult read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }

        byte[] fileData = Files.readAllBytes(path);
        if (fileData.length < 12) {
            throw new IllegalArgumentException("File is too small to be a valid AIFF file: " + path);
        }

        ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.BIG_ENDIAN);

        // Validate FORM header
        String formId = readAscii(buf, 4);
        if (!"FORM".equals(formId)) {
            throw new IllegalArgumentException("Not a FORM file: " + path);
        }
        buf.getInt(); // FORM chunk size (skip)

        String formType = readAscii(buf, 4);
        boolean isAifc = "AIFC".equals(formType);
        if (!"AIFF".equals(formType) && !isAifc) {
            throw new IllegalArgumentException("Not an AIFF or AIFC file (form type: " + formType + "): " + path);
        }

        // Find COMM and SSND chunks
        int channels = -1;
        int numFrames = -1;
        int bitDepth = -1;
        int sampleRate = -1;
        int ssndDataOffset = -1;
        int ssndDataSize = -1;

        while (buf.remaining() >= 8) {
            String chunkId = readAscii(buf, 4);
            int chunkSize = buf.getInt();

            if ("COMM".equals(chunkId)) {
                channels = buf.getShort() & 0xFFFF;
                numFrames = buf.getInt();
                bitDepth = buf.getShort() & 0xFFFF;
                sampleRate = (int) readExtended80(buf);

                if (isAifc) {
                    // AIFC has compression type and name after the standard COMM fields
                    int commBytesRead = 2 + 4 + 2 + 10; // channels + frames + bitDepth + sampleRate
                    if (chunkSize > commBytesRead) {
                        String compressionType = readAscii(buf, 4);
                        commBytesRead += 4;
                        if (!COMPRESSION_NONE.equals(compressionType)
                                && !COMPRESSION_RAW.equals(compressionType)) {
                            throw new IllegalArgumentException(
                                    "Unsupported AIFC compression type: " + compressionType);
                        }
                        // Skip compression name (Pascal string) and any remaining bytes
                        int remaining = chunkSize - commBytesRead;
                        if (remaining > 0) {
                            buf.position(buf.position() + remaining);
                        }
                    }
                } else {
                    // Skip any extra COMM bytes beyond what we read
                    int commBytesRead = 2 + 4 + 2 + 10;
                    int remaining = chunkSize - commBytesRead;
                    if (remaining > 0) {
                        buf.position(buf.position() + remaining);
                    }
                }
            } else if ("SSND".equals(chunkId)) {
                int offset = buf.getInt();   // offset to first sample
                buf.getInt();                // block size (usually 0)
                ssndDataOffset = buf.position() + offset;
                ssndDataSize = chunkSize - 8 - offset;
                break;
            } else {
                // Skip unknown chunks (pad to even boundary per IFF spec)
                int skipSize = chunkSize + (chunkSize % 2);
                if (buf.remaining() < skipSize) {
                    break;
                }
                buf.position(buf.position() + skipSize);
            }
        }

        if (channels == -1) {
            throw new IllegalArgumentException("No COMM chunk found in AIFF file: " + path);
        }
        if (ssndDataOffset == -1) {
            throw new IllegalArgumentException("No SSND chunk found in AIFF file: " + path);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("Invalid channel count: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Invalid sample rate: " + sampleRate);
        }
        if (numFrames <= 0) {
            throw new IllegalArgumentException("Invalid number of frames: " + numFrames);
        }

        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame = channels * bytesPerSample;
        int availableFrames = Math.min(numFrames, ssndDataSize / bytesPerFrame);

        float[][] audioData = new float[channels][availableFrames];
        buf.position(ssndDataOffset);

        for (int frame = 0; frame < availableFrames; frame++) {
            for (int ch = 0; ch < channels; ch++) {
                audioData[ch][frame] = readPcmSample(buf, bitDepth);
            }
        }

        return new AudioReadResult(audioData, sampleRate, channels, bitDepth);
    }

    /**
     * Reads a PCM integer sample (big-endian) and normalizes it to [-1.0, 1.0].
     */
    private static float readPcmSample(ByteBuffer buf, int bitDepth) {
        return switch (bitDepth) {
            case 8 -> {
                // 8-bit AIFF is signed (unlike WAV which is unsigned)
                byte value = buf.get();
                yield value / 128.0f;
            }
            case 16 -> {
                short value = buf.getShort();
                yield value / 32768.0f;
            }
            case 24 -> {
                int b0 = buf.get() & 0xFF;
                int b1 = buf.get() & 0xFF;
                int b2 = buf.get() & 0xFF;
                // Big-endian: b0 is MSB
                int value = (b0 << 24) | (b1 << 16) | (b2 << 8);
                value = value >> 8; // arithmetic shift to sign-extend
                yield value / 8388608.0f;
            }
            case 32 -> {
                int value = buf.getInt();
                yield value / 2147483648.0f;
            }
            default -> throw new IllegalArgumentException("Unsupported AIFF bit depth: " + bitDepth);
        };
    }

    /**
     * Reads an 80-bit IEEE 754 extended precision floating-point number.
     *
     * <p>AIFF uses this format to store the sample rate in the COMM chunk.
     * This implementation handles the common case of positive integer sample
     * rates accurately.</p>
     */
    private static double readExtended80(ByteBuffer buf) {
        // 80-bit extended: 1 sign bit, 15 exponent bits, 64 mantissa bits (with explicit integer bit)
        int exponentBits = buf.getShort() & 0xFFFF;
        long mantissa = buf.getLong();

        int sign = (exponentBits >> 15) & 1;
        int exponent = exponentBits & 0x7FFF;

        if (exponent == 0 && mantissa == 0) {
            return 0.0;
        }

        // The value = (-1)^sign * mantissa_unsigned * 2^(exponent - 16383 - 63)
        // For typical audio sample rates, exponent - 16383 - 63 is negative,
        // meaning we shift the mantissa right to get the integer value.
        int shift = exponent - 16383 - 63;
        double value;
        if (shift < 0) {
            value = (mantissa >>> (-shift));
        } else {
            value = (double) (mantissa >>> 0) * Math.pow(2.0, shift);
        }

        return sign == 1 ? -value : value;
    }

    private static String readAscii(ByteBuffer buf, int length) {
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
