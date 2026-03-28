package com.benesquivelmusic.daw.core.recording;

/**
 * Defines the metronome subdivision level.
 *
 * <p>In addition to clicking on each beat, the metronome can emit
 * quieter clicks on subdivisions within each beat. This helps
 * performers lock into faster rhythmic patterns.</p>
 */
public enum Subdivision {

    /** Click only on the main beats (quarter notes in 4/4 time). */
    QUARTER(1),

    /** Click on eighth-note subdivisions (2 clicks per beat). */
    EIGHTH(2),

    /** Click on sixteenth-note subdivisions (4 clicks per beat). */
    SIXTEENTH(4);

    private final int clicksPerBeat;

    Subdivision(int clicksPerBeat) {
        this.clicksPerBeat = clicksPerBeat;
    }

    /**
     * Returns the number of clicks per beat for this subdivision.
     *
     * @return the number of clicks per beat
     */
    public int getClicksPerBeat() {
        return clicksPerBeat;
    }
}
