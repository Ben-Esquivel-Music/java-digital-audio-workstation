package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads WAV (RIFF WAVE) files and decodes the audio data into
 * normalized {@code float[][]} arrays.
 *
 * <p>Supports PCM integer (8, 16, 24, 32-bit) and 32-bit IEEE float
 * WAV files. The decoded audio is returned in the range [-1.0, 1.0]
 * with channels as the first dimension.</p>
 */
public final class WavFileReader {

    /** WAV format code for PCM integer data. */
    private static final short FORMAT_PCM = 1;

    /** WAV format code for IEEE floating-point data. */
    private static final short FORMAT_IEEE_FLOAT = 3;

    private WavFileReader() {
        // utility class
    }

    /**
     * Reads a WAV file and returns the decoded audio data.
     *
     * @param path the path to the WAV file
     * @return the decoded audio result containing samples and format info
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the file is not a valid WAV file
     */
    public static WavReadResult read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }

        byte[] fileData = Files.readAllBytes(path);
        if (fileData.length < 44) {
            throw new IllegalArgumentException("File is too small to be a valid WAV file: " + path);
        }

        ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);

        // Validate RIFF header
        String riff = readAscii(buf, 4);
        if (!"RIFF".equals(riff)) {
            throw new IllegalArgumentException("Not a RIFF file: " + path);
        }
        buf.getInt(); // RIFF chunk size (skip)

        String wave = readAscii(buf, 4);
        if (!"WAVE".equals(wave)) {
            throw new IllegalArgumentException("Not a WAVE file: " + path);
        }

        // Find the fmt and data chunks
        int formatCode = -1;
        int channels = -1;
        int sampleRate = -1;
        int bitDepth = -1;
        int dataOffset = -1;
        int dataSize = -1;

        while (buf.remaining() >= 8) {
            String chunkId = readAscii(buf, 4);
            int chunkSize = buf.getInt();

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16) {
                    throw new IllegalArgumentException("Invalid fmt chunk size: " + chunkSize);
                }
                formatCode = buf.getShort() & 0xFFFF;
                channels = buf.getShort() & 0xFFFF;
                sampleRate = buf.getInt();
                buf.getInt(); // byte rate (skip)
                buf.getShort(); // block align (skip)
                bitDepth = buf.getShort() & 0xFFFF;
                // Skip any extra format bytes
                int extraBytes = chunkSize - 16;
                if (extraBytes > 0) {
                    buf.position(buf.position() + extraBytes);
                }
            } else if ("data".equals(chunkId)) {
                dataOffset = buf.position();
                dataSize = chunkSize;
                break;
            } else {
                // Skip unknown chunks (pad to even boundary per RIFF spec)
                int skipSize = chunkSize + (chunkSize % 2);
                if (buf.remaining() < skipSize) {
                    break;
                }
                buf.position(buf.position() + skipSize);
            }
        }

        if (formatCode == -1) {
            throw new IllegalArgumentException("No fmt chunk found in WAV file: " + path);
        }
        if (dataOffset == -1) {
            throw new IllegalArgumentException("No data chunk found in WAV file: " + path);
        }
        if (formatCode != FORMAT_PCM && formatCode != FORMAT_IEEE_FLOAT) {
            throw new IllegalArgumentException(
                    "Unsupported WAV format code: " + formatCode + " (only PCM and IEEE float are supported)");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("Invalid channel count: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Invalid sample rate: " + sampleRate);
        }

        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame = channels * bytesPerSample;
        int numFrames = dataSize / bytesPerFrame;

        boolean isFloat = (formatCode == FORMAT_IEEE_FLOAT);
        float[][] audioData = new float[channels][numFrames];

        buf.position(dataOffset);
        for (int frame = 0; frame < numFrames; frame++) {
            for (int ch = 0; ch < channels; ch++) {
                float sample;
                if (isFloat && bitDepth == 32) {
                    sample = buf.getFloat();
                } else {
                    sample = readPcmSample(buf, bitDepth);
                }
                audioData[ch][frame] = sample;
            }
        }

        return new WavReadResult(audioData, sampleRate, channels, bitDepth);
    }

    /**
     * Reads a PCM integer sample and normalizes it to [-1.0, 1.0].
     */
    private static float readPcmSample(ByteBuffer buf, int bitDepth) {
        switch (bitDepth) {
            case 8: {
                // 8-bit WAV is unsigned: 0–255, silence at 128
                int unsigned = buf.get() & 0xFF;
                return (unsigned - 128) / 128.0f;
            }
            case 16: {
                short value = buf.getShort();
                return value / 32768.0f;
            }
            case 24: {
                int b0 = buf.get() & 0xFF;
                int b1 = buf.get() & 0xFF;
                int b2 = buf.get() & 0xFF;
                // Sign-extend the 24-bit value
                int value = (b2 << 24) | (b1 << 16) | (b0 << 8);
                value = value >> 8; // arithmetic shift to sign-extend
                return value / 8388608.0f;
            }
            case 32: {
                int value = buf.getInt();
                return value / 2147483648.0f;
            }
            default:
                throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        }
    }

    /**
     * Reads ASCII characters from the buffer.
     */
    private static String readAscii(ByteBuffer buf, int length) {
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Result of reading a WAV file.
     *
     * @param audioData  decoded audio samples as {@code [channel][sample]} in [-1.0, 1.0]
     * @param sampleRate the sample rate in Hz
     * @param channels   the number of channels
     * @param bitDepth   the original bit depth
     */
    public record WavReadResult(float[][] audioData, int sampleRate, int channels, int bitDepth) {

        public WavReadResult {
            Objects.requireNonNull(audioData, "audioData must not be null");
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
            }
            if (channels <= 0) {
                throw new IllegalArgumentException("channels must be positive: " + channels);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth must be positive: " + bitDepth);
            }
        }

        /** Returns the total number of sample frames. */
        public int numFrames() {
            return (channels > 0) ? audioData[0].length : 0;
        }

        /** Returns the duration in seconds. */
        public double durationSeconds() {
            return (double) numFrames() / sampleRate;
        }
    }
}
