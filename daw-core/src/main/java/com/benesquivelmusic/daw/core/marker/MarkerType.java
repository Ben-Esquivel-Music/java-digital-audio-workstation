package com.benesquivelmusic.daw.core.marker;

/**
 * Enumerates the types of markers that can be placed on the timeline.
 *
 * <p>Each type has a default color used for visual identification on the
 * timeline ruler and in the marker list panel.</p>
 */
public enum MarkerType {

    /** A song section marker (e.g., Verse, Chorus, Bridge). */
    SECTION("#3498DB"),

    /** A rehearsal marker (e.g., rehearsal letters A, B, C). */
    REHEARSAL("#E67E22"),

    /** An arrangement marker for structural arrangement decisions. */
    ARRANGEMENT("#2ECC71");

    private final String defaultColor;

    MarkerType(String defaultColor) {
        this.defaultColor = defaultColor;
    }

    /**
     * Returns the default hex color string for this marker type.
     *
     * @return the default color (e.g., {@code "#3498DB"})
     */
    public String getDefaultColor() {
        return defaultColor;
    }
}
