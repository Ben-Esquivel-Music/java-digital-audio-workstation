package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports audio data to the WAV (RIFF WAVE) file format.
 *
 * <p>Supports the following PCM encoding modes:</p>
 * <ul>
 *   <li>8-bit unsigned integer</li>
 *   <li>16-bit signed integer</li>
 *   <li>24-bit signed integer</li>
 *   <li>32-bit signed integer</li>
 *   <li>32-bit IEEE float</li>
 * </ul>
 *
 * <p>Dithering is applied automatically when reducing bit depth below 32 bits,
 * using the algorithm specified in the {@link AudioExportConfig}.</p>
 */
final class WavExporter {

    /** WAV format code for PCM integer data. */
    private static final short FORMAT_PCM = 1;

    /** WAV format code for IEEE floating-point data. */
    private static final short FORMAT_IEEE_FLOAT = 3;

    private WavExporter() {
        // utility class
    }

    /**
     * Writes audio data to a WAV file.
     *
     * @param audioData       audio samples as {@code [channel][sample]} in [-1.0, 1.0]
     * @param sampleRate      the sample rate in Hz
     * @param bitDepth        the target bit depth (8, 16, 24, or 32)
     * @param ditherType      the dithering algorithm for bit-depth reduction
     * @param metadata        metadata to embed (currently stored as LIST INFO chunk)
     * @param outputPath      the output file path
     * @throws IOException if an I/O error occurs
     */
    static void write(float[][] audioData, int sampleRate, int bitDepth,
                      DitherType ditherType, AudioMetadata metadata,
                      Path outputPath) throws IOException {

        int channels = audioData.length;
        int numSamples = (channels > 0) ? audioData[0].length : 0;
        int bytesPerSample = bitDepth / 8;
        boolean isFloat = (bitDepth == 32 && ditherType == DitherType.NONE);
        short formatCode = isFloat ? FORMAT_IEEE_FLOAT : FORMAT_PCM;

        // Build optional LIST INFO chunk for metadata
        byte[] listInfoChunk = buildListInfoChunk(metadata);

        int dataSize = numSamples * channels * bytesPerSample;
        // RIFF header(12) + fmt chunk(24) + data chunk header(8) + data + optional LIST
        int riffSize = 4 + 24 + 8 + dataSize + listInfoChunk.length;

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            var header = ByteBuffer.allocate(12 + 24 + 8).order(ByteOrder.LITTLE_ENDIAN);

            // RIFF header
            header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(riffSize);
            header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

            // fmt sub-chunk
            header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(16); // sub-chunk size
            header.putShort(formatCode);
            header.putShort((short) channels);
            header.putInt(sampleRate);
            header.putInt(sampleRate * channels * bytesPerSample); // byte rate
            header.putShort((short) (channels * bytesPerSample));  // block align
            header.putShort((short) bitDepth);

            // data sub-chunk header
            header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(dataSize);

            out.write(header.array());

            // Write interleaved sample data
            writeSamples(out, audioData, numSamples, channels, bitDepth, isFloat, ditherType);

            // Write LIST INFO chunk if present
            if (listInfoChunk.length > 0) {
                out.write(listInfoChunk);
            }
        }
    }

    private static void writeSamples(OutputStream out, float[][] audioData,
                                     int numSamples, int channels, int bitDepth,
                                     boolean isFloat, DitherType ditherType) throws IOException {

        TpdfDitherer tpdf = (ditherType == DitherType.TPDF) ? new TpdfDitherer() : null;
        NoiseShapedDitherer noiseShaped = (ditherType == DitherType.NOISE_SHAPED)
                ? new NoiseShapedDitherer() : null;

        int bytesPerSample = bitDepth / 8;
        var buf = ByteBuffer.allocate(bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numSamples; i++) {
            for (int ch = 0; ch < channels; ch++) {
                double sample = audioData[ch][i];

                // Clamp to [-1.0, 1.0]
                sample = Math.max(-1.0, Math.min(1.0, sample));

                buf.clear();

                if (isFloat) {
                    buf.putFloat((float) sample);
                } else {
                    long quantized = quantize(sample, bitDepth, tpdf, noiseShaped);
                    writeIntSample(buf, quantized, bitDepth);
                }

                out.write(buf.array(), 0, bytesPerSample);
            }
        }

        // Reset noise-shaped ditherer state between channels if needed
        if (noiseShaped != null) {
            noiseShaped.reset();
        }
    }

    private static long quantize(double sample, int bitDepth,
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

    private static void writeIntSample(ByteBuffer buf, long value, int bitDepth) {
        switch (bitDepth) {
            case 8 -> buf.put((byte) (value & 0xFF));
            case 16 -> buf.putShort((short) value);
            case 24 -> {
                buf.put((byte) (value & 0xFF));
                buf.put((byte) ((value >> 8) & 0xFF));
                buf.put((byte) ((value >> 16) & 0xFF));
            }
            case 32 -> buf.putInt((int) value);
            default -> throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        }
    }

    /**
     * Builds a RIFF LIST INFO chunk containing metadata fields.
     * Returns an empty array if no metadata is set.
     */
    private static byte[] buildListInfoChunk(AudioMetadata metadata) {
        if (metadata == null || isEmptyMetadata(metadata)) {
            return new byte[0];
        }

        var entries = new java.util.ArrayList<byte[]>();

        addInfoEntry(entries, "INAM", metadata.title());
        addInfoEntry(entries, "IART", metadata.artist());
        addInfoEntry(entries, "IPRD", metadata.album());

        if (entries.isEmpty()) {
            return new byte[0];
        }

        int payloadSize = 0;
        for (byte[] entry : entries) {
            payloadSize += entry.length;
        }

        // LIST header: "LIST" + size(4) + "INFO" + entries
        int chunkSize = 4 + payloadSize; // "INFO" + entries
        var buf = ByteBuffer.allocate(8 + chunkSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("LIST".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buf.putInt(chunkSize);
        buf.put("INFO".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (byte[] entry : entries) {
            buf.put(entry);
        }
        return buf.array();
    }

    private static void addInfoEntry(java.util.List<byte[]> entries, String tag, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        byte[] valueBytes = (value + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // Pad to even length per RIFF spec
        int paddedLength = valueBytes.length + (valueBytes.length % 2);
        var buf = ByteBuffer.allocate(8 + paddedLength).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buf.putInt(valueBytes.length);
        buf.put(valueBytes);
        if (valueBytes.length % 2 != 0) {
            buf.put((byte) 0);
        }
        entries.add(buf.array());
    }

    private static boolean isEmptyMetadata(AudioMetadata metadata) {
        return (metadata.title() == null || metadata.title().isEmpty())
                && (metadata.artist() == null || metadata.artist().isEmpty())
                && (metadata.album() == null || metadata.album().isEmpty())
                && (metadata.isrc() == null || metadata.isrc().isEmpty());
    }
}
