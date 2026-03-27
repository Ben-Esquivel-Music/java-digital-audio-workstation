package com.benesquivelmusic.daw.core.recording;

/**
 * Configures the count-in (pre-roll) before recording begins.
 *
 * <p>When a count-in mode other than {@link #OFF} is selected, an audible
 * metronome click is played for the specified number of bars before the
 * transport starts recording. This gives the performer time to prepare.</p>
 */
public enum CountInMode {

    /** No count-in; recording starts immediately. */
    OFF(0),

    /** One bar of count-in clicks before recording. */
    ONE_BAR(1),

    /** Two bars of count-in clicks before recording. */
    TWO_BARS(2),

    /** Four bars of count-in clicks before recording. */
    FOUR_BARS(4);

    private final int bars;

    CountInMode(int bars) {
        this.bars = bars;
    }

    /**
     * Returns the number of bars to count in.
     *
     * @return the bar count (0 for {@link #OFF})
     */
    public int getBars() {
        return bars;
    }

    /**
     * Computes the total number of count-in beats for the given time
     * signature numerator (beats per bar).
     *
     * @param beatsPerBar the number of beats per bar (time signature numerator)
     * @return the total count-in beats
     */
    public int getTotalBeats(int beatsPerBar) {
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("beatsPerBar must be positive: " + beatsPerBar);
        }
        return bars * beatsPerBar;
    }
}
