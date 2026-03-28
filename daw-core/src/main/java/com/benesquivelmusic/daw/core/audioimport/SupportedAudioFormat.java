package com.benesquivelmusic.daw.core.audioimport;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Enumerates the audio file formats supported for import.
 */
public enum SupportedAudioFormat {

    /** Waveform Audio File Format. */
    WAV("wav"),

    /** Free Lossless Audio Codec. */
    FLAC("flac"),

    /** MPEG Audio Layer III. */
    MP3("mp3"),

    /** Audio Interchange File Format. */
    AIFF("aiff"),

    /** Ogg Vorbis audio. */
    OGG("ogg");

    private final String extension;

    SupportedAudioFormat(String extension) {
        this.extension = extension;
    }

    /** Returns the canonical file extension (without dot). */
    public String getExtension() {
        return extension;
    }

    /**
     * Determines the audio format from a file path based on its extension.
     *
     * @param path the file path to examine
     * @return the matching format, or empty if the extension is not supported
     */
    public static Optional<SupportedAudioFormat> fromPath(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return Optional.empty();
        }
        String ext = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        for (SupportedAudioFormat format : values()) {
            if (format.extension.equals(ext)) {
                return Optional.of(format);
            }
        }
        // Handle .aif as an alias for AIFF
        if ("aif".equals(ext)) {
            return Optional.of(AIFF);
        }
        return Optional.empty();
    }

    /**
     * Returns whether the given file path has a supported audio file extension.
     *
     * @param path the file path to check
     * @return {@code true} if the file extension matches a supported format
     */
    public static boolean isSupported(Path path) {
        return fromPath(path).isPresent();
    }
}
