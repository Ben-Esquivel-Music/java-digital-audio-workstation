package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.List;
import java.util.Objects;

/**
 * Immutable aggregate snapshot of room sound wave telemetry.
 *
 * <p>Contains all computed wave paths (direct and reflected) for every
 * source–microphone pair in the room, plus the estimated reverberation
 * time, audience member positions, and any actionable suggestions for
 * improving recording quality.</p>
 *
 * <p>Audience members represent non-performer occupants of the recording
 * space (concert-goers, congregation members, students, etc.) whose
 * presence affects room absorption and may influence microphone placement.</p>
 *
 * @param roomDimensions        the room geometry
 * @param wavePaths             all computed sound wave paths
 * @param estimatedRt60Seconds  estimated RT60 reverberation time in seconds
 * @param suggestions           actionable adjustment suggestions
 * @param audienceMembers       audience members present in the room (may be empty)
 */
public record RoomTelemetryData(
        RoomDimensions roomDimensions,
        List<SoundWavePath> wavePaths,
        double estimatedRt60Seconds,
        List<TelemetrySuggestion> suggestions,
        List<AudienceMember> audienceMembers
) {

    /**
     * Creates telemetry data with audience members.
     */
    public RoomTelemetryData {
        Objects.requireNonNull(roomDimensions, "roomDimensions must not be null");
        Objects.requireNonNull(wavePaths, "wavePaths must not be null");
        Objects.requireNonNull(suggestions, "suggestions must not be null");
        Objects.requireNonNull(audienceMembers, "audienceMembers must not be null");
        wavePaths = List.copyOf(wavePaths);
        suggestions = List.copyOf(suggestions);
        audienceMembers = List.copyOf(audienceMembers);
        if (estimatedRt60Seconds < 0) {
            throw new IllegalArgumentException(
                    "estimatedRt60Seconds must not be negative: " + estimatedRt60Seconds);
        }
    }

    /**
     * Backward-compatible factory that creates telemetry data with no audience members.
     *
     * @param roomDimensions        the room geometry
     * @param wavePaths             all computed sound wave paths
     * @param estimatedRt60Seconds  estimated RT60 reverberation time in seconds
     * @param suggestions           actionable adjustment suggestions
     * @return telemetry data with an empty audience member list
     */
    public static RoomTelemetryData withoutAudience(
            RoomDimensions roomDimensions,
            List<SoundWavePath> wavePaths,
            double estimatedRt60Seconds,
            List<TelemetrySuggestion> suggestions) {
        return new RoomTelemetryData(roomDimensions, wavePaths, estimatedRt60Seconds,
                suggestions, List.of());
    }
}
