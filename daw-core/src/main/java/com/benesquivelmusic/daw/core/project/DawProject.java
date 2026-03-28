package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an entire DAW project/session.
 *
 * <p>A project aggregates all tracks, the mixer, the transport,
 * the audio format configuration, and project metadata. When a track
 * is added, a corresponding mixer channel is automatically created.</p>
 */
public final class DawProject {

    private String name;
    private final AudioFormat format;
    private final List<Track> tracks = new ArrayList<>();
    private final Mixer mixer;
    private final Transport transport;
    private ProjectMetadata metadata;

    /**
     * Tracks the mixer channel associated with each track, keyed by track ID.
     * The channel is kept in this map even after a track is removed, so that
     * undoing a remove can re-add the same channel object without creating
     * a duplicate.
     */
    private final Map<String, MixerChannel> trackChannelMap = new LinkedHashMap<>();
    private final List<TrackGroup> trackGroups = new ArrayList<>();

    /**
     * Creates a new DAW project.
     *
     * @param name   the project name
     * @param format the audio format for this project
     */
    public DawProject(String name, AudioFormat format) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.mixer = new Mixer();
        this.transport = new Transport();
        this.metadata = ProjectMetadata.createNew(name);
    }

    /** Returns the project name. */
    public String getName() {
        return name;
    }

    /** Sets the project name. */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.metadata = metadata.withName(name);
    }

    /** Returns the audio format. */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Adds a track to the project and ensures a corresponding mixer channel exists.
     *
     * <p>If the track has been added and removed before (e.g. via undo/redo), the
     * original {@link MixerChannel} is reused rather than creating a new one, which
     * keeps the mixer channel count consistent across add/remove cycles.</p>
     *
     * @param track the track to add
     */
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        tracks.add(track);
        MixerChannel channel = trackChannelMap.computeIfAbsent(
                track.getId(), _ -> new MixerChannel(track.getName()));
        if (!mixer.getChannels().contains(channel)) {
            mixer.addChannel(channel);
        }
    }

    /**
     * Creates and adds a new audio track with the given name.
     *
     * @param name the track name
     * @return the newly created track
     */
    public Track createAudioTrack(String name) {
        Track track = new Track(name, TrackType.AUDIO);
        addTrack(track);
        return track;
    }

    /**
     * Creates and adds a new MIDI track with the given name.
     *
     * @param name the track name
     * @return the newly created track
     */
    public Track createMidiTrack(String name) {
        Track track = new Track(name, TrackType.MIDI);
        addTrack(track);
        return track;
    }

    /**
     * Removes a track from the project and removes its mixer channel from the
     * active mixer. The track-to-channel mapping is retained so that a subsequent
     * {@link #addTrack(Track)} call (e.g. from an undo operation) reuses the same
     * channel rather than creating a duplicate.
     *
     * @param track the track to remove
     * @return {@code true} if the track was removed
     */
    public boolean removeTrack(Track track) {
        boolean removed = tracks.remove(track);
        if (removed) {
            MixerChannel channel = trackChannelMap.get(track.getId());
            if (channel != null) {
                mixer.removeChannel(channel);
            }
        }
        return removed;
    }

    /**
     * Moves a track from one position to another in the track list and
     * reorders the corresponding mixer channel to match.
     *
     * @param fromIndex the current index of the track to move
     * @param toIndex   the target index for the track
     * @throws IndexOutOfBoundsException if either index is out of range
     */
    public void moveTrack(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= tracks.size()) {
            throw new IndexOutOfBoundsException("fromIndex out of range: " + fromIndex);
        }
        if (toIndex < 0 || toIndex >= tracks.size()) {
            throw new IndexOutOfBoundsException("toIndex out of range: " + toIndex);
        }
        if (fromIndex == toIndex) {
            return;
        }
        Track track = tracks.remove(fromIndex);
        tracks.add(toIndex, track);
        mixer.moveChannel(fromIndex, toIndex);
    }

    /**
     * Returns an unmodifiable view of the tracks.
     *
     * @return the list of tracks
     */
    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    /**
     * Returns the {@link MixerChannel} associated with the given track, or
     * {@code null} if no channel has been created for it yet.
     *
     * @param track the track whose mixer channel is requested
     * @return the associated mixer channel, or {@code null}
     */
    public MixerChannel getMixerChannelForTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        return trackChannelMap.get(track.getId());
    }

    /**
     * Duplicates the given track and adds the copy to the project.
     *
     * <p>The duplicated track receives a new unique ID, a new mixer channel,
     * and copies of all clips. The new track is inserted immediately after
     * the original in the track list.</p>
     *
     * @param track the track to duplicate
     * @return the new duplicated track
     * @throws IllegalArgumentException if the track is not in this project
     */
    public Track duplicateTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        int index = tracks.indexOf(track);
        if (index < 0) {
            throw new IllegalArgumentException("track is not in this project");
        }
        Track copy = track.duplicate(track.getName() + " (copy)");
        tracks.add(index + 1, copy);
        MixerChannel channel = new MixerChannel(copy.getName());
        trackChannelMap.put(copy.getId(), channel);
        mixer.addChannel(channel);
        return copy;
    }

    /** Returns the mixer. */
    public Mixer getMixer() {
        return mixer;
    }

    /** Returns the transport. */
    public Transport getTransport() {
        return transport;
    }

    /** Returns the project metadata. */
    public ProjectMetadata getMetadata() {
        return metadata;
    }

    /** Sets the project metadata. */
    public void setMetadata(ProjectMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    // ── Folder track support ────────────────────────────────────────────────

    /**
     * Creates and adds a new folder track with the given name.
     *
     * @param name the folder track name
     * @return the newly created folder track
     */
    public Track createFolderTrack(String name) {
        Track track = new Track(name, TrackType.FOLDER);
        addTrack(track);
        return track;
    }

    /**
     * Moves a track into a folder track as a child.
     *
     * <p>If the track is already a child of another folder, it is removed
     * from the previous parent first.</p>
     *
     * @param track  the track to move into the folder
     * @param folder the destination folder track
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if folder is not a folder track,
     *                                  or if the track is the folder itself
     */
    public void moveTrackToFolder(Track track, Track folder) {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(folder, "folder must not be null");
        if (folder.getType() != TrackType.FOLDER) {
            throw new IllegalArgumentException("target must be a folder track");
        }
        if (track == folder) {
            throw new IllegalArgumentException("a track cannot be moved into itself");
        }
        Track previousParent = track.getParentTrack();
        if (previousParent != null) {
            previousParent.removeChildTrack(track);
        }
        folder.addChildTrack(track);
    }

    /**
     * Removes a track from its parent folder, making it a top-level track.
     *
     * @param track the track to remove from its folder
     * @throws NullPointerException     if track is {@code null}
     * @throws IllegalStateException    if the track has no parent folder
     */
    public void removeTrackFromFolder(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        Track parent = track.getParentTrack();
        if (parent == null) {
            throw new IllegalStateException("track is not in a folder");
        }
        parent.removeChildTrack(track);
    }

    // ── Track group support ─────────────────────────────────────────────────

    /**
     * Creates a new track group with the given name and tracks.
     *
     * @param name   the group name
     * @param tracks the tracks to include in the group
     * @return the newly created track group
     * @throws NullPointerException if name or tracks is {@code null}
     */
    public TrackGroup createTrackGroup(String name, List<Track> tracks) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(tracks, "tracks must not be null");
        TrackGroup group = new TrackGroup(name);
        for (Track track : tracks) {
            group.addTrack(track);
        }
        trackGroups.add(group);
        return group;
    }

    /**
     * Adds an existing track group to the project. Used by undo operations
     * to re-add a previously removed group.
     *
     * @param group the track group to add
     */
    public void addTrackGroup(TrackGroup group) {
        Objects.requireNonNull(group, "group must not be null");
        if (!trackGroups.contains(group)) {
            trackGroups.add(group);
        }
    }

    /**
     * Removes a track group from the project.
     *
     * @param group the track group to remove
     * @return {@code true} if the group was removed
     */
    public boolean removeTrackGroup(TrackGroup group) {
        return trackGroups.remove(group);
    }

    /**
     * Returns an unmodifiable view of the track groups in this project.
     *
     * @return the list of track groups
     */
    public List<TrackGroup> getTrackGroups() {
        return Collections.unmodifiableList(trackGroups);
    }
}
