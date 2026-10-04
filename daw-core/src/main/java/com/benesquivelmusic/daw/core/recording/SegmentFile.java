package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.export.PcmSampleEncoding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Reader for the recording-segment grammar of {@link SegmentWriter}
 * (Recording Reliability book §3.4, §4.7; story 323), for both streaming
 * {@code .part} files and sealed {@code .wav} files.
 *
 * <p>This is the reader a crash-recovery scan (story 332) will use: it needs
 * nothing but the bytes on disk and the file name.</p>
 * <ul>
 *   <li>A file whose name ends with {@link SegmentWriter#PART_SUFFIX} is
 *       <em>streaming</em>: its frame count is derived from the file length —
 *       {@code (length − 44) / (channels × bytesPerSample)}, floored to whole
 *       frames (a partial trailing frame from a crash mid-write is
 *       ignored).</li>
 *   <li>Any other file is <em>sealed</em>: its frame count comes from the
 *       data-size field at offset 40, which must not be the provisional
 *       sentinel and must fit inside the file (trailing chunks after the
 *       data chunk are tolerated).</li>
 * </ul>
 *
 * <p>Sample decoding uses the same scale as the project's
 * {@code WavFileReader}: signed PCM divided by {@code 2^(bits−1)}, IEEE
 * float as-is.</p>
 *
 * <p>Thread: any non-real-time thread.</p>
 */
public final class SegmentFile {

    /** Size of the canonical header every segment starts with. */
    public static final int HEADER_BYTES = SegmentWriter.DATA_OFFSET;

    private static final int READ_CHUNK_FRAMES = 16384;

    private SegmentFile() {
        // utility class
    }

    /**
     * What the grammar says about a segment file.
     *
     * @param sampleRate frames per second from the header
     * @param channels   channel count from the header
     * @param bitDepth   bits per sample from the header (16, 24 or 32)
     * @param frameCount whole frames present (length-derived while
     *                   streaming, data-size-derived once sealed)
     * @param sealed     {@code true} for a sealed {@code .wav}, {@code false}
     *                   for a streaming {@code .part}
     */
    public record Description(double sampleRate, int channels, int bitDepth,
                              long frameCount, boolean sealed) {
        public Description {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
            }
            if (channels <= 0) {
                throw new IllegalArgumentException("channels must be positive: " + channels);
            }
            if (bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
                throw new IllegalArgumentException("bitDepth must be 16, 24 or 32: " + bitDepth);
            }
            if (frameCount < 0) {
                throw new IllegalArgumentException("frameCount must not be negative: " + frameCount);
            }
        }

        /** Returns the bytes per interleaved frame. */
        public int bytesPerFrame() {
            return channels * (bitDepth / 8);
        }
    }

    /** Parsed canonical header plus the file facts needed to read it. */
    private record Header(int formatCode, int channels, int sampleRate, int bitDepth,
                          long dataSizeField, long fileLength) {
        int bytesPerFrame() {
            return channels * (bitDepth / 8);
        }
    }

    /**
     * Describes a segment file from its bytes and name alone.
     *
     * @param file a {@code .part} or sealed {@code .wav}
     * @return the description
     * @throws IOException if the file is shorter than the 44-byte header, is
     *                     not a canonical WAV, or (for a sealed file) still
     *                     carries the provisional sentinel or a data size
     *                     that exceeds the file
     */
    public static Description describe(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            Header header = readHeader(file, fc);
            return describe(file, header);
        }
    }

    /**
     * Reads every whole frame of a segment file into {@code [channel][frame]}
     * floats in {@code [-1, 1]}.
     *
     * @param file a {@code .part} or sealed {@code .wav}
     * @return the decoded frames (each row {@code frameCount} long)
     * @throws IOException on the same conditions as {@link #describe(Path)}
     */
    public static float[][] readFrames(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            Header header = readHeader(file, fc);
            Description description = describe(file, header);
            long frameCount = description.frameCount();
            if (frameCount > Integer.MAX_VALUE) {
                throw new IOException(file + " holds " + frameCount
                        + " frames, more than a single in-memory clip can carry");
            }
            float[][] audio = new float[header.channels()][(int) frameCount];
            decodeInto(fc, header, audio, 0, (int) frameCount);
            return audio;
        }
    }

    /**
     * Reads the segments of one take, in the order given, into a single
     * {@code [channel][frame]} array: the frames of each segment follow the
     * frames of the one before it, as they were captured. Every segment is
     * described first; the array is then allocated once at the summed frame
     * count and each segment is decoded straight into its place in it.
     * Sealed {@code .wav} and streaming {@code .part} files may be mixed.
     *
     * <p>A {@code .part} that is still being written contributes the whole
     * frames it held when it was described.</p>
     *
     * @param segments the segment files in playback order; at least one
     * @return the decoded frames (each row as long as the segments' frame
     *         counts added up)
     * @throws IllegalArgumentException if {@code segments} is empty: with no
     *                                  file there is no channel count to
     *                                  shape the result by
     * @throws IOException              on the conditions of
     *                                  {@link #describe(Path)} for any
     *                                  segment; if a segment's channel count
     *                                  or sample rate differs from the first
     *                                  segment's; if the segments together
     *                                  hold more than {@link Integer#MAX_VALUE}
     *                                  frames; or if a segment ends before
     *                                  the frames it was described with
     */
    public static float[][] readFrames(List<Path> segments) throws IOException {
        List<Path> files = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
        if (files.isEmpty()) {
            throw new IllegalArgumentException("segments must not be empty");
        }
        Description[] descriptions = new Description[files.size()];
        long totalFrames = 0;
        for (int i = 0; i < descriptions.length; i++) {
            Path file = files.get(i);
            Description description = describe(file);
            Description first = descriptions[0] == null ? description : descriptions[0];
            if (description.channels() != first.channels()) {
                throw new IOException(file + " has " + description.channels()
                        + " channel(s) but " + files.getFirst() + " has " + first.channels()
                        + ": the segments of one take share a channel count");
            }
            if (description.sampleRate() != first.sampleRate()) {
                throw new IOException(file + " has a sample rate of " + (int) description.sampleRate()
                        + " Hz but " + files.getFirst() + " has " + (int) first.sampleRate()
                        + " Hz: the segments of one take share a sample rate");
            }
            descriptions[i] = description;
            totalFrames += description.frameCount();
        }
        if (totalFrames > Integer.MAX_VALUE) {
            throw new IOException("the " + files.size() + " segments from " + files.getFirst()
                    + " hold " + totalFrames + " frames, more than a single in-memory clip can carry");
        }
        float[][] audio = new float[descriptions[0].channels()][(int) totalFrames];
        int offset = 0;
        for (int i = 0; i < descriptions.length; i++) {
            Path file = files.get(i);
            int frames = (int) descriptions[i].frameCount();
            try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
                Header header = readHeader(file, fc);
                if (header.channels() != audio.length) {
                    throw new IOException(file + " changed its channel count while it was being read");
                }
                decodeInto(fc, header, audio, offset, frames);
            }
            offset += frames;
        }
        return audio;
    }

    /**
     * Decodes the first {@code frameCount} frames of an open segment into
     * {@code audio[ch][offset .. offset + frameCount)}.
     */
    private static void decodeInto(FileChannel fc, Header header, float[][] audio,
                                   int offset, int frameCount) throws IOException {
        int channels = header.channels();
        int bytesPerFrame = header.bytesPerFrame();
        boolean ieeeFloat = header.formatCode() == PcmSampleEncoding.FORMAT_IEEE_FLOAT;
        ByteBuffer chunk = ByteBuffer.allocate(READ_CHUNK_FRAMES * bytesPerFrame)
                .order(ByteOrder.LITTLE_ENDIAN);
        long position = HEADER_BYTES;
        int decoded = 0;
        while (decoded < frameCount) {
            int frames = Math.min(READ_CHUNK_FRAMES, frameCount - decoded);
            int bytes = frames * bytesPerFrame;
            chunk.clear().limit(bytes);
            readFully(fc, chunk, position);
            chunk.flip();
            for (int f = 0; f < frames; f++) {
                for (int ch = 0; ch < channels; ch++) {
                    audio[ch][offset + decoded + f] = ieeeFloat
                            ? chunk.getFloat()
                            : decodePcm(chunk, header.bitDepth());
                }
            }
            position += bytes;
            decoded += frames;
        }
    }

    /** Returns whether {@code file} is named as a streaming segment. */
    public static boolean isStreamingName(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        Path name = file.getFileName();
        return name != null && name.toString().endsWith(SegmentWriter.PART_SUFFIX);
    }

    private static Description describe(Path file, Header header) throws IOException {
        int bytesPerFrame = header.bytesPerFrame();
        boolean streaming = isStreamingName(file);
        long frameCount;
        if (streaming) {
            frameCount = (header.fileLength() - HEADER_BYTES) / bytesPerFrame;
        } else {
            if (header.dataSizeField() == (SegmentWriter.PROVISIONAL_SIZE & 0xFFFF_FFFFL)) {
                throw new IOException(file + " is named as a sealed segment but its data-size"
                        + " field still holds the provisional streaming sentinel");
            }
            if (HEADER_BYTES + header.dataSizeField() > header.fileLength()) {
                throw new IOException(file + " declares a " + header.dataSizeField()
                        + "-byte data chunk but is only " + header.fileLength() + " bytes long");
            }
            if (header.dataSizeField() % bytesPerFrame != 0) {
                throw new IOException(file + " declares a data chunk of " + header.dataSizeField()
                        + " bytes, not a whole number of " + bytesPerFrame + "-byte frames");
            }
            frameCount = header.dataSizeField() / bytesPerFrame;
        }
        return new Description(header.sampleRate(), header.channels(), header.bitDepth(),
                frameCount, !streaming);
    }

    private static Header readHeader(Path file, FileChannel fc) throws IOException {
        long length = fc.size();
        if (length < HEADER_BYTES) {
            throw new IOException(file + " is " + length + " bytes, shorter than the "
                    + HEADER_BYTES + "-byte canonical segment header");
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        readFully(fc, header, 0);
        header.flip();
        expectTag(file, header, 0, "RIFF");
        expectTag(file, header, 8, "WAVE");
        expectTag(file, header, 12, "fmt ");
        int fmtSize = header.getInt(16);
        if (fmtSize != 16) {
            throw new IOException(file + " has a " + fmtSize
                    + "-byte fmt chunk; segments use the canonical 16-byte fmt chunk");
        }
        int formatCode = header.getShort(20) & 0xFFFF;
        int channels = header.getShort(22) & 0xFFFF;
        int sampleRate = header.getInt(24);
        int bitDepth = header.getShort(34) & 0xFFFF;
        expectTag(file, header, 36, "data");
        long dataSizeField = header.getInt(40) & 0xFFFF_FFFFL;
        if (formatCode != PcmSampleEncoding.FORMAT_PCM
                && formatCode != PcmSampleEncoding.FORMAT_IEEE_FLOAT) {
            throw new IOException(file + " has WAV format code " + formatCode
                    + "; segments are PCM (1) or IEEE float (3)");
        }
        if (formatCode == PcmSampleEncoding.FORMAT_IEEE_FLOAT && bitDepth != 32) {
            throw new IOException(file + " declares IEEE float at " + bitDepth + " bits");
        }
        if (bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            throw new IOException(file + " declares " + bitDepth
                    + " bits per sample; segments are 16, 24 or 32");
        }
        if (channels <= 0) {
            throw new IOException(file + " declares " + channels + " channels");
        }
        if (sampleRate <= 0) {
            throw new IOException(file + " declares a sample rate of " + sampleRate);
        }
        return new Header(formatCode, channels, sampleRate, bitDepth, dataSizeField, length);
    }

    private static void expectTag(Path file, ByteBuffer header, int offset, String tag) throws IOException {
        byte[] actual = new byte[4];
        header.get(offset, actual);
        String found = new String(actual, StandardCharsets.US_ASCII);
        if (!tag.equals(found)) {
            throw new IOException(file + " is not a canonical WAV segment: expected '" + tag
                    + "' at offset " + offset + " but found '" + found + "'");
        }
    }

    private static float decodePcm(ByteBuffer buf, int bitDepth) {
        return switch (bitDepth) {
            case 16 -> buf.getShort() / 32768.0f;
            case 24 -> {
                int b0 = buf.get() & 0xFF;
                int b1 = buf.get() & 0xFF;
                int b2 = buf.get() & 0xFF;
                int value = ((b2 << 24) | (b1 << 16) | (b0 << 8)) >> 8; // sign-extend
                yield value / 8388608.0f;
            }
            case 32 -> buf.getInt() / 2147483648.0f;
            default -> throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        };
    }

    private static void readFully(FileChannel fc, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = fc.read(buffer, pos);
            if (n < 0) {
                throw new IOException("unexpected end of file at byte " + pos);
            }
            pos += n;
        }
    }
}
