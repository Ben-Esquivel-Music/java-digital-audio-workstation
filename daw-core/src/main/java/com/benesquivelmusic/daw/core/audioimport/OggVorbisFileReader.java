package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Reads OGG Vorbis audio files and decodes them into normalized
 * {@code float[][]} arrays.
 *
 * <p>Uses the {@link javax.sound.sampled.AudioSystem} framework to decode
 * OGG Vorbis files. This requires a Vorbis SPI on the classpath (e.g.
 * {@code vorbisspi}). If no compatible SPI is installed, the reader throws
 * {@link IOException} with a descriptive message.</p>
 */
public final class OggVorbisFileReader {

    private OggVorbisFileReader() {
        // utility class
    }

    /**
     * Reads an OGG Vorbis file and returns the decoded audio data.
     *
     * @param path the path to the OGG file
     * @return the decoded audio result containing samples and format info
     * @throws IOException              if an I/O error occurs or OGG decoding is
     *                                  not supported in this environment
     * @throws IllegalArgumentException if the file is not a valid OGG file
     */
    public static AudioReadResult read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }

        return AudioSystemDecoder.decode(path, "OGG Vorbis");
    }
}
