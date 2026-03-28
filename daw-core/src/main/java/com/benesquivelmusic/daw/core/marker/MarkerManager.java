package com.benesquivelmusic.daw.core.marker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages the collection of markers and marker ranges for a project.
 *
 * <p>Provides operations for adding, removing, and editing markers, as well
 * as navigation helpers to jump to the next or previous marker relative to
 * a given playhead position. Markers are maintained in sorted order by
 * position for efficient navigation.</p>
 */
public final class MarkerManager {

    private final List<Marker> markers = new ArrayList<>();
    private final List<MarkerRange> markerRanges = new ArrayList<>();

    /**
     * Adds a marker to the collection.
     *
     * @param marker the marker to add
     * @throws NullPointerException if marker is {@code null}
     */
    public void addMarker(Marker marker) {
        Objects.requireNonNull(marker, "marker must not be null");
        markers.add(marker);
        Collections.sort(markers);
    }

    /**
     * Removes a marker from the collection.
     *
     * @param marker the marker to remove
     * @return {@code true} if the marker was removed
     */
    public boolean removeMarker(Marker marker) {
        return markers.remove(marker);
    }

    /**
     * Returns an unmodifiable view of the markers, sorted by position.
     *
     * @return the list of markers
     */
    public List<Marker> getMarkers() {
        return Collections.unmodifiableList(markers);
    }

    /**
     * Returns the number of markers.
     *
     * @return the marker count
     */
    public int getMarkerCount() {
        return markers.size();
    }

    /**
     * Finds a marker by its unique identifier.
     *
     * @param id the marker ID
     * @return an {@link Optional} containing the marker, or empty if not found
     * @throws NullPointerException if id is {@code null}
     */
    public Optional<Marker> findMarkerById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        for (Marker marker : markers) {
            if (marker.getId().equals(id)) {
                return Optional.of(marker);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the next marker after the given position, or empty if there
     * is no marker ahead.
     *
     * @param currentPositionInBeats the current playhead position
     * @return the next marker, or empty
     */
    public Optional<Marker> getNextMarker(double currentPositionInBeats) {
        for (Marker marker : markers) {
            if (marker.getPositionInBeats() > currentPositionInBeats) {
                return Optional.of(marker);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the previous marker before the given position, or empty if
     * there is no marker behind.
     *
     * @param currentPositionInBeats the current playhead position
     * @return the previous marker, or empty
     */
    public Optional<Marker> getPreviousMarker(double currentPositionInBeats) {
        Marker previous = null;
        for (Marker marker : markers) {
            if (marker.getPositionInBeats() < currentPositionInBeats) {
                previous = marker;
            } else {
                break;
            }
        }
        return Optional.ofNullable(previous);
    }

    /**
     * Returns all markers of the given type.
     *
     * @param type the marker type to filter by
     * @return a list of markers matching the given type
     * @throws NullPointerException if type is {@code null}
     */
    public List<Marker> getMarkersByType(MarkerType type) {
        Objects.requireNonNull(type, "type must not be null");
        List<Marker> result = new ArrayList<>();
        for (Marker marker : markers) {
            if (marker.getType() == type) {
                result.add(marker);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Removes all markers from the collection.
     */
    public void clearMarkers() {
        markers.clear();
    }

    /**
     * Re-sorts the marker list. Call this after modifying a marker's position.
     */
    public void resort() {
        Collections.sort(markers);
    }

    // ── Marker range operations ─────────────────────────────────────────────

    /**
     * Adds a marker range to the collection.
     *
     * @param range the marker range to add
     * @throws NullPointerException if range is {@code null}
     */
    public void addMarkerRange(MarkerRange range) {
        Objects.requireNonNull(range, "range must not be null");
        markerRanges.add(range);
        Collections.sort(markerRanges);
    }

    /**
     * Removes a marker range from the collection.
     *
     * @param range the marker range to remove
     * @return {@code true} if the range was removed
     */
    public boolean removeMarkerRange(MarkerRange range) {
        return markerRanges.remove(range);
    }

    /**
     * Returns an unmodifiable view of the marker ranges, sorted by start position.
     *
     * @return the list of marker ranges
     */
    public List<MarkerRange> getMarkerRanges() {
        return Collections.unmodifiableList(markerRanges);
    }

    /**
     * Returns the number of marker ranges.
     *
     * @return the marker range count
     */
    public int getMarkerRangeCount() {
        return markerRanges.size();
    }

    /**
     * Finds a marker range by its unique identifier.
     *
     * @param id the marker range ID
     * @return an {@link Optional} containing the range, or empty if not found
     * @throws NullPointerException if id is {@code null}
     */
    public Optional<MarkerRange> findMarkerRangeById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        for (MarkerRange range : markerRanges) {
            if (range.getId().equals(id)) {
                return Optional.of(range);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all marker ranges that contain the given beat position.
     *
     * @param positionInBeats the position to check
     * @return a list of ranges containing the position
     */
    public List<MarkerRange> getRangesAtPosition(double positionInBeats) {
        List<MarkerRange> result = new ArrayList<>();
        for (MarkerRange range : markerRanges) {
            if (range.contains(positionInBeats)) {
                result.add(range);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Removes all marker ranges from the collection.
     */
    public void clearMarkerRanges() {
        markerRanges.clear();
    }
}
