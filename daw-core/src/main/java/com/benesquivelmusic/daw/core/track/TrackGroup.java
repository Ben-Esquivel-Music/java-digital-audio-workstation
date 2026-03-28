package com.benesquivelmusic.daw.core.track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group of tracks whose volume, mute, solo, and arm operations are linked.
 *
 * <p>When a linked property is changed on the group, the change is propagated
 * to all member tracks. For volume, the group applies a proportional scaling
 * factor so that member tracks retain their relative balance.</p>
 */
public final class TrackGroup {

    private final String id;
    private String name;
    private final List<Track> tracks = new ArrayList<>();

    /**
     * Creates a new track group with the given name.
     *
     * @param name the display name for this group
     */
    public TrackGroup(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /** Returns the unique identifier for this group. */
    public String getId() {
        return id;
    }

    /** Returns the display name. */
    public String getName() {
        return name;
    }

    /** Sets the display name. */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Adds a track to this group.
     *
     * @param track the track to add
     * @throws NullPointerException if track is {@code null}
     */
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        if (!tracks.contains(track)) {
            tracks.add(track);
        }
    }

    /**
     * Removes a track from this group.
     *
     * @param track the track to remove
     * @return {@code true} if the track was removed
     */
    public boolean removeTrack(Track track) {
        return tracks.remove(track);
    }

    /**
     * Returns an unmodifiable view of the tracks in this group.
     *
     * @return the list of tracks
     */
    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    /**
     * Returns the number of tracks in this group.
     *
     * @return the track count
     */
    public int size() {
        return tracks.size();
    }

    /**
     * Returns whether the given track is a member of this group.
     *
     * @param track the track to check
     * @return {@code true} if the track is in this group
     */
    public boolean contains(Track track) {
        return tracks.contains(track);
    }

    /**
     * Sets the muted state on all member tracks.
     *
     * @param muted the muted state to apply
     */
    public void setMuted(boolean muted) {
        for (Track track : tracks) {
            track.setMuted(muted);
        }
    }

    /**
     * Sets the solo state on all member tracks.
     *
     * @param solo the solo state to apply
     */
    public void setSolo(boolean solo) {
        for (Track track : tracks) {
            track.setSolo(solo);
        }
    }

    /**
     * Sets the armed state on all member tracks.
     *
     * @param armed the armed state to apply
     */
    public void setArmed(boolean armed) {
        for (Track track : tracks) {
            track.setArmed(armed);
        }
    }

    /**
     * Scales the volume of all member tracks proportionally by the given factor.
     *
     * <p>Each track's volume is multiplied by {@code factor} and clamped to
     * the [0.0, 1.0] range. This preserves the relative balance between
     * members rather than setting them all to the same absolute value.</p>
     *
     * @param factor the scaling factor (e.g. 0.5 halves all volumes)
     * @throws IllegalArgumentException if factor is negative
     */
    public void scaleVolume(double factor) {
        if (factor < 0.0) {
            throw new IllegalArgumentException("factor must not be negative: " + factor);
        }
        for (Track track : tracks) {
            double scaled = Math.min(1.0, track.getVolume() * factor);
            track.setVolume(scaled);
        }
    }
}
