package com.benesquivelmusic.daw.sdk.mastering.album;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateless helpers that batch-update lists of {@link AlbumTrackMetadata}.
 *
 * <p>These power the "Auto-fill" buttons on the album metadata pane:</p>
 * <ul>
 *     <li>{@link #propagateArtist(List, String)} — set every track's artist
 *         to a single value</li>
 *     <li>{@link #propagateComposer(List, String)} — set every track's
 *         composer to a single value</li>
 *     <li>{@link #autoGenerateIsrcSequence(List, String)} — assign the
 *         given ISRC to the first track and increment the designation code
 *         for every subsequent track (ISO 3901)</li>
 * </ul>
 *
 * <p>All methods return new immutable lists; they never mutate their
 * inputs.</p>
 */
public final class AlbumMetadataHelpers {

    private AlbumMetadataHelpers() {
        // utility class
    }

    /**
     * Returns a copy of {@code tracks} with every track's artist replaced
     * by {@code artist}.
     *
     * @param tracks the original track metadata list
     * @param artist the artist name to broadcast (may be {@code null} to clear)
     * @return a new list with artists propagated
     */
    public static List<AlbumTrackMetadata> propagateArtist(
            List<AlbumTrackMetadata> tracks, String artist) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        List<AlbumTrackMetadata> out = new ArrayList<>(tracks.size());
        for (AlbumTrackMetadata t : tracks) {
            out.add(t.withArtist(artist));
        }
        return List.copyOf(out);
    }

    /**
     * Returns a copy of {@code tracks} with every track's composer replaced
     * by {@code composer}.
     *
     * @param tracks   the original track metadata list
     * @param composer the composer name to broadcast (may be {@code null} to clear)
     * @return a new list with composers propagated
     */
    public static List<AlbumTrackMetadata> propagateComposer(
            List<AlbumTrackMetadata> tracks, String composer) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        List<AlbumTrackMetadata> out = new ArrayList<>(tracks.size());
        for (AlbumTrackMetadata t : tracks) {
            out.add(t.withComposer(composer));
        }
        return List.copyOf(out);
    }

    /**
     * Assigns ISRCs to every track by starting from {@code firstIsrc} and
     * incrementing the 5-digit designation code for each subsequent track,
     * preserving the country, registrant, and year segments.
     *
     * <p>For example, given {@code firstIsrc = "US-RC1-26-00042"} and three
     * tracks, the resulting ISRCs are
     * {@code US-RC1-26-00042}, {@code US-RC1-26-00043}, {@code US-RC1-26-00044}.</p>
     *
     * @param tracks    the original track metadata list
     * @param firstIsrc the ISRC for the first track (any accepted form)
     * @return a new list with sequential ISRCs assigned
     * @throws IllegalArgumentException if {@code firstIsrc} is not a valid ISRC
     *                                  or the sequence would overflow
     */
    public static List<AlbumTrackMetadata> autoGenerateIsrcSequence(
            List<AlbumTrackMetadata> tracks, String firstIsrc) {
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(firstIsrc, "firstIsrc must not be null");
        if (tracks.isEmpty()) {
            return List.of();
        }
        List<AlbumTrackMetadata> out = new ArrayList<>(tracks.size());
        String current = IsrcValidator.normalize(firstIsrc);
        out.add(tracks.get(0).withIsrc(current));
        for (int i = 1; i < tracks.size(); i++) {
            current = IsrcValidator.next(current);
            out.add(tracks.get(i).withIsrc(current));
        }
        return List.copyOf(out);
    }
}
