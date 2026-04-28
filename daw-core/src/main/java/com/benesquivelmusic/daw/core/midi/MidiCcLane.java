package com.benesquivelmusic.daw.core.midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One control-change editing lane belonging to a {@link MidiClip}.
 *
 * <p>A lane has a {@link MidiCcLaneType type}, a CC number (only meaningful
 * for {@link MidiCcLaneType#ARBITRARY_CC} — the other types use their
 * conventional default), an optional 14-bit "high resolution" mode, an
 * MIDI channel, a relative {@link #getHeightRatio() height ratio} for
 * stacking multiple lanes vertically, and an ordered list of
 * {@link MidiCcEvent breakpoints}.</p>
 *
 * <p>Lanes are pure data — they carry no rendering logic. The piano-roll
 * editor renders them by drawing line segments between consecutive
 * breakpoints (line-segment interpolation only, per the MVP spec).</p>
 *
 * @see MidiClip#getCcLanes()
 */
public final class MidiCcLane {

    /** Default vertical height share used when stacking multiple lanes. */
    public static final double DEFAULT_HEIGHT_RATIO = 1.0;

    private final MidiCcLaneType type;
    private final int ccNumber;
    private final boolean highResolution;
    private final int channel;
    private double heightRatio;
    private final List<MidiCcEvent> events = new ArrayList<>();

    /**
     * Creates a new lane.
     *
     * @param type           the lane type
     * @param ccNumber       the CC number — used only when {@code type} is
     *                       {@link MidiCcLaneType#ARBITRARY_CC}; for the
     *                       preset types pass {@link MidiCcLaneType#defaultCcNumber()}
     *                       or any value (it is overridden)
     * @param highResolution whether to treat the lane as 14-bit
     * @param channel        the MIDI channel (0–15)
     */
    public MidiCcLane(MidiCcLaneType type, int ccNumber, boolean highResolution,
                      int channel) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        if (channel < 0 || channel > MidiNoteData.MAX_CHANNEL) {
            throw new IllegalArgumentException(
                    "channel must be 0–" + MidiNoteData.MAX_CHANNEL + ": " + channel);
        }
        if (type == MidiCcLaneType.ARBITRARY_CC) {
            if (ccNumber < 0 || ccNumber > 127) {
                throw new IllegalArgumentException(
                        "ccNumber must be 0–127 for ARBITRARY_CC: " + ccNumber);
            }
            this.ccNumber = ccNumber;
            // Per the MIDI 1.0 spec, only CCs 0–31 have 14-bit LSB pairs
            // at ccNumber + 32. CCs above 31 cannot be high-resolution.
            if (highResolution && ccNumber > 31) {
                throw new IllegalArgumentException(
                        "14-bit high-resolution requires ccNumber 0–31, got: " + ccNumber);
            }
        } else {
            int dflt = type.defaultCcNumber();
            this.ccNumber = dflt; // -1 for VELOCITY / PITCH_BEND
        }
        if (highResolution && !type.supportsHighResolution()) {
            throw new IllegalArgumentException(
                    "lane type " + type + " does not support 14-bit values");
        }
        this.highResolution = highResolution;
        this.channel = channel;
        this.heightRatio = DEFAULT_HEIGHT_RATIO;
    }

    /**
     * Convenience constructor for preset (non-arbitrary) lane types on
     * channel 0.
     *
     * @param type           the lane type (must not be {@code ARBITRARY_CC})
     * @param highResolution whether to treat the lane as 14-bit
     * @return a new lane on channel 0
     */
    public static MidiCcLane preset(MidiCcLaneType type, boolean highResolution) {
        if (type == MidiCcLaneType.ARBITRARY_CC) {
            throw new IllegalArgumentException(
                    "use the (type, ccNumber, hiRes, channel) constructor for ARBITRARY_CC");
        }
        return new MidiCcLane(type, -1, highResolution, 0);
    }

    /**
     * Convenience for a 7-bit velocity lane on channel 0. Velocity lanes
     * derive their values from the clip's notes, but a lane object is
     * still useful for stacking and persistence.
     *
     * @return a new velocity lane
     */
    public static MidiCcLane velocity() {
        return preset(MidiCcLaneType.VELOCITY, false);
    }

    public MidiCcLaneType getType() {
        return type;
    }

    /**
     * Returns the CC number used by this lane. For non-arbitrary lane
     * types this is {@link MidiCcLaneType#defaultCcNumber()} (which is
     * {@code -1} for velocity and pitch bend).
     *
     * @return the CC number, or {@code -1} when not applicable
     */
    public int getCcNumber() {
        return ccNumber;
    }

    public boolean isHighResolution() {
        return highResolution;
    }

    public int getChannel() {
        return channel;
    }

    public double getHeightRatio() {
        return heightRatio;
    }

    /**
     * Sets the relative height share used when stacking multiple lanes.
     * Must be strictly positive.
     *
     * @param heightRatio the new height ratio
     */
    public void setHeightRatio(double heightRatio) {
        if (!(heightRatio > 0.0) || Double.isNaN(heightRatio)
                || Double.isInfinite(heightRatio)) {
            throw new IllegalArgumentException(
                    "heightRatio must be a positive finite number: " + heightRatio);
        }
        this.heightRatio = heightRatio;
    }

    /** Adds a breakpoint, keeping the list sorted by column. */
    public void addEvent(MidiCcEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        events.add(event);
        Collections.sort(events);
    }

    /**
     * Replaces the breakpoint at {@code index} with {@code newEvent},
     * re-sorting if needed.
     *
     * @return the previous event at that index
     */
    public MidiCcEvent replaceEvent(int index, MidiCcEvent newEvent) {
        Objects.requireNonNull(newEvent, "newEvent must not be null");
        MidiCcEvent prev = events.set(index, newEvent);
        Collections.sort(events);
        return prev;
    }

    public boolean removeEvent(MidiCcEvent event) {
        return events.remove(event);
    }

    public MidiCcEvent removeEventAt(int index) {
        return events.remove(index);
    }

    public int indexOf(MidiCcEvent event) {
        return events.indexOf(event);
    }

    /** Unmodifiable, column-sorted view of the breakpoints. */
    public List<MidiCcEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /** Number of breakpoints currently in the lane. */
    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }

    /**
     * Returns the linearly-interpolated value at {@code column}.
     *
     * <p>If there are no breakpoints, returns {@code 0}. Before the first
     * breakpoint the function holds at the first value; after the last
     * breakpoint it holds at the last value. Between breakpoints values
     * are linearly interpolated and rounded to the nearest integer.</p>
     *
     * @param column the query column (any integer; negative columns hold
     *               at the first breakpoint value)
     * @return the value at {@code column}, or {@code 0} when empty
     */
    public int valueAt(int column) {
        if (events.isEmpty()) {
            return 0;
        }
        MidiCcEvent first = events.get(0);
        if (column <= first.column()) {
            return first.value();
        }
        MidiCcEvent last = events.get(events.size() - 1);
        if (column >= last.column()) {
            return last.value();
        }
        // Find the segment that brackets `column` (binary search would be
        // possible, but lanes are typically small enough that linear is
        // simpler and just as fast).
        for (int i = 1; i < events.size(); i++) {
            MidiCcEvent right = events.get(i);
            if (right.column() >= column) {
                MidiCcEvent left = events.get(i - 1);
                int span = right.column() - left.column();
                if (span == 0) {
                    return right.value();
                }
                double t = (column - left.column()) / (double) span;
                double v = left.value() + t * (right.value() - left.value());
                return (int) Math.round(v);
            }
        }
        return last.value(); // unreachable, defensive
    }
}
