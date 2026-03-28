package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Exports audio data to the FLAC (Free Lossless Audio Codec) format.
 *
 * <p>Implements a subset of the FLAC specification using verbatim subframes
 * (uncompressed PCM samples stored in FLAC container framing). While this
 * produces larger files than a full FLAC encoder with prediction, it is fully
 * standards-compliant and decodable by any FLAC player.</p>
 *
 * <p>Supports 16-bit and 24-bit sample depths with optional dithering when
 * reducing from a higher bit depth.</p>
 *
 * <p>Reference: <a href="https://xiph.org/flac/format.html">FLAC Format Specification</a></p>
 */
public final class FlacExporter {

    private static final int FLAC_BLOCK_SIZE = 4096;

    private FlacExporter() {
        // utility class
    }

    /**
     * Writes audio data to a FLAC file.
     *
     * @param audioData  audio samples as {@code [channel][sample]} in [-1.0, 1.0]
     * @param sampleRate the sample rate in Hz
     * @param bitDepth   the target bit depth (16 or 24)
     * @param ditherType the dithering algorithm for bit-depth reduction
     * @param outputPath the output file path
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static void write(float[][] audioData, int sampleRate, int bitDepth,
                             DitherType ditherType, Path outputPath) throws IOException {

        Objects.requireNonNull(audioData, "audioData must not be null");
        Objects.requireNonNull(ditherType, "ditherType must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        if (audioData.length == 0) {
            throw new IllegalArgumentException("audioData must have at least one channel");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bitDepth != 16 && bitDepth != 24) {
            throw new IllegalArgumentException("bitDepth must be 16 or 24 for FLAC: " + bitDepth);
        }

        int channels = audioData.length;
        int totalSamples = audioData[0].length;

        TpdfDitherer tpdf = (ditherType == DitherType.TPDF) ? new TpdfDitherer() : null;
        NoiseShapedDitherer[] noiseShaped = null;
        if (ditherType == DitherType.NOISE_SHAPED) {
            noiseShaped = new NoiseShapedDitherer[channels];
            for (int ch = 0; ch < channels; ch++) {
                noiseShaped[ch] = new NoiseShapedDitherer();
            }
        }

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            // FLAC stream marker: "fLaC"
            out.write(new byte[]{'f', 'L', 'a', 'C'});

            // STREAMINFO metadata block (last metadata block)
            writeStreamInfoBlock(out, sampleRate, channels, bitDepth, totalSamples);

            // Write audio frames
            int frameNumber = 0;
            int samplesWritten = 0;
            while (samplesWritten < totalSamples) {
                int blockSize = Math.min(FLAC_BLOCK_SIZE, totalSamples - samplesWritten);
                writeFrame(out, audioData, samplesWritten, blockSize,
                        channels, sampleRate, bitDepth, frameNumber,
                        tpdf, noiseShaped);
                samplesWritten += blockSize;
                frameNumber++;
            }
        }
    }

    /**
     * Writes the STREAMINFO metadata block.
     */
    private static void writeStreamInfoBlock(OutputStream out, int sampleRate,
                                             int channels, int bitDepth,
                                             int totalSamples) throws IOException {
        // Metadata block header: 1 byte (last-block flag + type) + 3 bytes (length)
        // STREAMINFO is type 0, and we mark it as the last metadata block
        byte[] header = new byte[4];
        header[0] = (byte) 0x80; // last-metadata-block flag + type 0 (STREAMINFO)
        // STREAMINFO payload is always 34 bytes
        header[1] = 0;
        header[2] = 0;
        header[3] = 34;
        out.write(header);

        ByteBuffer info = ByteBuffer.allocate(34);
        // Minimum block size (16 bits)
        info.putShort((short) FLAC_BLOCK_SIZE);
        // Maximum block size (16 bits)
        info.putShort((short) FLAC_BLOCK_SIZE);
        // Minimum frame size (24 bits) - 0 means unknown
        info.put((byte) 0);
        info.put((byte) 0);
        info.put((byte) 0);
        // Maximum frame size (24 bits) - 0 means unknown
        info.put((byte) 0);
        info.put((byte) 0);
        info.put((byte) 0);

        // Sample rate (20 bits) | channels-1 (3 bits) | bits per sample-1 (5 bits) | total samples (36 bits)
        // = 8 bytes total
        long sampleRateBits = ((long) sampleRate) & 0xFFFFF;
        int channelsBits = (channels - 1) & 0x7;
        int bpsBits = (bitDepth - 1) & 0x1F;
        long totalSamplesLong = totalSamples & 0xFFFFFFFFFL;

        // Pack into 8 bytes:
        // Byte 0-2: sample rate (20 bits) + channels-1 (3 bits) + top 1 bit of bps-1
        // Byte 3: bottom 4 bits of bps-1 + top 4 bits of total samples
        // Byte 4-7: bottom 32 bits of total samples
        long packed = (sampleRateBits << 44)
                | ((long) channelsBits << 41)
                | ((long) bpsBits << 36)
                | totalSamplesLong;

        info.put((byte) ((packed >> 56) & 0xFF));
        info.put((byte) ((packed >> 48) & 0xFF));
        info.put((byte) ((packed >> 40) & 0xFF));
        info.put((byte) ((packed >> 32) & 0xFF));
        info.put((byte) ((packed >> 24) & 0xFF));
        info.put((byte) ((packed >> 16) & 0xFF));
        info.put((byte) ((packed >> 8) & 0xFF));
        info.put((byte) (packed & 0xFF));

        // MD5 signature of unencoded audio data (16 bytes) - zeroed (unknown)
        for (int i = 0; i < 16; i++) {
            info.put((byte) 0);
        }

        out.write(info.array());
    }

    /**
     * Writes a single FLAC audio frame using verbatim subframes.
     */
    private static void writeFrame(OutputStream out, float[][] audioData,
                                   int startSample, int blockSize,
                                   int channels, int sampleRate, int bitDepth,
                                   int frameNumber,
                                   TpdfDitherer tpdf,
                                   NoiseShapedDitherer[] noiseShaped) throws IOException {
        // Build frame in memory to compute CRC
        java.io.ByteArrayOutputStream frameBytes = new java.io.ByteArrayOutputStream(
                blockSize * channels * (bitDepth / 8) + 64);

        // Frame header
        // Sync code: 0xFFF8 (14 bits sync + 1 bit reserved=0 + 1 bit blocking strategy=0=fixed)
        frameBytes.write(0xFF);
        frameBytes.write(0xF8);

        // Block size code and sample rate code
        int blockSizeCode = getBlockSizeCode(blockSize);
        int sampleRateCode = getSampleRateCode(sampleRate);
        frameBytes.write((blockSizeCode << 4) | sampleRateCode);

        // Channel assignment and sample size
        int channelCode = (channels - 1) & 0xF;
        int sampleSizeCode = getSampleSizeCode(bitDepth);
        frameBytes.write((channelCode << 4) | (sampleSizeCode << 1));

        // Frame number (UTF-8 coded)
        writeUtf8Number(frameBytes, frameNumber);

        // If block size code needs extra bytes
        if (blockSizeCode == 6) {
            frameBytes.write((blockSize - 1) & 0xFF);
        } else if (blockSizeCode == 7) {
            frameBytes.write(((blockSize - 1) >> 8) & 0xFF);
            frameBytes.write((blockSize - 1) & 0xFF);
        }

        // If sample rate code needs extra bytes
        if (sampleRateCode == 12) {
            frameBytes.write((sampleRate / 1000) & 0xFF);
        } else if (sampleRateCode == 13) {
            frameBytes.write((sampleRate >> 8) & 0xFF);
            frameBytes.write(sampleRate & 0xFF);
        }

        // Frame header CRC-8
        byte[] headerSoFar = frameBytes.toByteArray();
        frameBytes.write(crc8(headerSoFar));

        // Write subframes (one per channel) - verbatim subframes
        for (int ch = 0; ch < channels; ch++) {
            // Subframe header: 1 zero bit + 6-bit type (verbatim=1) + 1 bit wasted bits flag
            // type = 000001 for verbatim
            frameBytes.write(0x02); // 0b00000010 = verbatim type, no wasted bits

            // Write samples for this channel
            for (int i = 0; i < blockSize; i++) {
                int sampleIndex = startSample + i;
                double sample = audioData[ch][sampleIndex];
                sample = Math.max(-1.0, Math.min(1.0, sample));

                long quantized = quantize(sample, bitDepth, tpdf,
                        noiseShaped != null ? noiseShaped[ch] : null);

                writeSample(frameBytes, quantized, bitDepth);
            }
        }

        // Byte-align if needed (verbatim frames with standard bit depths are already aligned)

        // Frame footer: CRC-16
        byte[] frameData = frameBytes.toByteArray();
        int crc16 = crc16(frameData);
        frameBytes.write((crc16 >> 8) & 0xFF);
        frameBytes.write(crc16 & 0xFF);

        out.write(frameBytes.toByteArray());
    }

    private static long quantize(double sample, int bitDepth,
                                 TpdfDitherer tpdf,
                                 NoiseShapedDitherer noiseShaped) {
        if (tpdf != null) {
            return (long) tpdf.dither(sample, bitDepth);
        } else if (noiseShaped != null) {
            return (long) noiseShaped.dither(sample, bitDepth);
        } else {
            double maxVal = (1L << (bitDepth - 1)) - 1;
            return Math.round(sample * maxVal);
        }
    }

    private static void writeSample(java.io.ByteArrayOutputStream out, long value, int bitDepth) {
        if (bitDepth == 16) {
            out.write((int) ((value >> 8) & 0xFF));
            out.write((int) (value & 0xFF));
        } else if (bitDepth == 24) {
            out.write((int) ((value >> 16) & 0xFF));
            out.write((int) ((value >> 8) & 0xFF));
            out.write((int) (value & 0xFF));
        }
    }

    private static int getBlockSizeCode(int blockSize) {
        // Common FLAC block sizes
        if (blockSize == 4096) return 12; // actually 0xC = 4096
        // Use end-of-header 16-bit value
        if (blockSize <= 256) return 6; // 8-bit value follows
        return 7; // 16-bit value follows
    }

    private static int getSampleRateCode(int sampleRate) {
        return switch (sampleRate) {
            case 88200 -> 1;
            case 176400 -> 2;
            case 192000 -> 3;
            case 8000 -> 4;
            case 16000 -> 5;
            case 22050 -> 6;
            case 24000 -> 7;
            case 32000 -> 8;
            case 44100 -> 9;
            case 48000 -> 10;
            case 96000 -> 11;
            default -> {
                if (sampleRate % 1000 == 0 && sampleRate / 1000 <= 255) {
                    yield 12; // kHz, 8-bit follows
                }
                yield 13; // Hz, 16-bit follows
            }
        };
    }

    private static int getSampleSizeCode(int bitDepth) {
        return switch (bitDepth) {
            case 8 -> 1;
            case 12 -> 2;
            case 16 -> 4;
            case 20 -> 5;
            case 24 -> 6;
            case 32 -> 7; // only for FLAC 32-bit integer support
            default -> 0; // "get from STREAMINFO"
        };
    }

    /**
     * Writes a non-negative integer in FLAC's UTF-8-like coding.
     */
    private static void writeUtf8Number(java.io.ByteArrayOutputStream out, int value) {
        if (value < 0x80) {
            out.write(value);
        } else if (value < 0x800) {
            out.write(0xC0 | (value >> 6));
            out.write(0x80 | (value & 0x3F));
        } else if (value < 0x10000) {
            out.write(0xE0 | (value >> 12));
            out.write(0x80 | ((value >> 6) & 0x3F));
            out.write(0x80 | (value & 0x3F));
        } else if (value < 0x200000) {
            out.write(0xF0 | (value >> 18));
            out.write(0x80 | ((value >> 12) & 0x3F));
            out.write(0x80 | ((value >> 6) & 0x3F));
            out.write(0x80 | (value & 0x3F));
        } else {
            out.write(0xF8 | (value >> 24));
            out.write(0x80 | ((value >> 18) & 0x3F));
            out.write(0x80 | ((value >> 12) & 0x3F));
            out.write(0x80 | ((value >> 6) & 0x3F));
            out.write(0x80 | (value & 0x3F));
        }
    }

    /**
     * Computes CRC-8 with polynomial x^8 + x^2 + x^1 + x^0 (FLAC standard).
     */
    private static byte crc8(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = ((crc << 1) ^ 0x07) & 0xFF;
                } else {
                    crc = (crc << 1) & 0xFF;
                }
            }
        }
        return (byte) crc;
    }

    /**
     * Computes CRC-16 with polynomial x^16 + x^15 + x^2 + x^0 (FLAC standard).
     */
    private static int crc16(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= ((b & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x8005) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }
}
