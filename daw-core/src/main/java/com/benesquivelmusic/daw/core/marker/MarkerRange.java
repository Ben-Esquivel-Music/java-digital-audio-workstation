package com.benesquivelmusic.daw.core.marker;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a marker range that spans a section of the timeline with a
 * start and end point.
 *
 * <p>Marker ranges are used to mark entire sections (e.g., "Verse 1" from
 * beat 0 to beat 16, "Chorus" from beat 16 to beat 32).</p>
 */
public final class MarkerRange implements Comparable<MarkerRange> {

    private final String id;
    private String name;
    private double startPositionInBeats;
    private double endPositionInBeats;
    private String color;
    private MarkerType type;

    /**
     * Creates a new marker range.
     *
     * @param name                  the display name for this range
     * @param startPositionInBeats  the start position in beats (must be &ge; 0)
     * @param endPositionInBeats    the end position in beats (must be &gt; startPositionInBeats)
     * @param type                  the marker type
     * @throws NullPointerException     if name or type is {@code null}
     * @throws IllegalArgumentException if positions are invalid
     */
    public MarkerRange(String name, double startPositionInBeats,
                       double endPositionInBeats, MarkerType type) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        if (startPositionInBeats < 0) {
            throw new IllegalArgumentException(
                    "startPositionInBeats must not be negative: " + startPositionInBeats);
        }
        if (endPositionInBeats <= startPositionInBeats) {
            throw new IllegalArgumentException(
                    "endPositionInBeats must be greater than startPositionInBeats: start="
                            + startPositionInBeats + ", end=" + endPositionInBeats);
        }
        this.startPositionInBeats = startPositionInBeats;
        this.endPositionInBeats = endPositionInBeats;
        this.color = type.getDefaultColor();
    }

    /** Returns the unique identifier for this marker range. */
    public String getId() {
        return id;
    }

    /** Returns the display name. */
    public String getName() {
        return name;
    }

    /**
     * Sets the display name.
     *
     * @param name the new name
     * @throws NullPointerException if name is {@code null}
     */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /** Returns the start position in beats. */
    public double getStartPositionInBeats() {
        return startPositionInBeats;
    }

    /** Returns the end position in beats. */
    public double getEndPositionInBeats() {
        return endPositionInBeats;
    }

    /**
     * Sets the start and end positions for this range.
     *
     * @param startPositionInBeats the start position (must be &ge; 0)
     * @param endPositionInBeats   the end position (must be &gt; startPositionInBeats)
     * @throws IllegalArgumentException if positions are invalid
     */
    public void setRange(double startPositionInBeats, double endPositionInBeats) {
        if (startPositionInBeats < 0) {
            throw new IllegalArgumentException(
                    "startPositionInBeats must not be negative: " + startPositionInBeats);
        }
        if (endPositionInBeats <= startPositionInBeats) {
            throw new IllegalArgumentException(
                    "endPositionInBeats must be greater than startPositionInBeats: start="
                            + startPositionInBeats + ", end=" + endPositionInBeats);
        }
        this.startPositionInBeats = startPositionInBeats;
        this.endPositionInBeats = endPositionInBeats;
    }

    /**
     * Returns the duration of this range in beats.
     *
     * @return the duration in beats
     */
    public double getDurationInBeats() {
        return endPositionInBeats - startPositionInBeats;
    }

    /** Returns the hex color string. */
    public String getColor() {
        return color;
    }

    /**
     * Sets the color for this marker range.
     *
     * @param color a hex color string
     * @throws NullPointerException if color is {@code null}
     */
    public void setColor(String color) {
        this.color = Objects.requireNonNull(color, "color must not be null");
    }

    /** Returns the marker type. */
    public MarkerType getType() {
        return type;
    }

    /**
     * Sets the marker type.
     *
     * @param type the new marker type
     * @throws NullPointerException if type is {@code null}
     */
    public void setType(MarkerType type) {
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    /**
     * Returns whether the given beat position falls within this range
     * (inclusive start, exclusive end).
     *
     * @param positionInBeats the position to check
     * @return {@code true} if the position is within the range
     */
    public boolean contains(double positionInBeats) {
        return positionInBeats >= startPositionInBeats
                && positionInBeats < endPositionInBeats;
    }

    /**
     * Marker ranges are ordered by their start position.
     *
     * @param other the range to compare to
     * @return a negative, zero, or positive value
     */
    @Override
    public int compareTo(MarkerRange other) {
        return Double.compare(this.startPositionInBeats, other.startPositionInBeats);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MarkerRange other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + " [" + startPositionInBeats + " - " + endPositionInBeats
                + " beats] (" + type + ")";
    }
}
