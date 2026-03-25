package com.benesquivelmusic.daw.app.ui;

/**
 * Identifies the available grid/snap resolutions for the arrangement and editor views.
 *
 * <p>Each constant represents a musical subdivision that determines how clips,
 * notes, and other events align when snap-to-grid is enabled. Triplet variants
 * divide the corresponding straight value into three equal parts.</p>
 */
public enum GridResolution {

    /** Full bar (measure). */
    BAR("Bar"),

    /** Half note (1/2). */
    HALF("1/2"),

    /** Quarter note (1/4). */
    QUARTER("1/4"),

    /** Eighth note (1/8). */
    EIGHTH("1/8"),

    /** Sixteenth note (1/16). */
    SIXTEENTH("1/16"),

    /** Thirty-second note (1/32). */
    THIRTY_SECOND("1/32"),

    /** Half-note triplet (1/2T). */
    HALF_TRIPLET("1/2T"),

    /** Quarter-note triplet (1/4T). */
    QUARTER_TRIPLET("1/4T"),

    /** Eighth-note triplet (1/8T). */
    EIGHTH_TRIPLET("1/8T"),

    /** Sixteenth-note triplet (1/16T). */
    SIXTEENTH_TRIPLET("1/16T");

    private final String displayName;

    GridResolution(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this grid resolution.
     *
     * @return the display name (e.g. "1/4", "1/8T")
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the grid size in beats for this resolution.
     *
     * <p>Straight subdivisions are fractions of a whole note (4 beats in common
     * time). Triplet variants divide the corresponding straight value into three
     * equal parts. The {@link #BAR} resolution uses the given
     * {@code beatsPerBar} directly.</p>
     *
     * @param beatsPerBar the number of beats in a bar (e.g. 4 for 4/4 time)
     * @return the grid size in beats
     * @throws IllegalArgumentException if {@code beatsPerBar} is not positive
     */
    public double beatsPerGrid(int beatsPerBar) {
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("beatsPerBar must be positive: " + beatsPerBar);
        }
        return switch (this) {
            case BAR -> beatsPerBar;
            case HALF -> 2.0;
            case QUARTER -> 1.0;
            case EIGHTH -> 0.5;
            case SIXTEENTH -> 0.25;
            case THIRTY_SECOND -> 0.125;
            case HALF_TRIPLET -> 2.0 / 3.0;
            case QUARTER_TRIPLET -> 1.0 / 3.0;
            case EIGHTH_TRIPLET -> 0.5 / 3.0;
            case SIXTEENTH_TRIPLET -> 0.25 / 3.0;
        };
    }
}
