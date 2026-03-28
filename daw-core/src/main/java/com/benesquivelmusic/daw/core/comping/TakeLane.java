package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single take (lane) within a track's comping system.
 *
 * <p>A take lane holds audio clips recorded during one pass of the same
 * section. Multiple take lanes are stacked vertically as sub-tracks
 * within the parent track, and the user selects (comps) the best
 * segments from each take to form a composite.</p>
 */
public final class TakeLane {

    private final String id;
    private String name;
    private final List<AudioClip> clips = new ArrayList<>();
    private boolean soloed;
    private boolean muted;

    /**
     * Creates a new take lane with the given name.
     *
     * @param name the display name (e.g. "Take 1", "Take 2")
     */
    public TakeLane(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /** Returns the unique identifier for this take lane. */
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
     */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Adds an audio clip to this take lane.
     *
     * @param clip the clip to add
     */
    public void addClip(AudioClip clip) {
        Objects.requireNonNull(clip, "clip must not be null");
        clips.add(clip);
    }

    /**
     * Removes an audio clip from this take lane.
     *
     * @param clip the clip to remove
     * @return {@code true} if the clip was removed
     */
    public boolean removeClip(AudioClip clip) {
        return clips.remove(clip);
    }

    /**
     * Returns an unmodifiable view of the audio clips in this take lane.
     *
     * @return the list of clips
     */
    public List<AudioClip> getClips() {
        return Collections.unmodifiableList(clips);
    }

    /** Returns whether this take lane is soloed for auditioning. */
    public boolean isSoloed() {
        return soloed;
    }

    /**
     * Sets whether this take lane is soloed for auditioning.
     *
     * @param soloed {@code true} to solo
     */
    public void setSoloed(boolean soloed) {
        this.soloed = soloed;
    }

    /** Returns whether this take lane is muted. */
    public boolean isMuted() {
        return muted;
    }

    /**
     * Sets whether this take lane is muted.
     *
     * @param muted {@code true} to mute
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }
}
