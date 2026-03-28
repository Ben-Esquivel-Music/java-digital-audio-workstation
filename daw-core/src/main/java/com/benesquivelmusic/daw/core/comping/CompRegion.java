package com.benesquivelmusic.daw.core.comping;

/**
 * A selected region within a take lane that contributes to the composite.
 *
 * <p>A comp region defines a beat range {@code [startBeat, startBeat + durationBeats)}
 * on a specific take lane whose audio should be included in the compiled
 * composite clip on the main track lane.</p>
 *
 * @param takeIndex     the zero-based index of the take lane this region belongs to
 * @param startBeat     the start position in beats on the timeline
 * @param durationBeats the duration of the region in beats (must be positive)
 */
public record CompRegion(int takeIndex, double startBeat, double durationBeats) {

    public CompRegion {
        if (takeIndex < 0) {
            throw new IllegalArgumentException("takeIndex must not be negative: " + takeIndex);
        }
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        if (durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be positive: " + durationBeats);
        }
    }

    /**
     * Returns the end beat position (start + duration).
     *
     * @return the end beat
     */
    public double endBeat() {
        return startBeat + durationBeats;
    }

    /**
     * Returns whether this region overlaps with the given beat range.
     *
     * @param otherStart the start of the other range
     * @param otherEnd   the end of the other range
     * @return {@code true} if the ranges overlap
     */
    public boolean overlaps(double otherStart, double otherEnd) {
        return startBeat < otherEnd && endBeat() > otherStart;
    }
}
