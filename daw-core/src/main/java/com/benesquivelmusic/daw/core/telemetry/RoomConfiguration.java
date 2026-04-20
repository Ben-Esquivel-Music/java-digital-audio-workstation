package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable configuration of a recording room for sound wave telemetry.
 *
 * <p>Aggregates the room dimensions, wall material, microphone placements,
 * sound sources, and audience members. This configuration is fed into the
 * {@link SoundWaveTelemetryEngine} to compute telemetry data.</p>
 *
 * <p>Audience members represent non-performer occupants of the recording
 * space (concert-goers, congregation members, students, etc.) whose
 * presence affects room acoustics.</p>
 */
public final class RoomConfiguration {

    private RoomDimensions dimensions;
    private SurfaceMaterialMap materialMap;
    private final List<MicrophonePlacement> microphones = new ArrayList<>();
    private final List<SoundSource> soundSources = new ArrayList<>();
    private final List<AudienceMember> audienceMembers = new ArrayList<>();
    private final List<AcousticTreatment> appliedTreatments = new ArrayList<>();

    /**
     * Per-source directivity assignments, keyed by {@link SoundSource#name()}.
     * Entries default to {@link SourceDirectivity#OMNIDIRECTIONAL} when
     * missing (see {@link #getSourceDirectivity(String)}).
     *
     * <p>A {@link LinkedHashMap} is used for deterministic iteration in
     * diagnostic output such as {@link #getSourceDirectivities()};
     * persistence itself iterates the {@code soundSources} list (not
     * this map) so the on-disk ordering is driven by source-insertion
     * order, not by this map's iteration order.</p>
     */
    private final Map<String, SourceDirectivity> sourceDirectivities =
            new LinkedHashMap<>();

    /**
     * Creates a room configuration with the given dimensions and a single
     * wall material broadcast to every surface.
     *
     * <p>This constructor remains for backwards compatibility — it is
     * equivalent to calling {@link #RoomConfiguration(RoomDimensions,
     * SurfaceMaterialMap)} with {@code new SurfaceMaterialMap(wallMaterial)}.</p>
     *
     * @param dimensions   the room dimensions
     * @param wallMaterial the material applied uniformly to all six surfaces
     */
    public RoomConfiguration(RoomDimensions dimensions, WallMaterial wallMaterial) {
        this(dimensions, new SurfaceMaterialMap(
                Objects.requireNonNull(wallMaterial, "wallMaterial must not be null")));
    }

    /**
     * Creates a room configuration with per-surface materials.
     *
     * @param dimensions  the room dimensions
     * @param materialMap the per-surface material assignment
     */
    public RoomConfiguration(RoomDimensions dimensions, SurfaceMaterialMap materialMap) {
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions must not be null");
        this.materialMap = Objects.requireNonNull(materialMap, "materialMap must not be null");
    }

    /** Returns the room dimensions. */
    public RoomDimensions getDimensions() {
        return dimensions;
    }

    /** Sets the room dimensions. */
    public void setDimensions(RoomDimensions dimensions) {
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions must not be null");
    }

    /**
     * Returns the per-surface material map.
     *
     * @return the surface-to-material assignment
     */
    public SurfaceMaterialMap getMaterialMap() {
        return materialMap;
    }

    /**
     * Replaces the per-surface material map.
     *
     * @param materialMap the new per-surface material assignment
     */
    public void setMaterialMap(SurfaceMaterialMap materialMap) {
        this.materialMap = Objects.requireNonNull(materialMap, "materialMap must not be null");
    }

    /**
     * Returns the predominant wall material — defined as the material
     * assigned to the front wall. When the configuration was built via
     * the legacy single-material constructor, this matches the original
     * material on every surface.
     *
     * @return the front-wall material (legacy &quot;predominant&quot; material)
     */
    public WallMaterial getWallMaterial() {
        return materialMap.frontWall();
    }

    /**
     * Replaces every surface's material with {@code wallMaterial}, i.e.
     * broadcasts the supplied material across all six surfaces.
     *
     * <p>Provided for backwards compatibility. Prefer
     * {@link #setMaterialMap(SurfaceMaterialMap)} when per-surface
     * granularity is needed.</p>
     */
    public void setWallMaterial(WallMaterial wallMaterial) {
        Objects.requireNonNull(wallMaterial, "wallMaterial must not be null");
        this.materialMap = new SurfaceMaterialMap(wallMaterial);
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
        sourceDirectivities.remove(name);
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

    /**
     * Adds an audience member to the room.
     *
     * @param member the audience member
     */
    public void addAudienceMember(AudienceMember member) {
        Objects.requireNonNull(member, "member must not be null");
        audienceMembers.add(member);
    }

    /**
     * Removes an audience member by name.
     *
     * @param name the audience member name
     * @return {@code true} if an audience member was removed
     */
    public boolean removeAudienceMember(String name) {
        return audienceMembers.removeIf(m -> m.name().equals(name));
    }

    /**
     * Returns an unmodifiable view of the audience members.
     *
     * @return the list of audience members
     */
    public List<AudienceMember> getAudienceMembers() {
        return Collections.unmodifiableList(audienceMembers);
    }

    /**
     * Records that an acoustic treatment has been installed in the room.
     *
     * <p>Applied treatments are consulted by
     * {@code TreatmentAdvisor} so that subsequent analyses do not re-suggest
     * a spot that already has treatment. They are persisted alongside the
     * rest of the room configuration.</p>
     *
     * @param treatment the treatment to mark as applied
     */
    public void addAppliedTreatment(AcousticTreatment treatment) {
        Objects.requireNonNull(treatment, "treatment must not be null");
        appliedTreatments.add(treatment);
    }

    /**
     * Removes an applied treatment by reference equality.
     *
     * @param treatment the treatment to remove
     * @return {@code true} if the treatment was present and removed
     */
    public boolean removeAppliedTreatment(AcousticTreatment treatment) {
        return appliedTreatments.remove(treatment);
    }

    /** Returns an unmodifiable view of the applied treatments. */
    public List<AcousticTreatment> getAppliedTreatments() {
        return Collections.unmodifiableList(appliedTreatments);
    }

    // ------------------------------------------------------------------
    // Per-source directivity
    // ------------------------------------------------------------------

    /**
     * Sets the directivity pattern for the named sound source. Sources
     * that have not been explicitly configured default to
     * {@link SourceDirectivity#OMNIDIRECTIONAL}. Passing {@code null}
     * reverts the source to that default.
     *
     * @param sourceName  the source name (must not be {@code null})
     * @param directivity the directivity, or {@code null} to clear
     */
    public void setSourceDirectivity(String sourceName, SourceDirectivity directivity) {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        if (directivity == null || directivity == SourceDirectivity.OMNIDIRECTIONAL) {
            sourceDirectivities.remove(sourceName);
        } else {
            sourceDirectivities.put(sourceName, directivity);
        }
    }

    /**
     * Returns the directivity configured for {@code sourceName}, or
     * {@link SourceDirectivity#OMNIDIRECTIONAL} if none has been set.
     *
     * @param sourceName the source name (must not be {@code null})
     * @return the directivity (never {@code null})
     */
    public SourceDirectivity getSourceDirectivity(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        return sourceDirectivities.getOrDefault(
                sourceName, SourceDirectivity.OMNIDIRECTIONAL);
    }

    /**
     * Returns an unmodifiable snapshot of the explicit per-source
     * directivity assignments. Sources using the default
     * {@link SourceDirectivity#OMNIDIRECTIONAL} are <em>not</em> present
     * in the returned map.
     */
    public Map<String, SourceDirectivity> getSourceDirectivities() {
        return Collections.unmodifiableMap(new HashMap<>(sourceDirectivities));
    }
}
