package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.track.Track;
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
     * Returns an unmodifiable view of the tracks.
     *
     * @return the list of tracks
     */
    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
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
}
