package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;

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
    private boolean recording;
    private InputMonitoringMode inputMonitoringMode = InputMonitoringMode.OFF;
    private int inputDeviceIndex = NO_INPUT_DEVICE;
    private final List<AudioClip> clips = new ArrayList<>();
    private final AutomationData automationData = new AutomationData();
    private final MidiClip midiClip = new MidiClip();
    private Track parentTrack;
    private final List<Track> childTracks = new ArrayList<>();
    private boolean collapsed;
    private TrackColor color = TrackColor.RED;
    private boolean frozen;
    private float[][] frozenAudioData;
    private final TakeComping takeComping = new TakeComping();
    private SoundFontAssignment soundFontAssignment;

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

    /** Returns the color assigned to this track. */
    public TrackColor getColor() {
        return color;
    }

    /**
     * Sets the color assigned to this track.
     *
     * @param color the track color
     * @throws NullPointerException if color is {@code null}
     */
    public void setColor(TrackColor color) {
        this.color = Objects.requireNonNull(color, "color must not be null");
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
     * Returns whether this track is currently recording.
     *
     * <p>This flag is set by the recording pipeline when recording is
     * active. It can be used by the UI to show a recording indicator
     * (e.g., red flashing) on the track header.</p>
     *
     * @return {@code true} if recording is in progress
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Sets the recording state. Typically called by the recording pipeline.
     *
     * @param recording {@code true} if recording is active
     */
    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    /**
     * Returns the input monitoring mode for this track.
     *
     * @return the monitoring mode (never {@code null})
     */
    public InputMonitoringMode getInputMonitoringMode() {
        return inputMonitoringMode;
    }

    /**
     * Sets the input monitoring mode for this track.
     *
     * @param mode the monitoring mode (must not be {@code null})
     */
    public void setInputMonitoringMode(InputMonitoringMode mode) {
        this.inputMonitoringMode = Objects.requireNonNull(mode,
                "inputMonitoringMode must not be null");
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
     * Returns the MIDI clip for this track.
     *
     * <p>The MIDI clip holds the MIDI notes placed on this track. For
     * non-MIDI tracks, the clip will be empty but is still accessible.</p>
     *
     * @return the MIDI clip (never {@code null})
     */
    public MidiClip getMidiClip() {
        return midiClip;
    }

    // ── SoundFont assignment support ────────────────────────────────────────

    /**
     * Returns the SoundFont preset assignment for this MIDI track, or
     * {@code null} if no SoundFont has been assigned.
     *
     * @return the SoundFont assignment, or {@code null}
     */
    public SoundFontAssignment getSoundFontAssignment() {
        return soundFontAssignment;
    }

    /**
     * Assigns a SoundFont preset to this track. Pass {@code null} to clear
     * the assignment.
     *
     * @param soundFontAssignment the SoundFont assignment, or {@code null}
     */
    public void setSoundFontAssignment(SoundFontAssignment soundFontAssignment) {
        this.soundFontAssignment = soundFontAssignment;
    }

    // ── Folder track support ────────────────────────────────────────────────

    /**
     * Returns the parent folder track, or {@code null} if this track is at
     * the top level.
     *
     * @return the parent track, or {@code null}
     */
    public Track getParentTrack() {
        return parentTrack;
    }

    /**
     * Sets the parent folder track. Pass {@code null} to make this track
     * top-level.
     *
     * @param parentTrack the parent folder track, or {@code null}
     */
    public void setParentTrack(Track parentTrack) {
        this.parentTrack = parentTrack;
    }

    /**
     * Adds a child track to this folder track.
     *
     * @param child the track to add as a child
     * @throws IllegalStateException    if this track is not a folder track
     * @throws IllegalArgumentException if the child is this track
     * @throws NullPointerException     if child is {@code null}
     */
    public void addChildTrack(Track child) {
        Objects.requireNonNull(child, "child must not be null");
        if (type != TrackType.FOLDER) {
            throw new IllegalStateException("only folder tracks can have children");
        }
        if (child == this) {
            throw new IllegalArgumentException("a track cannot be its own child");
        }
        childTracks.add(child);
        child.setParentTrack(this);
    }

    /**
     * Removes a child track from this folder track.
     *
     * @param child the track to remove
     * @return {@code true} if the child was removed
     */
    public boolean removeChildTrack(Track child) {
        boolean removed = childTracks.remove(child);
        if (removed) {
            child.setParentTrack(null);
        }
        return removed;
    }

    /**
     * Returns an unmodifiable view of the child tracks of this folder track.
     *
     * @return the list of child tracks (empty for non-folder tracks)
     */
    public List<Track> getChildTracks() {
        return Collections.unmodifiableList(childTracks);
    }

    /**
     * Returns whether this folder track is collapsed in the arrangement view.
     *
     * @return {@code true} if collapsed
     */
    public boolean isCollapsed() {
        return collapsed;
    }

    /**
     * Sets the collapsed state for this folder track.
     *
     * @param collapsed {@code true} to collapse
     */
    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    // ── Track freeze support ────────────────────────────────────────────────

    /**
     * Returns whether this track is frozen.
     *
     * <p>A frozen track's audio has been rendered offline through its effects
     * chain and is stored as pre-rendered data. During playback the frozen
     * audio is used instead of real-time effects processing, freeing CPU
     * resources. Editing of a frozen track (effects, volume, clip edits)
     * should be disabled in the UI until the track is unfrozen.</p>
     *
     * @return {@code true} if this track is frozen
     */
    public boolean isFrozen() {
        return frozen;
    }

    /**
     * Sets the frozen state of this track.
     *
     * @param frozen {@code true} to mark this track as frozen
     */
    void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    /**
     * Returns the pre-rendered audio data for this frozen track, or
     * {@code null} if the track is not frozen or has no rendered audio.
     *
     * @return frozen audio data as {@code [channel][sample]}, or {@code null}
     */
    public float[][] getFrozenAudioData() {
        return frozenAudioData;
    }

    /**
     * Sets the pre-rendered frozen audio data for this track.
     *
     * @param frozenAudioData the rendered audio data, or {@code null} to clear
     */
    void setFrozenAudioData(float[][] frozenAudioData) {
        this.frozenAudioData = frozenAudioData;
    }

    // ── Multi-take comping support ──────────────────────────────────────────

    /**
     * Returns the take comping manager for this track.
     *
     * <p>The take comping manager holds stacked take lanes recorded on this
     * track and the user's comp selections. Use it to add takes, select
     * comp regions, and compile the composite.</p>
     *
     * @return the take comping manager (never {@code null})
     */
    public TakeComping getTakeComping() {
        return takeComping;
    }

    /**
     * Returns the nesting depth of this track in the folder hierarchy.
     * A top-level track has depth 0, a child of a folder has depth 1, etc.
     *
     * @return the depth (0 for top-level)
     */
    public int getDepth() {
        int depth = 0;
        Track current = this.parentTrack;
        while (current != null) {
            depth++;
            current = current.getParentTrack();
        }
        return depth;
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
        copy.setInputMonitoringMode(inputMonitoringMode);
        copy.setInputDeviceIndex(inputDeviceIndex);
        copy.setColor(color);
        copy.setSoundFontAssignment(soundFontAssignment);
        for (AudioClip clip : clips) {
            copy.addClip(clip.duplicate());
        }
        return copy;
    }
}
