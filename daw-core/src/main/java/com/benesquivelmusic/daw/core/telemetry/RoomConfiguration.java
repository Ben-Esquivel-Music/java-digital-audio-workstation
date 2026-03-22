package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mutable configuration of a recording room for sound wave telemetry.
 *
 * <p>Aggregates the room dimensions, wall material, microphone placements,
 * and sound sources. This configuration is fed into the
 * {@link SoundWaveTelemetryEngine} to compute telemetry data.</p>
 */
public final class RoomConfiguration {

    private RoomDimensions dimensions;
    private WallMaterial wallMaterial;
    private final List<MicrophonePlacement> microphones = new ArrayList<>();
    private final List<SoundSource> soundSources = new ArrayList<>();

    /**
     * Creates a room configuration with the given dimensions and wall material.
     *
     * @param dimensions   the room dimensions
     * @param wallMaterial the predominant wall material
     */
    public RoomConfiguration(RoomDimensions dimensions, WallMaterial wallMaterial) {
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions must not be null");
        this.wallMaterial = Objects.requireNonNull(wallMaterial, "wallMaterial must not be null");
    }

    /** Returns the room dimensions. */
    public RoomDimensions getDimensions() {
        return dimensions;
    }

    /** Sets the room dimensions. */
    public void setDimensions(RoomDimensions dimensions) {
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions must not be null");
    }

    /** Returns the predominant wall material. */
    public WallMaterial getWallMaterial() {
        return wallMaterial;
    }

    /** Sets the predominant wall material. */
    public void setWallMaterial(WallMaterial wallMaterial) {
        this.wallMaterial = Objects.requireNonNull(wallMaterial, "wallMaterial must not be null");
    }

    /**
     * Adds a microphone placement to the room.
     *
     * @param mic the microphone placement
     */
    public void addMicrophone(MicrophonePlacement mic) {
        Objects.requireNonNull(mic, "mic must not be null");
        microphones.add(mic);
    }

    /**
     * Removes a microphone placement by name.
     *
     * @param name the microphone name
     * @return {@code true} if a microphone was removed
     */
    public boolean removeMicrophone(String name) {
        return microphones.removeIf(m -> m.name().equals(name));
    }

    /**
     * Returns an unmodifiable view of the microphone placements.
     *
     * @return the list of microphones
     */
    public List<MicrophonePlacement> getMicrophones() {
        return Collections.unmodifiableList(microphones);
    }

    /**
     * Adds a sound source to the room.
     *
     * @param source the sound source
     */
    public void addSoundSource(SoundSource source) {
        Objects.requireNonNull(source, "source must not be null");
        soundSources.add(source);
    }

    /**
     * Removes a sound source by name.
     *
     * @param name the source name
     * @return {@code true} if a source was removed
     */
    public boolean removeSoundSource(String name) {
        return soundSources.removeIf(s -> s.name().equals(name));
    }

    /**
     * Returns an unmodifiable view of the sound sources.
     *
     * @return the list of sound sources
     */
    public List<SoundSource> getSoundSources() {
        return Collections.unmodifiableList(soundSources);
    }
}
