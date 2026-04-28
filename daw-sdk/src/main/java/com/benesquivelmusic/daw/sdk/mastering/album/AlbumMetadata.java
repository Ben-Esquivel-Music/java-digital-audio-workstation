package com.benesquivelmusic.daw.sdk.mastering.album;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Album-level metadata used during album assembly and DDP export.
 *
 * <p>This record carries the fields that apply to the album as a whole
 * (title, artist, year, genre, UPC/EAN, release date), as opposed to the
 * per-track {@link AlbumTrackMetadata}.</p>
 *
 * <p>The {@code upcEan} field, when present, must be a 12-digit UPC-A
 * or 13-digit EAN-13 code. Validation is performed in the compact
 * constructor.</p>
 *
 * @param title       the album title (required, non-blank)
 * @param artist      the primary album artist (required, non-blank)
 * @param year        the release year (e.g. {@code 2026}); must be in
 *                    {@code [1877, 2200]} (1877 = first phonograph) or
 *                    {@code 0} for unspecified
 * @param genre       the genre tag (may be {@code null})
 * @param upcEan      the album UPC-A (12 digits) or EAN-13 (13 digits) code
 *                    (may be {@code null})
 * @param releaseDate the release date (may be empty)
 */
public record AlbumMetadata(
        String title,
        String artist,
        int year,
        String genre,
        String upcEan,
        Optional<LocalDate> releaseDate
) {

    /** UPC-A (12 digits) or EAN-13 (13 digits) syntactic pattern. */
    private static final Pattern UPC_EAN = Pattern.compile("\\d{12,13}");

    /** Earliest plausible release year (Edison's phonograph, 1877). */
    public static final int MIN_YEAR = 1877;

    /** Latest plausible release year. */
    public static final int MAX_YEAR = 2200;

    public AlbumMetadata {
        Objects.requireNonNull(title, "title must not be null");
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        Objects.requireNonNull(artist, "artist must not be null");
        if (artist.isEmpty()) {
            throw new IllegalArgumentException("artist must not be empty");
        }
        if (year != 0 && (year < MIN_YEAR || year > MAX_YEAR)) {
            throw new IllegalArgumentException(
                    "year must be 0 or in [" + MIN_YEAR + ", " + MAX_YEAR + "]: " + year);
        }
        if (upcEan != null && !upcEan.isEmpty() && !UPC_EAN.matcher(upcEan).matches()) {
            throw new IllegalArgumentException(
                    "upcEan must be 12 (UPC-A) or 13 (EAN-13) digits: " + upcEan);
        }
        Objects.requireNonNull(releaseDate, "releaseDate must not be null (use Optional.empty())");
    }

    /**
     * Creates an album metadata record with only the title and artist
     * specified; all other fields default to "unknown".
     *
     * @param title  the album title
     * @param artist the album artist
     * @return a new album metadata record
     */
    public static AlbumMetadata of(String title, String artist) {
        return new AlbumMetadata(title, artist, 0, null, null, Optional.empty());
    }

    /**
     * Returns a copy with the given title.
     *
     * @param newTitle the title
     * @return a new metadata record
     */
    public AlbumMetadata withTitle(String newTitle) {
        return new AlbumMetadata(newTitle, artist, year, genre, upcEan, releaseDate);
    }

    /**
     * Returns a copy with the given artist.
     *
     * @param newArtist the artist
     * @return a new metadata record
     */
    public AlbumMetadata withArtist(String newArtist) {
        return new AlbumMetadata(title, newArtist, year, genre, upcEan, releaseDate);
    }

    /**
     * Returns a copy with the given year.
     *
     * @param newYear the year (or {@code 0} for unspecified)
     * @return a new metadata record
     */
    public AlbumMetadata withYear(int newYear) {
        return new AlbumMetadata(title, artist, newYear, genre, upcEan, releaseDate);
    }

    /**
     * Returns a copy with the given genre.
     *
     * @param newGenre the genre (may be {@code null})
     * @return a new metadata record
     */
    public AlbumMetadata withGenre(String newGenre) {
        return new AlbumMetadata(title, artist, year, newGenre, upcEan, releaseDate);
    }

    /**
     * Returns a copy with the given UPC/EAN code.
     *
     * @param newUpcEan the UPC-A or EAN-13 code (may be {@code null})
     * @return a new metadata record
     * @throws IllegalArgumentException if {@code newUpcEan} is non-null and not 12/13 digits
     */
    public AlbumMetadata withUpcEan(String newUpcEan) {
        return new AlbumMetadata(title, artist, year, genre, newUpcEan, releaseDate);
    }

    /**
     * Returns a copy with the given release date.
     *
     * @param newDate the release date (use {@link Optional#empty()} to clear)
     * @return a new metadata record
     */
    public AlbumMetadata withReleaseDate(Optional<LocalDate> newDate) {
        return new AlbumMetadata(title, artist, year, genre, upcEan, newDate);
    }
}
