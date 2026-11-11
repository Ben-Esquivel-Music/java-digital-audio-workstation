package com.benesquivelmusic.daw.core.transport;

/**
 * Represents a time signature change at a specific position on the timeline.
 *
 * <p>Each event specifies the beat position where the change takes effect,
 * along with the new numerator (beats per bar) and denominator (note value
 * of each beat).</p>
 *
 * @param positionInBeats the beat position where this time signature change occurs (finite and &ge; 0)
 * @param numerator       beats per bar (must be positive)
 * @param denominator     note value of each beat (must be positive)
 */
public record TimeSignatureChangeEvent(double positionInBeats, int numerator, int denominator)
        implements Comparable<TimeSignatureChangeEvent> {

    /**
     * Creates a time signature change event with validation: a position that
     * is not finite is refused like a negative one (story 334, studio ruling
     * E10.5).
     */
    public TimeSignatureChangeEvent {
        if (!Double.isFinite(positionInBeats) || positionInBeats < 0) {
            throw new IllegalArgumentException(
                    "positionInBeats must be finite and not negative: " + positionInBeats);
        }
        if (numerator <= 0) {
            throw new IllegalArgumentException(
                    "numerator must be positive: " + numerator);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException(
                    "denominator must be positive: " + denominator);
        }
    }

    /**
     * Orders time signature change events by their beat position.
     */
    @Override
    public int compareTo(TimeSignatureChangeEvent other) {
        return Double.compare(this.positionInBeats, other.positionInBeats);
    }
}
