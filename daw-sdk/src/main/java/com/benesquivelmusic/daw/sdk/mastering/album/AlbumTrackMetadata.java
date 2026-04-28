package com.benesquivelmusic.daw.sdk.mastering.album;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-track metadata for album assembly and DDP / digital distribution.
 *
 * <p>Carries the textual fields required by replication plants and digital
 * distributors:</p>
 * <ul>
 *     <li>{@code title} — track title (required, non-blank)</li>
 *     <li>{@code artist} — track-level artist (may differ from album artist
 *         on a compilation; may be {@code null} to inherit the album artist)</li>
 *     <li>{@code composer} — composer / writer credit (may be {@code null})</li>
 *     <li>{@code isrc} — ISO 3901 ISRC in canonical {@code CC-XXX-YY-NNNNN}
 *         form, validated by {@link IsrcValidator}; may be {@code null} when
 *         a track is still being assembled</li>
 *     <li>{@code cdText} — optional CD-Text fields (songwriter, arranger,
 *         message, UPC/EAN); see {@link CdText}</li>
 *     <li>{@code extra} — distributor-specific free-form key/value tags
 *         (e.g. {@code "explicit"="false"}, {@code "language"="en"})</li>
 * </ul>
 *
 * <p>Records are deeply immutable: the backing {@code extra} map is wrapped
 * via {@link Collections#unmodifiableMap} on construction and {@code null}
 * is canonicalised to an empty map.</p>
 *
 * @param title    the track title (required)
 * @param artist   the track-level artist (may be {@code null})
 * @param composer the composer (may be {@code null})
 * @param isrc     the ISRC (canonical form, may be {@code null})
 * @param cdText   the optional CD-Text record (never {@code null}; use
 *                 {@link Optional#empty()} for none)
 * @param extra    distributor-specific key/value tags (never {@code null})
 */
public record AlbumTrackMetadata(
        String title,
        String artist,
        String composer,
        String isrc,
        Optional<CdText> cdText,
        Map<String, String> extra
) {

    public AlbumTrackMetadata {
        Objects.requireNonNull(title, "title must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        Objects.requireNonNull(cdText, "cdText must not be null (use Optional.empty())");
        // Normalize non-empty ISRCs to canonical CC-XXX-YY-NNNNN form.
        if (isrc != null && !isrc.isEmpty()) {
            isrc = IsrcValidator.normalize(isrc);
        }
        // Defensive copy so callers cannot mutate the backing map.
        extra = (extra == null || extra.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /**
     * Creates a track metadata record with only a title.
     *
     * @param title the track title
     * @return a new metadata record
     */
    public static AlbumTrackMetadata ofTitle(String title) {
        return new AlbumTrackMetadata(title, null, null, null, Optional.empty(), Map.of());
    }

    /**
     * Returns a copy with the given artist.
     *
     * @param newArtist the artist (may be {@code null})
     * @return a new metadata record with the updated artist
     */
    public AlbumTrackMetadata withArtist(String newArtist) {
        return new AlbumTrackMetadata(title, newArtist, composer, isrc, cdText, extra);
    }

    /**
     * Returns a copy with the given composer.
     *
     * @param newComposer the composer (may be {@code null})
     * @return a new metadata record with the updated composer
     */
    public AlbumTrackMetadata withComposer(String newComposer) {
        return new AlbumTrackMetadata(title, artist, newComposer, isrc, cdText, extra);
    }

    /**
     * Returns a copy with the given ISRC. The ISRC must be a valid
     * ISO 3901 code or {@code null}; it is normalized to the canonical
     * {@code CC-XXX-YY-NNNNN} form before storage.
     *
     * @param newIsrc the ISRC (may be {@code null})
     * @return a new metadata record with the updated ISRC
     * @throws IllegalArgumentException if {@code newIsrc} is non-null and invalid
     */
    public AlbumTrackMetadata withIsrc(String newIsrc) {
        String stored = (newIsrc == null || newIsrc.isEmpty())
                ? null
                : IsrcValidator.normalize(newIsrc);
        return new AlbumTrackMetadata(title, artist, composer, stored, cdText, extra);
    }

    /**
     * Returns a copy with the given CD-Text record.
     *
     * @param newCdText the CD-Text record (use {@link Optional#empty()} to clear)
     * @return a new metadata record with the updated CD-Text
     */
    public AlbumTrackMetadata withCdText(Optional<CdText> newCdText) {
        return new AlbumTrackMetadata(title, artist, composer, isrc, newCdText, extra);
    }

    /**
     * Returns a copy with the given extra-tag map (overwriting any prior tags).
     *
     * @param newExtra the new tag map (may be {@code null} to clear)
     * @return a new metadata record with the updated tags
     */
    public AlbumTrackMetadata withExtra(Map<String, String> newExtra) {
        return new AlbumTrackMetadata(title, artist, composer, isrc, cdText, newExtra);
    }

    /**
     * Returns a copy with one extra tag added or replaced.
     *
     * @param key   the tag key (must not be {@code null} or empty)
     * @param value the tag value (must not be {@code null})
     * @return a new metadata record with the additional tag
     */
    public AlbumTrackMetadata withExtraTag(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        Objects.requireNonNull(value, "value must not be null");
        Map<String, String> merged = new LinkedHashMap<>(extra);
        merged.put(key, value);
        return new AlbumTrackMetadata(title, artist, composer, isrc, cdText, merged);
    }
}
