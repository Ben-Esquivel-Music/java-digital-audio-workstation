package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Platform-specific and genre-specific loudness target presets.
 *
 * <p>Each preset defines a target integrated loudness (LUFS) and
 * a maximum true-peak level (dBTP) for export validation. Platform
 * presets reflect the loudness normalization targets used by major
 * streaming services. Genre presets provide reference loudness ranges
 * commonly used in professional mastering.</p>
 *
 * <p>Sources: mastering-techniques research document §8 — "LUFS
 * (Loudness Units Full Scale) — integrated, short-term, and momentary"
 * and "Platform targets: Spotify −14 LUFS, Apple Music −16 LUFS,
 * YouTube −14 LUFS".</p>
 */
public enum LoudnessTarget {

    // ---- Platform presets ----

    /** Spotify loudness normalization target. */
    SPOTIFY("Spotify", -14.0, -1.0),

    /** Apple Music / iTunes Sound Check target. */
    APPLE_MUSIC("Apple Music", -16.0, -1.0),

    /** YouTube loudness normalization target. */
    YOUTUBE("YouTube", -14.0, -1.0),

    /** Amazon Music loudness normalization target. */
    AMAZON_MUSIC("Amazon Music", -14.0, -2.0),

    /** Tidal loudness normalization target. */
    TIDAL("Tidal", -14.0, -1.0),

    /** EBU R128 broadcast loudness target. */
    BROADCAST("Broadcast", -23.0, -1.0),

    /** CD mastering reference (typical loud master). */
    CD("CD", -9.0, -0.3),

    // ---- Genre reference presets ----

    /** Pop / EDM typical mastering loudness. */
    GENRE_POP_EDM("Pop/EDM", -9.0, -1.0),

    /** Rock typical mastering loudness. */
    GENRE_ROCK("Rock", -11.0, -1.0),

    /** Jazz / Classical typical mastering loudness. */
    GENRE_JAZZ_CLASSICAL("Jazz/Classical", -18.0, -1.0),

    /** Hip-Hop / R&B typical mastering loudness. */
    GENRE_HIPHOP_RNB("Hip-Hop/R&B", -10.0, -1.0);

    private final String displayName;
    private final double targetIntegratedLufs;
    private final double maxTruePeakDbtp;

    LoudnessTarget(String displayName, double targetIntegratedLufs, double maxTruePeakDbtp) {
        this.displayName = displayName;
        this.targetIntegratedLufs = targetIntegratedLufs;
        this.maxTruePeakDbtp = maxTruePeakDbtp;
    }

    /**
     * Returns a human-readable display name for this target.
     *
     * @return display name (e.g. "Spotify", "Apple Music")
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the target integrated loudness in LUFS.
     *
     * @return target integrated loudness
     */
    public double targetIntegratedLufs() {
        return targetIntegratedLufs;
    }

    /**
     * Returns the maximum allowable true-peak level in dBTP.
     *
     * @return maximum true-peak level
     */
    public double maxTruePeakDbtp() {
        return maxTruePeakDbtp;
    }
}
