package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads MP3 (MPEG Audio Layer III) files and decodes them into normalized
 * {@code float[][]} arrays.
 *
 * <p>Uses the {@link javax.sound.sampled.AudioSystem} framework to decode
 * MP3 files. This requires an MP3 SPI on the classpath (e.g.
 * {@code mp3spi} or {@code javalayer}). If no compatible SPI is installed,
 * the reader throws {@link IOException} with a descriptive message.</p>
 */
public final class Mp3FileReader {

    private Mp3FileReader() {
        // utility class
    }

    /**
     * Reads an MP3 file and returns the decoded audio data.
     *
     * @param path the path to the MP3 file
     * @return the decoded audio result containing samples and format info
     * @throws IOException              if an I/O error occurs or MP3 decoding is
     *                                  not supported in this environment
     * @throws IllegalArgumentException if the file is not a valid MP3 file
     */
    public static AudioReadResult read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }

        return AudioSystemDecoder.decode(path, "MP3");
    }
}
