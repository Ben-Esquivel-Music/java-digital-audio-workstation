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
import java.util.List;
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
     * Adds a track to the project and creates a corresponding mixer channel.
     *
     * @param track the track to add
     */
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        tracks.add(track);
        mixer.addChannel(new MixerChannel(track.getName()));
    }

    /**
     * Creates and adds a new audio track with the given name.
     *
     * @param name the track name
     * @return the newly created track
     */
    public Track createAudioTrack(String name) {
        var track = new Track(name, TrackType.AUDIO);
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
        var track = new Track(name, TrackType.MIDI);
        addTrack(track);
        return track;
    }

    /**
     * Removes a track from the project.
     *
     * @param track the track to remove
     * @return {@code true} if the track was removed
     */
    public boolean removeTrack(Track track) {
        return tracks.remove(track);
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
