package com.benesquivelmusic.daw.sdk.mastering.album;

/**
 * CD-Text metadata fields for a single track.
 *
 * <p>CD-Text is an extension of the Red Book CD specification that allows
 * embedding textual metadata directly on the disc. This record carries the
 * fields that supplement {@link AlbumTrackMetadata#title()},
 * {@link AlbumTrackMetadata#artist()}, and {@link AlbumTrackMetadata#composer()}
 * which are typically promoted to the matching CD-Text packs ({@code TITLE},
 * {@code PERFORMER}, {@code COMPOSER}).</p>
 *
 * <p>The {@code upcEan} field carries the album-level Universal Product
 * Code / European Article Number; it is exposed here so distributors that
 * consume per-track CD-Text records can read it from any track.</p>
 *
 * @param songwriter the songwriter name (CD-Text {@code SONGWRITER} pack); may be {@code null}
 * @param arranger   the arranger / orchestrator name (CD-Text {@code ARRANGER} pack); may be {@code null}
 * @param message    a free-form message embedded in the disc (CD-Text {@code MESSAGE} pack); may be {@code null}
 * @param upcEan     the album UPC/EAN code (CD-Text {@code UPC_EAN} pack); may be {@code null}
 */
public record CdText(
        String songwriter,
        String arranger,
        String message,
        String upcEan
) {

    /** Empty CD-Text — no fields set. */
    public static final CdText EMPTY = new CdText(null, null, null, null);

    /**
     * Returns {@code true} if every field is {@code null} or empty.
     *
     * @return whether this CD-Text record carries no information
     */
    public boolean isEmpty() {
        return isBlank(songwriter)
                && isBlank(arranger)
                && isBlank(message)
                && isBlank(upcEan);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
