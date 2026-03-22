package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;

import java.util.Objects;

/**
 * An audio object with freely positionable 3D spatial metadata.
 *
 * <p>In Dolby Atmos workflows, audio objects carry per-sample 3D position
 * metadata that allows the playback renderer to adapt the mix to any
 * speaker configuration or binaural headphones. Each object has an
 * associated {@link ObjectMetadata} containing its current position,
 * size/spread, and gain.</p>
 */
public final class AudioObject {

    private final String trackId;
    private ObjectMetadata metadata;

    /**
     * Creates an audio object with the given track ID and default metadata.
     *
     * @param trackId the unique identifier of the associated track
     */
    public AudioObject(String trackId) {
        this(trackId, ObjectMetadata.DEFAULT);
    }

    /**
     * Creates an audio object with the given track ID and initial metadata.
     *
     * @param trackId  the unique identifier of the associated track
     * @param metadata the initial spatial metadata
     */
    public AudioObject(String trackId, ObjectMetadata metadata) {
        this.trackId = Objects.requireNonNull(trackId, "trackId must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    /** Returns the track identifier. */
    public String getTrackId() {
        return trackId;
    }

    /** Returns the current spatial metadata. */
    public ObjectMetadata getMetadata() {
        return metadata;
    }

    /**
     * Updates the spatial metadata for this audio object.
     *
     * @param metadata the new metadata
     */
    public void setMetadata(ObjectMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
