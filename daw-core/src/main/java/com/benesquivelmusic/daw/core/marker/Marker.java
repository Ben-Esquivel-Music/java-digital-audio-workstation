package com.benesquivelmusic.daw.core.marker;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a named marker at a specific position on the timeline.
 *
 * <p>Markers label specific points in time (e.g., "Verse 1", "Chorus",
 * "Bridge") so that engineers and producers can quickly navigate between
 * song sections during editing, mixing, and review.</p>
 *
 * <p>Each marker has a unique identifier, a human-readable name, a position
 * in beats, a color for visual distinction, and a {@link MarkerType} for
 * categorization.</p>
 */
public final class Marker implements Comparable<Marker> {

    private final String id;
    private String name;
    private double positionInBeats;
    private String color;
    private MarkerType type;

    /**
     * Creates a new marker at the given position.
     *
     * @param name            the display name for this marker
     * @param positionInBeats the position in beats (must be &ge; 0)
     * @param type            the marker type
     * @throws NullPointerException     if name or type is {@code null}
     * @throws IllegalArgumentException if positionInBeats is negative
     */
    public Marker(String name, double positionInBeats, MarkerType type) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        if (positionInBeats < 0) {
            throw new IllegalArgumentException(
                    "positionInBeats must not be negative: " + positionInBeats);
        }
        this.positionInBeats = positionInBeats;
        this.color = type.getDefaultColor();
    }

    /** Returns the unique identifier for this marker. */
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

    /** Returns the position in beats. */
    public double getPositionInBeats() {
        return positionInBeats;
    }

    /**
     * Sets the position in beats.
     *
     * @param positionInBeats the new position (must be &ge; 0)
     * @throws IllegalArgumentException if positionInBeats is negative
     */
    public void setPositionInBeats(double positionInBeats) {
        if (positionInBeats < 0) {
            throw new IllegalArgumentException(
                    "positionInBeats must not be negative: " + positionInBeats);
        }
        this.positionInBeats = positionInBeats;
    }

    /** Returns the hex color string (e.g., {@code "#3498DB"}). */
    public String getColor() {
        return color;
    }

    /**
     * Sets the color for this marker.
     *
     * @param color a hex color string (e.g., {@code "#FF5733"})
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
     * Sets the marker type and updates the color to the type's default.
     *
     * @param type the new marker type
     * @throws NullPointerException if type is {@code null}
     */
    public void setType(MarkerType type) {
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    /**
     * Markers are ordered by their position in beats.
     *
     * @param other the marker to compare to
     * @return a negative, zero, or positive value
     */
    @Override
    public int compareTo(Marker other) {
        return Double.compare(this.positionInBeats, other.positionInBeats);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Marker other)) {
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
        return name + " @ " + positionInBeats + " beats (" + type + ")";
    }
}
