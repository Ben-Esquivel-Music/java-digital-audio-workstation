package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationData;

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

    /** Sentinel value indicating no input device has been assigned. */
    public static final int NO_INPUT_DEVICE = -1;

    private final String id;
    private final TrackType type;
    private String name;
    private double volume;
    private double pan;
    private boolean muted;
    private boolean solo;
    private boolean armed;
    private boolean phaseInverted;
    private int inputDeviceIndex = NO_INPUT_DEVICE;
    private final List<AudioClip> clips = new ArrayList<>();
    private final AutomationData automationData = new AutomationData();

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
        this.phaseInverted = false;
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

    /** Returns whether this track's phase is inverted. */
    public boolean isPhaseInverted() {
        return phaseInverted;
    }

    /** Sets the phase-inverted state. */
    public void setPhaseInverted(boolean phaseInverted) {
        this.phaseInverted = phaseInverted;
    }

    /**
     * Returns the index of the input device assigned to this track, or
     * {@link #NO_INPUT_DEVICE} ({@value #NO_INPUT_DEVICE}) if no device
     * has been assigned.
     *
     * @return the input device index, or {@code -1} if unassigned
     */
    public int getInputDeviceIndex() {
        return inputDeviceIndex;
    }

    /**
     * Assigns an input device to this track by its index.
     *
     * <p>Use {@link #NO_INPUT_DEVICE} to clear the assignment.</p>
     *
     * @param inputDeviceIndex the device index, or {@code -1} to unassign
     * @throws IllegalArgumentException if the index is less than {@code -1}
     */
    public void setInputDeviceIndex(int inputDeviceIndex) {
        if (inputDeviceIndex < NO_INPUT_DEVICE) {
            throw new IllegalArgumentException(
                    "inputDeviceIndex must be >= -1: " + inputDeviceIndex);
        }
        this.inputDeviceIndex = inputDeviceIndex;
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

    /**
     * Returns the automation data for this track.
     *
     * <p>Automation data holds per-parameter automation lanes that define
     * envelope curves for volume, pan, mute, and send levels.</p>
     *
     * @return the automation data
     */
    public AutomationData getAutomationData() {
        return automationData;
    }

    /**
     * Creates a duplicate of this track with a new unique ID.
     * Clips on the track are also duplicated.
     *
     * @param newName the name for the duplicated track
     * @return a new {@code Track} with the same properties but a different ID
     */
    public Track duplicate(String newName) {
        Objects.requireNonNull(newName, "newName must not be null");
        Track copy = new Track(newName, type);
        copy.setVolume(volume);
        copy.setPan(pan);
        copy.setMuted(muted);
        copy.setSolo(solo);
        copy.setArmed(false);
        copy.setPhaseInverted(phaseInverted);
        copy.setInputDeviceIndex(inputDeviceIndex);
        for (AudioClip clip : clips) {
            copy.addClip(clip.duplicate());
        }
        return copy;
    }
}
