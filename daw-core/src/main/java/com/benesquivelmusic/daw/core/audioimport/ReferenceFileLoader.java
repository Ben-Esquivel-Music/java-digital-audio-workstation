package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.export.SampleRateConverter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * The one multi-format single-file decode that project import, project open
 * and plugins share: dispatches to the format-specific readers in this
 * package and returns a {@code float[][]} buffer, plus the one
 * rate-conversion helper that brings such a buffer to a target sample rate.
 *
 * <p>Three paths share it, so a file decodes to the same samples whichever
 * of them reads it: {@link AudioFileImporter} (project import, which then
 * converts to the project rate), the project-open clip rehydrator in
 * {@code com.benesquivelmusic.daw.core.persistence} (which rebuilds exactly
 * what the import held in memory, Persistence Integrity book §4.3), and
 * plugins (e.g. the built-in Match EQ) that load a one-off audio file for
 * analysis without going through the import pipeline (which creates tracks
 * and clips). It is not the only reader of audio files: sample preview and
 * clip processing still read WAV files with {@code WavFileReader}
 * directly.</p>
 *
 * <p>Thread: any non-real-time thread. Pure apart from the file reads.</p>
 */
public final class ReferenceFileLoader {

    /** Bytes one decoded sample occupies in memory ({@code float}). */
    private static final int FLOAT_BYTES = Float.BYTES;

    private ReferenceFileLoader() {
        // utility class
    }

    /**
     * Reads the given audio file and returns its decoded PCM payload.
     *
     * <p>Dispatches to the matching reader based on the file extension via
     * {@link SupportedAudioFormat#fromPath(Path)}. No sample-rate conversion
     * is performed — callers that need a specific rate convert the returned
     * data with {@link #convertSampleRate(float[][], int, int)}.</p>
     *
     * @param file the audio file to read
     * @return the decoded audio (channel-major), sample rate, channels, bit depth
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file format is not supported
     * @throws NullPointerException     if {@code file} is {@code null}
     */
    public static AudioReadResult read(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        SupportedAudioFormat format = SupportedAudioFormat.fromPath(file)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported audio file format: " + file.getFileName()));
        return read(file, format);
    }

    /**
     * Reads an audio file using the reader for {@code format}.
     */
    static AudioReadResult read(Path file, SupportedAudioFormat format) throws IOException {
        return switch (format) {
            case WAV -> {
                WavFileReader.WavReadResult wav = WavFileReader.read(file);
                yield new AudioReadResult(wav.audioData(), wav.sampleRate(),
                        wav.channels(), wav.bitDepth());
            }
            case FLAC -> FlacFileReader.read(file);
            case AIFF -> AiffFileReader.read(file);
            case OGG -> OggVorbisFileReader.read(file);
            case MP3 -> Mp3FileReader.read(file);
        };
    }

    /**
     * Converts channel-major audio from {@code sourceSampleRate} to
     * {@code targetSampleRate}, channel by channel, with the windowed-sinc
     * {@link SampleRateConverter} — the conversion every project import
     * applies.
     *
     * @param audioData        the audio as {@code [channel][sample]}
     * @param sourceSampleRate the rate {@code audioData} is at, in Hz
     * @param targetSampleRate the rate wanted, in Hz
     * @return {@code audioData} itself when the two rates are equal, otherwise
     *         a new array at the target rate
     */
    public static float[][] convertSampleRate(float[][] audioData, int sourceSampleRate,
                                              int targetSampleRate) {
        Objects.requireNonNull(audioData, "audioData must not be null");
        if (sourceSampleRate == targetSampleRate) {
            return audioData;
        }
        int channels = audioData.length;
        float[][] converted = new float[channels][];
        for (int ch = 0; ch < channels; ch++) {
            converted[ch] = SampleRateConverter.convert(audioData[ch], sourceSampleRate, targetSampleRate);
        }
        return converted;
    }

    /**
     * What decoding a file with {@link #read(Path)} and converting it with
     * {@link #convertSampleRate} to a target rate allocates in {@code float}
     * samples, as {@link #estimateDecode} judges it from the headers.
     *
     * @param decodedBytes   the bytes the decode allocates, at the file's own rate
     * @param convertedBytes the bytes the conversion allocates: 0 when the
     *                       headers give the file's rate and it is the target
     *                       (the conversion returns the decoded array
     *                       itself), the decoded size at the target rate when
     *                       they give another rate, and {@code decodedBytes}
     *                       — a conservative second copy — when the headers do
     *                       not give the rate without decoding
     */
    public record DecodeEstimate(long decodedBytes, long convertedBytes) {
        public DecodeEstimate {
            if (decodedBytes < 0 || convertedBytes < 0) {
                throw new IllegalArgumentException("byte counts must not be negative: "
                        + decodedBytes + ", " + convertedBytes);
            }
        }
    }

    /**
     * Estimates how many bytes of {@code float} samples decoding
     * {@code file} and converting it to {@code targetSampleRate} allocate:
     * for a WAV from its {@code fmt } and {@code data} chunk headers (a
     * header-only walk, nothing else is read), so a WAV already at the target
     * rate is charged no conversion copy; for every other format — and for a
     * WAV whose headers do not parse — from the file size and a conservative
     * expansion ratio for the format, with a second copy for the conversion
     * whose need the headers cannot tell. An estimate for memory budgeting,
     * not a promise: the decode itself judges whether the file is valid.
     *
     * @param file             the audio file
     * @param targetSampleRate the rate the decoded audio will be converted to, in Hz
     * @return the estimated decode and conversion sizes
     * @throws IOException              if the file's size cannot be read
     * @throws IllegalArgumentException if the file format is not supported
     */
    public static DecodeEstimate estimateDecode(Path file, int targetSampleRate) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        SupportedAudioFormat format = SupportedAudioFormat.fromPath(file)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported audio file format: " + file.getFileName()));
        long fileBytes = Files.size(file);
        if (format == SupportedAudioFormat.WAV) {
            Optional<WavShape> shape = wavShape(file);
            if (shape.isPresent()) {
                return shape.get().estimate(targetSampleRate);
            }
        }
        long decoded = saturatedMultiply(fileBytes, expansionRatio(format));
        return new DecodeEstimate(decoded, decoded);
    }

    /**
     * Decoded {@code float} bytes per encoded byte, rounded up to the
     * conservative side: uncompressed PCM (WAV, AIFF) at most quadruples
     * (8-bit samples); FLAC at a typical 2:1 compression of 16-bit audio
     * quadruples; lossy formats at about 11:1 grow about 22-fold.
     */
    private static long expansionRatio(SupportedAudioFormat format) {
        return switch (format) {
            case WAV, AIFF, FLAC -> 4;
            case OGG, MP3 -> 24;
        };
    }

    /** What a WAV's {@code fmt } and {@code data} chunk headers declare. */
    private record WavShape(int channels, int sampleRate, int bitDepth, long dataBytes) {
        DecodeEstimate estimate(int targetSampleRate) {
            long frames = dataBytes / ((long) channels * (bitDepth / 8));
            long samples = saturatedMultiply(frames, channels);
            long decoded = saturatedMultiply(samples, FLOAT_BYTES);
            if (sampleRate == targetSampleRate) {
                return new DecodeEstimate(decoded, 0L);
            }
            double scale = (double) targetSampleRate / sampleRate;
            return new DecodeEstimate(decoded, saturatedMultiply((long) Math.ceil(samples * scale), FLOAT_BYTES));
        }
    }

    private static Optional<WavShape> wavShape(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            RiffChunks.Chunk fmt = null;
            RiffChunks.Chunk data = null;
            for (RiffChunks.Chunk chunk : RiffChunks.scan(channel)) {
                if (fmt == null && chunk.id().equals("fmt ")) {
                    fmt = chunk;
                } else if (data == null && chunk.id().equals("data")) {
                    data = chunk;
                }
            }
            if (fmt == null || data == null || fmt.size() < 16) {
                return Optional.empty();
            }
            ByteBuffer body = ByteBuffer.wrap(RiffChunks.readBody(channel, fmt, 16))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int channels = body.getShort(2) & 0xFFFF;
            int sampleRate = body.getInt(4);
            int bitDepth = body.getShort(14) & 0xFFFF;
            if (channels <= 0 || sampleRate <= 0 || bitDepth < 8) {
                return Optional.empty();
            }
            return Optional.of(new WavShape(channels, sampleRate, bitDepth, data.size()));
        }
    }

    private static long saturatedMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
