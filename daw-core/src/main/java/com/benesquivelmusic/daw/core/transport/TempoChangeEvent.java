package com.benesquivelmusic.daw.core.transport;

/**
 * Represents a tempo change at a specific position on the timeline.
 *
 * <p>Each event specifies the beat position where the change takes effect,
 * the new tempo in BPM, and the type of transition from the preceding tempo.</p>
 *
 * @param positionInBeats the beat position where this tempo change occurs (must be &ge; 0)
 * @param bpm             the new tempo in beats per minute (must be between 20 and 999)
 * @param transitionType  how the tempo transitions from the previous value
 */
public record TempoChangeEvent(double positionInBeats, double bpm, TempoTransitionType transitionType)
        implements Comparable<TempoChangeEvent> {

    /**
     * Creates a tempo change event with validation.
     */
    public TempoChangeEvent {
        if (positionInBeats < 0) {
            throw new IllegalArgumentException(
                    "positionInBeats must not be negative: " + positionInBeats);
        }
        if (bpm < 20.0 || bpm > 999.0) {
            throw new IllegalArgumentException(
                    "bpm must be between 20 and 999: " + bpm);
        }
        if (transitionType == null) {
            throw new NullPointerException("transitionType must not be null");
        }
    }

    /**
     * Creates an instant tempo change event at the given position.
     *
     * @param positionInBeats the beat position
     * @param bpm             the new tempo
     * @return a new {@code TempoChangeEvent} with {@link TempoTransitionType#INSTANT}
     */
    public static TempoChangeEvent instant(double positionInBeats, double bpm) {
        return new TempoChangeEvent(positionInBeats, bpm, TempoTransitionType.INSTANT);
    }

    /**
     * Creates a linear tempo change event at the given position.
     *
     * @param positionInBeats the beat position
     * @param bpm             the new tempo
     * @return a new {@code TempoChangeEvent} with {@link TempoTransitionType#LINEAR}
     */
    public static TempoChangeEvent linear(double positionInBeats, double bpm) {
        return new TempoChangeEvent(positionInBeats, bpm, TempoTransitionType.LINEAR);
    }

    /**
     * Creates a curved tempo change event at the given position.
     *
     * @param positionInBeats the beat position
     * @param bpm             the new tempo
     * @return a new {@code TempoChangeEvent} with {@link TempoTransitionType#CURVED}
     */
    public static TempoChangeEvent curved(double positionInBeats, double bpm) {
        return new TempoChangeEvent(positionInBeats, bpm, TempoTransitionType.CURVED);
    }

    /**
     * Orders tempo change events by their beat position.
     */
    @Override
    public int compareTo(TempoChangeEvent other) {
        return Double.compare(this.positionInBeats, other.positionInBeats);
    }
}
