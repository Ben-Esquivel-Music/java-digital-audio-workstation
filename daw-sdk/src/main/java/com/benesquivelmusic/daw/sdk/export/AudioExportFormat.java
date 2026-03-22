package com.benesquivelmusic.daw.sdk.export;

/**
 * Supported audio export file formats.
 *
 * <p>Each format specifies whether it supports lossless encoding and
 * a human-readable file extension used when generating output filenames.</p>
 */
public enum AudioExportFormat {

    /** Waveform Audio File Format — uncompressed PCM. */
    WAV("wav", true),

    /** Free Lossless Audio Codec. */
    FLAC("flac", true),

    /** Ogg Vorbis — lossy, open-source. */
    OGG("ogg", false),

    /** MPEG-1 Audio Layer III — lossy. */
    MP3("mp3", false),

    /** Advanced Audio Coding — lossy. */
    AAC("aac", false);

    private final String fileExtension;
    private final boolean lossless;

    AudioExportFormat(String fileExtension, boolean lossless) {
        this.fileExtension = fileExtension;
        this.lossless = lossless;
    }

    /**
     * Returns the standard file extension (without leading dot).
     *
     * @return file extension (e.g. "wav", "flac")
     */
    public String fileExtension() {
        return fileExtension;
    }

    /**
     * Returns {@code true} if this format preserves the full audio fidelity.
     *
     * @return whether the format is lossless
     */
    public boolean isLossless() {
        return lossless;
    }
}
