package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads FLAC (Free Lossless Audio Codec) files and decodes the audio data
 * into normalized {@code float[][]} arrays.
 *
 * <p>Supports all standard FLAC subframe types: constant, verbatim, fixed
 * (orders 0–4), and LPC. Handles 8, 16, 24, and 32-bit sample depths.</p>
 *
 * <p>Reference: <a href="https://xiph.org/flac/format.html">FLAC Format Specification</a></p>
 */
public final class FlacFileReader {

    private FlacFileReader() {
        // utility class
    }

    /**
     * Reads a FLAC file and returns the decoded audio data.
     *
     * @param path the path to the FLAC file
     * @return the decoded audio result containing samples and format info
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the file is not a valid FLAC file
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
        if (fileData.length < 42) {
            throw new IllegalArgumentException("File is too small to be a valid FLAC file: " + path);
        }

        BitReader reader = new BitReader(fileData);

        // Validate "fLaC" marker
        if (reader.readByte() != 'f' || reader.readByte() != 'L'
                || reader.readByte() != 'a' || reader.readByte() != 'C') {
            throw new IllegalArgumentException("Not a FLAC file (missing fLaC marker): " + path);
        }

        // Read metadata blocks
        StreamInfo streamInfo = null;
        boolean lastBlock = false;
        while (!lastBlock) {
            int blockHeader = reader.readByte() & 0xFF;
            lastBlock = (blockHeader & 0x80) != 0;
            int blockType = blockHeader & 0x7F;
            int blockLength = (reader.readByte() & 0xFF) << 16
                    | (reader.readByte() & 0xFF) << 8
                    | (reader.readByte() & 0xFF);

            if (blockType == 0) {
                streamInfo = readStreamInfo(reader);
            } else {
                reader.skipBytes(blockLength);
            }
        }

        if (streamInfo == null) {
            throw new IllegalArgumentException("No STREAMINFO metadata block found: " + path);
        }

        // Decode audio frames
        int channels = streamInfo.channels;
        int bitsPerSample = streamInfo.bitsPerSample;
        long totalSamples = streamInfo.totalSamples;
        int sampleRate = streamInfo.sampleRate;

        List<int[][]> frameBlocks = new ArrayList<>();
        long samplesDecoded = 0;

        while (reader.bytesRemaining() > 0 && (totalSamples == 0 || samplesDecoded < totalSamples)) {
            try {
                int[][] frameData = decodeFrame(reader, channels, bitsPerSample, sampleRate);
                if (frameData != null && frameData[0].length > 0) {
                    frameBlocks.add(frameData);
                    samplesDecoded += frameData[0].length;
                }
            } catch (IllegalArgumentException e) {
                // End of stream or corrupted trailing bytes — stop gracefully
                break;
            }
        }

        if (frameBlocks.isEmpty()) {
            throw new IllegalArgumentException("No audio frames found in FLAC file: " + path);
        }

        // Assemble all frame blocks into a single float[][] output
        int totalFrames = 0;
        for (int[][] block : frameBlocks) {
            totalFrames += block[0].length;
        }

        float[][] audioData = new float[channels][totalFrames];
        double maxVal = (1L << (bitsPerSample - 1));
        int writePos = 0;

        for (int[][] block : frameBlocks) {
            int blockSize = block[0].length;
            for (int ch = 0; ch < channels; ch++) {
                for (int i = 0; i < blockSize; i++) {
                    audioData[ch][writePos + i] = (float) (block[ch][i] / maxVal);
                }
            }
            writePos += blockSize;
        }

        return new AudioReadResult(audioData, sampleRate, channels, bitsPerSample);
    }

    private static StreamInfo readStreamInfo(BitReader reader) {
        int minBlockSize = reader.readUint16();
        int maxBlockSize = reader.readUint16();
        reader.skipBytes(3); // min frame size
        reader.skipBytes(3); // max frame size

        // 20 bits sample rate + 3 bits (channels-1) + 5 bits (bps-1) + 36 bits total samples
        long packed = 0;
        for (int i = 0; i < 8; i++) {
            packed = (packed << 8) | (reader.readByte() & 0xFF);
        }

        int sampleRate = (int) ((packed >> 44) & 0xFFFFF);
        int channels = (int) (((packed >> 41) & 0x7) + 1);
        int bitsPerSample = (int) (((packed >> 36) & 0x1F) + 1);
        long totalSamples = packed & 0xFFFFFFFFFL;

        reader.skipBytes(16); // MD5

        return new StreamInfo(minBlockSize, maxBlockSize, sampleRate, channels, bitsPerSample, totalSamples);
    }

    private static int[][] decodeFrame(BitReader reader, int streamChannels,
                                       int streamBps, int streamSampleRate) {
        // Search for frame sync: 0xFFF8 or 0xFFF9
        if (!findFrameSync(reader)) {
            return null;
        }

        int syncByte2 = reader.getLastSyncByte();
        boolean variableBlockSize = (syncByte2 & 0x01) != 0;

        // Block size code (4 bits) + sample rate code (4 bits)
        int bsSrByte = reader.readByte() & 0xFF;
        int blockSizeCode = (bsSrByte >> 4) & 0x0F;
        int sampleRateCode = bsSrByte & 0x0F;

        // Channel assignment (4 bits) + sample size code (3 bits) + reserved (1 bit)
        int chSsByte = reader.readByte() & 0xFF;
        int channelAssignment = (chSsByte >> 4) & 0x0F;
        int sampleSizeCode = (chSsByte >> 1) & 0x07;

        // Frame/sample number (UTF-8 coded)
        readUtf8Number(reader);

        // Resolve block size
        int blockSize = resolveBlockSize(blockSizeCode, reader);
        if (blockSize <= 0 || blockSize > 65535) {
            throw new IllegalArgumentException("Invalid FLAC block size: " + blockSize);
        }

        // Resolve sample rate (use stream sample rate if code is 0)
        int sampleRate = resolveSampleRate(sampleRateCode, reader, streamSampleRate);

        // Resolve bits per sample
        int bitsPerSample = resolveBitsPerSample(sampleSizeCode, streamBps);

        // Skip CRC-8 of header
        reader.readByte();

        // Determine number of channels and decode mode
        int numChannels;
        boolean leftSide = false;
        boolean rightSide = false;
        boolean midSide = false;

        if (channelAssignment <= 7) {
            numChannels = channelAssignment + 1;
        } else if (channelAssignment == 8) {
            numChannels = 2;
            leftSide = true;
        } else if (channelAssignment == 9) {
            numChannels = 2;
            rightSide = true;
        } else if (channelAssignment == 10) {
            numChannels = 2;
            midSide = true;
        } else {
            throw new IllegalArgumentException("Reserved channel assignment: " + channelAssignment);
        }

        // Decode subframes
        reader.resetBitBuffer();
        int[][] samples = new int[numChannels][blockSize];

        for (int ch = 0; ch < numChannels; ch++) {
            int subframeBps = bitsPerSample;
            if (leftSide && ch == 1) subframeBps++;
            else if (rightSide && ch == 0) subframeBps++;
            else if (midSide && ch == 1) subframeBps++;

            decodeSubframe(reader, samples[ch], blockSize, subframeBps);
        }

        // Apply stereo decorrelation
        if (leftSide) {
            for (int i = 0; i < blockSize; i++) {
                samples[1][i] = samples[0][i] - samples[1][i];
            }
        } else if (rightSide) {
            for (int i = 0; i < blockSize; i++) {
                samples[0][i] = samples[0][i] + samples[1][i];
            }
        } else if (midSide) {
            for (int i = 0; i < blockSize; i++) {
                int mid = samples[0][i];
                int side = samples[1][i];
                samples[0][i] = mid + (side >> 1) + (side & 1);
                samples[1][i] = mid - (side >> 1);
            }
        }

        // Byte-align and skip CRC-16
        reader.alignToByte();
        reader.readByte(); // CRC-16 high byte
        reader.readByte(); // CRC-16 low byte

        return samples;
    }

    private static boolean findFrameSync(BitReader reader) {
        while (reader.bytesRemaining() >= 2) {
            int b = reader.readByte() & 0xFF;
            if (b == 0xFF) {
                int next = reader.peekByte() & 0xFF;
                if ((next & 0xFE) == 0xF8) {
                    reader.setLastSyncByte(reader.readByte() & 0xFF);
                    return true;
                }
            }
        }
        return false;
    }

    private static long readUtf8Number(BitReader reader) {
        int first = reader.readByte() & 0xFF;
        int len;
        long value;

        if ((first & 0x80) == 0) {
            return first;
        } else if ((first & 0xE0) == 0xC0) {
            len = 1;
            value = first & 0x1F;
        } else if ((first & 0xF0) == 0xE0) {
            len = 2;
            value = first & 0x0F;
        } else if ((first & 0xF8) == 0xF0) {
            len = 3;
            value = first & 0x07;
        } else if ((first & 0xFC) == 0xF8) {
            len = 4;
            value = first & 0x03;
        } else if ((first & 0xFE) == 0xFC) {
            len = 5;
            value = first & 0x01;
        } else {
            len = 6;
            value = 0;
        }

        for (int i = 0; i < len; i++) {
            value = (value << 6) | (reader.readByte() & 0x3F);
        }
        return value;
    }

    private static int resolveBlockSize(int code, BitReader reader) {
        return switch (code) {
            case 0 -> throw new IllegalArgumentException("Reserved block size code 0");
            case 1 -> 192;
            case 2 -> 576;
            case 3 -> 1152;
            case 4 -> 2304;
            case 5 -> 4608;
            case 6 -> (reader.readByte() & 0xFF) + 1;
            case 7 -> ((reader.readByte() & 0xFF) << 8 | (reader.readByte() & 0xFF)) + 1;
            case 8 -> 256;
            case 9 -> 512;
            case 10 -> 1024;
            case 11 -> 2048;
            case 12 -> 4096;
            case 13 -> 8192;
            case 14 -> 16384;
            case 15 -> 32768;
            default -> throw new IllegalArgumentException("Invalid block size code: " + code);
        };
    }

    private static int resolveSampleRate(int code, BitReader reader, int streamSampleRate) {
        return switch (code) {
            case 0 -> streamSampleRate;
            case 1 -> 88200;
            case 2 -> 176400;
            case 3 -> 192000;
            case 4 -> 8000;
            case 5 -> 16000;
            case 6 -> 22050;
            case 7 -> 24000;
            case 8 -> 32000;
            case 9 -> 44100;
            case 10 -> 48000;
            case 11 -> 96000;
            case 12 -> (reader.readByte() & 0xFF) * 1000;
            case 13 -> ((reader.readByte() & 0xFF) << 8 | (reader.readByte() & 0xFF));
            case 14 -> ((reader.readByte() & 0xFF) << 8 | (reader.readByte() & 0xFF)) * 10;
            case 15 -> throw new IllegalArgumentException("Invalid sample rate code: 15");
            default -> throw new IllegalArgumentException("Invalid sample rate code: " + code);
        };
    }

    private static int resolveBitsPerSample(int code, int streamBps) {
        return switch (code) {
            case 0 -> streamBps;
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            case 7 -> 32;
            default -> throw new IllegalArgumentException("Reserved sample size code: " + code);
        };
    }

    private static void decodeSubframe(BitReader reader, int[] output, int blockSize, int bps) {
        int header = reader.readBits(8);
        if ((header & 0x80) != 0) {
            throw new IllegalArgumentException("Invalid subframe header: zero bit is set");
        }

        int type = (header >> 1) & 0x3F;
        boolean hasWastedBits = (header & 0x01) != 0;

        int wastedBits = 0;
        if (hasWastedBits) {
            wastedBits = 1;
            while (reader.readBits(1) == 0) {
                wastedBits++;
            }
            bps -= wastedBits;
        }

        if (type == 0) {
            // CONSTANT subframe
            int value = reader.readSignedBits(bps);
            for (int i = 0; i < blockSize; i++) {
                output[i] = value;
            }
        } else if (type == 1) {
            // VERBATIM subframe
            for (int i = 0; i < blockSize; i++) {
                output[i] = reader.readSignedBits(bps);
            }
        } else if (type >= 8 && type <= 12) {
            // FIXED subframe (predictor order 0-4)
            int order = type - 8;
            decodeFixedSubframe(reader, output, blockSize, bps, order);
        } else if (type >= 32) {
            // LPC subframe (order = type - 31)
            int order = type - 31;
            decodeLpcSubframe(reader, output, blockSize, bps, order);
        } else {
            throw new IllegalArgumentException("Reserved subframe type: " + type);
        }

        if (wastedBits > 0) {
            for (int i = 0; i < blockSize; i++) {
                output[i] <<= wastedBits;
            }
        }
    }

    private static void decodeFixedSubframe(BitReader reader, int[] output,
                                            int blockSize, int bps, int order) {
        // Read warm-up samples
        for (int i = 0; i < order; i++) {
            output[i] = reader.readSignedBits(bps);
        }

        // Read residual
        decodeResidual(reader, output, blockSize, order);

        // Apply fixed prediction
        switch (order) {
            case 0 -> {
                // No prediction — residual is the signal
            }
            case 1 -> {
                for (int i = order; i < blockSize; i++) {
                    output[i] += output[i - 1];
                }
            }
            case 2 -> {
                for (int i = order; i < blockSize; i++) {
                    output[i] += 2 * output[i - 1] - output[i - 2];
                }
            }
            case 3 -> {
                for (int i = order; i < blockSize; i++) {
                    output[i] += 3 * output[i - 1] - 3 * output[i - 2] + output[i - 3];
                }
            }
            case 4 -> {
                for (int i = order; i < blockSize; i++) {
                    output[i] += 4 * output[i - 1] - 6 * output[i - 2]
                            + 4 * output[i - 3] - output[i - 4];
                }
            }
        }
    }

    private static void decodeLpcSubframe(BitReader reader, int[] output,
                                          int blockSize, int bps, int order) {
        // Read warm-up samples
        for (int i = 0; i < order; i++) {
            output[i] = reader.readSignedBits(bps);
        }

        // Read LPC precision (4 bits) + 1
        int precision = reader.readBits(4) + 1;

        // Read LPC shift (5 bits, signed)
        int shift = reader.readSignedBits(5);

        // Read predictor coefficients
        int[] coefficients = new int[order];
        for (int i = 0; i < order; i++) {
            coefficients[i] = reader.readSignedBits(precision);
        }

        // Read residual
        decodeResidual(reader, output, blockSize, order);

        // Apply LPC prediction
        for (int i = order; i < blockSize; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) {
                sum += (long) coefficients[j] * output[i - 1 - j];
            }
            output[i] += (int) (sum >> shift);
        }
    }

    private static void decodeResidual(BitReader reader, int[] output,
                                       int blockSize, int predictorOrder) {
        int codingMethod = reader.readBits(2);
        int partitionOrder = reader.readBits(4);
        int numPartitions = 1 << partitionOrder;

        int riceBits = (codingMethod == 0) ? 4 : 5;
        int escapeCode = (codingMethod == 0) ? 15 : 31;

        int sampleIndex = predictorOrder;
        for (int partition = 0; partition < numPartitions; partition++) {
            int partitionSamples;
            if (partitionOrder == 0) {
                partitionSamples = blockSize - predictorOrder;
            } else if (partition == 0) {
                partitionSamples = (blockSize >> partitionOrder) - predictorOrder;
            } else {
                partitionSamples = blockSize >> partitionOrder;
            }

            int riceParam = reader.readBits(riceBits);

            if (riceParam == escapeCode) {
                // Escape: raw samples with explicit bit depth
                int rawBits = reader.readBits(5);
                for (int i = 0; i < partitionSamples; i++) {
                    output[sampleIndex++] = reader.readSignedBits(rawBits);
                }
            } else {
                for (int i = 0; i < partitionSamples; i++) {
                    // Read Rice-coded residual
                    int quotient = 0;
                    while (reader.readBits(1) == 0) {
                        quotient++;
                    }
                    int remainder = (riceParam > 0) ? reader.readBits(riceParam) : 0;
                    int value = (quotient << riceParam) | remainder;
                    // Zig-zag decode
                    output[sampleIndex++] = (value & 1) != 0 ? -(value >>> 1) - 1 : (value >>> 1);
                }
            }
        }
    }

    // ── Inner types ─────────────────────────────────────────────────────────

    private record StreamInfo(int minBlockSize, int maxBlockSize, int sampleRate,
                              int channels, int bitsPerSample, long totalSamples) {
    }

    /**
     * Bit-level reader for FLAC streams.
     */
    static final class BitReader {
        private final byte[] data;
        private int bytePos;
        private int bitBuffer;
        private int bitsInBuffer;
        private int lastSyncByte;

        BitReader(byte[] data) {
            this.data = data;
        }

        int readByte() {
            if (bytePos >= data.length) {
                throw new IllegalArgumentException("Unexpected end of FLAC stream");
            }
            return data[bytePos++] & 0xFF;
        }

        int peekByte() {
            if (bytePos >= data.length) {
                return -1;
            }
            return data[bytePos] & 0xFF;
        }

        int readUint16() {
            return (readByte() << 8) | readByte();
        }

        void skipBytes(int n) {
            bytePos += n;
        }

        int bytesRemaining() {
            return data.length - bytePos;
        }

        int getLastSyncByte() {
            return lastSyncByte;
        }

        void setLastSyncByte(int b) {
            this.lastSyncByte = b;
        }

        void resetBitBuffer() {
            bitBuffer = 0;
            bitsInBuffer = 0;
        }

        void alignToByte() {
            bitsInBuffer = 0;
            bitBuffer = 0;
        }

        int readBits(int n) {
            while (bitsInBuffer < n) {
                bitBuffer = (bitBuffer << 8) | readByte();
                bitsInBuffer += 8;
            }
            bitsInBuffer -= n;
            int value = (bitBuffer >> bitsInBuffer) & ((1 << n) - 1);
            bitBuffer &= (1 << bitsInBuffer) - 1;
            return value;
        }

        int readSignedBits(int n) {
            int value = readBits(n);
            // Sign-extend
            if (n > 0 && (value & (1 << (n - 1))) != 0) {
                value |= (-1 << n);
            }
            return value;
        }
    }
}
