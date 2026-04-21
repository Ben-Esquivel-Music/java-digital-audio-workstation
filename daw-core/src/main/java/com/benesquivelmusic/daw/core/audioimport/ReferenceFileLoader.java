package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Thin public wrapper around the format-specific readers in this package.
 *
 * <p>Used by plugins (e.g. the built-in Match EQ) that need to load a one-off
 * audio file as a {@code float[][]} buffer for analysis, without going through
 * the full {@link AudioFileImporter} project-import pipeline (which creates
 * tracks and clips).</p>
 */
public final class ReferenceFileLoader {

    private ReferenceFileLoader() {
        // utility class
    }

    /**
     * Reads the given audio file and returns its decoded PCM payload.
     *
     * <p>Dispatches to the matching reader based on the file extension via
     * {@link SupportedAudioFormat#fromPath(Path)}. No sample-rate conversion
     * is performed — callers that need a specific rate must convert the
     * returned data themselves.</p>
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
}
