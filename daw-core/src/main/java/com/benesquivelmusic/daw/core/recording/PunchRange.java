package com.benesquivelmusic.daw.core.recording;

/**
 * Defines a punch-in/punch-out region on the timeline for recording.
 *
 * <p>When punch recording is active, audio is only captured between
 * {@link #punchInBeat()} and {@link #punchOutBeat()}. The transport
 * plays normally outside this range, but recording is only active
 * within it.</p>
 *
 * @param punchInBeat  the beat position where recording starts (punch-in)
 * @param punchOutBeat the beat position where recording stops (punch-out)
 */
public record PunchRange(double punchInBeat, double punchOutBeat) {

    public PunchRange {
        if (punchInBeat < 0) {
            throw new IllegalArgumentException(
                    "punchInBeat must not be negative: " + punchInBeat);
        }
        if (punchOutBeat <= punchInBeat) {
            throw new IllegalArgumentException(
                    "punchOutBeat must be greater than punchInBeat: punchIn="
                            + punchInBeat + ", punchOut=" + punchOutBeat);
        }
    }

    /**
     * Returns the duration of the punch range in beats.
     *
     * @return the punch range duration in beats
     */
    public double durationBeats() {
        return punchOutBeat - punchInBeat;
    }

    /**
     * Returns whether the given beat position falls within this punch range.
     *
     * @param beat the beat position to test
     * @return {@code true} if the position is within the punch range
     */
    public boolean contains(double beat) {
        return beat >= punchInBeat && beat < punchOutBeat;
    }
}
