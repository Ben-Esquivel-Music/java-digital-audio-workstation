package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single track in the DAW project.
 *
 * <p>A track holds audio or MIDI data and has properties such as volume,
 * pan, mute, solo, and armed (record-ready) state. Audio clips recorded
 * or imported onto this track are managed via {@link #addClip(AudioClip)},
 * {@link #removeClip(AudioClip)}, and {@link #getClips()}.</p>
 */
public final class Track {

    private final String id;
    private final TrackType type;
    private String name;
    private double volume;
    private double pan;
    private boolean muted;
    private boolean solo;
    private boolean armed;
    private final List<AudioClip> clips = new ArrayList<>();

    /**
     * Creates a new track with the given name and type.
     *
     * @param name the display name for this track
     * @param type the track type
     */
    public Track(String name, TrackType type) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.volume = 1.0;
        this.pan = 0.0;
        this.muted = false;
        this.solo = false;
        this.armed = false;
    }

    /** Returns the unique identifier for this track. */
    public String getId() {
        return id;
    }

    /** Returns the track type. */
    public TrackType getType() {
        return type;
    }

    /** Returns the display name. */
    public String getName() {
        return name;
    }

    /** Sets the display name. */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /** Returns the volume level (0.0 = silence, 1.0 = unity gain). */
    public double getVolume() {
        return volume;
    }

    /**
     * Sets the volume level.
     *
     * @param volume volume in the range [0.0, 1.0]
     * @throws IllegalArgumentException if volume is out of range
     */
    public void setVolume(double volume) {
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        this.volume = volume;
    }

    /** Returns the pan position (−1.0 = full left, 0.0 = center, 1.0 = full right). */
    public double getPan() {
        return pan;
    }

    /**
     * Sets the pan position.
     *
     * @param pan pan in the range [−1.0, 1.0]
     * @throws IllegalArgumentException if pan is out of range
     */
    public void setPan(double pan) {
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
        this.pan = pan;
    }

    /** Returns whether this track is muted. */
    public boolean isMuted() {
        return muted;
    }

    /** Sets the muted state. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Returns whether this track is soloed. */
    public boolean isSolo() {
        return solo;
    }

    /** Sets the solo state. */
    public void setSolo(boolean solo) {
        this.solo = solo;
    }

    /** Returns whether this track is armed for recording. */
    public boolean isArmed() {
        return armed;
    }

    /** Sets the armed (record-ready) state. */
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /**
     * Adds an audio clip to this track.
     *
     * @param clip the clip to add
     */
    public void addClip(AudioClip clip) {
        Objects.requireNonNull(clip, "clip must not be null");
        clips.add(clip);
    }

    /**
     * Removes an audio clip from this track.
     *
     * @param clip the clip to remove
     * @return {@code true} if the clip was removed
     */
    public boolean removeClip(AudioClip clip) {
        return clips.remove(clip);
    }

    /**
     * Returns an unmodifiable view of the audio clips on this track.
     *
     * @return the list of clips
     */
    public List<AudioClip> getClips() {
        return Collections.unmodifiableList(clips);
    }
}
