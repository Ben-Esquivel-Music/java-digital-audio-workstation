package com.benesquivelmusic.daw.sdk.export;

/**
 * Metadata to embed in exported audio files.
 *
 * @param title  the track title (may be {@code null})
 * @param artist the artist name (may be {@code null})
 * @param album  the album name (may be {@code null})
 * @param isrc   the International Standard Recording Code (may be {@code null})
 */
public record AudioMetadata(
        String title,
        String artist,
        String album,
        String isrc
) {

    /** Empty metadata — no fields set. */
    public static final AudioMetadata EMPTY = new AudioMetadata(null, null, null, null);
}
