package com.benesquivelmusic.daw.sdk.mastering;

import java.util.Objects;

/**
 * Metadata for a single track within an album sequence.
 *
 * @param title             the track title
 * @param isrc              the International Standard Recording Code (may be {@code null})
 * @param durationSeconds   the track duration in seconds
 * @param preGapSeconds     the silent gap before this track (0–10 seconds)
 * @param crossfadeDuration the crossfade duration in seconds with the previous track (0 = no crossfade)
 * @param crossfadeCurve    the crossfade curve type
 */
public record AlbumTrackEntry(
        String title,
        String isrc,
        double durationSeconds,
        double preGapSeconds,
        double crossfadeDuration,
        CrossfadeCurve crossfadeCurve
) {

    /** Maximum allowed pre-gap duration in seconds. */
    public static final double MAX_PRE_GAP_SECONDS = 10.0;

    /** Default pre-gap duration in seconds. */
    public static final double DEFAULT_PRE_GAP_SECONDS = 2.0;

    public AlbumTrackEntry {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(crossfadeCurve, "crossfadeCurve must not be null");
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive: " + durationSeconds);
        }
        if (preGapSeconds < 0 || preGapSeconds > MAX_PRE_GAP_SECONDS) {
            throw new IllegalArgumentException(
                    "preGapSeconds must be in [0, " + MAX_PRE_GAP_SECONDS + "]: " + preGapSeconds);
        }
        if (crossfadeDuration < 0) {
            throw new IllegalArgumentException(
                    "crossfadeDuration must be >= 0: " + crossfadeDuration);
        }
    }

    /**
     * Creates a track entry with default gap and no crossfade.
     *
     * @param title           the track title
     * @param durationSeconds the duration in seconds
     * @return a new album track entry
     */
    public static AlbumTrackEntry of(String title, double durationSeconds) {
        return new AlbumTrackEntry(title, null, durationSeconds,
                DEFAULT_PRE_GAP_SECONDS, 0.0, CrossfadeCurve.LINEAR);
    }

    /**
     * Returns a copy of this entry with an updated pre-gap duration.
     *
     * @param newPreGapSeconds the new pre-gap in seconds
     * @return a new entry with the updated gap
     */
    public AlbumTrackEntry withPreGapSeconds(double newPreGapSeconds) {
        return new AlbumTrackEntry(title, isrc, durationSeconds,
                newPreGapSeconds, crossfadeDuration, crossfadeCurve);
    }

    /**
     * Returns a copy of this entry with an updated crossfade configuration.
     *
     * @param newDuration the crossfade duration in seconds
     * @param newCurve    the crossfade curve type
     * @return a new entry with the updated crossfade
     */
    public AlbumTrackEntry withCrossfade(double newDuration, CrossfadeCurve newCurve) {
        return new AlbumTrackEntry(title, isrc, durationSeconds,
                preGapSeconds, newDuration, newCurve);
    }
}
