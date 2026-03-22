package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.List;
import java.util.Objects;

/**
 * Immutable aggregate snapshot of room sound wave telemetry.
 *
 * <p>Contains all computed wave paths (direct and reflected) for every
 * source–microphone pair in the room, plus the estimated reverberation
 * time and any actionable suggestions for improving recording quality.</p>
 *
 * @param roomDimensions        the room geometry
 * @param wavePaths             all computed sound wave paths
 * @param estimatedRt60Seconds  estimated RT60 reverberation time in seconds
 * @param suggestions           actionable adjustment suggestions
 */
public record RoomTelemetryData(
        RoomDimensions roomDimensions,
        List<SoundWavePath> wavePaths,
        double estimatedRt60Seconds,
        List<TelemetrySuggestion> suggestions
) {

    public RoomTelemetryData {
        Objects.requireNonNull(roomDimensions, "roomDimensions must not be null");
        Objects.requireNonNull(wavePaths, "wavePaths must not be null");
        Objects.requireNonNull(suggestions, "suggestions must not be null");
        wavePaths = List.copyOf(wavePaths);
        suggestions = List.copyOf(suggestions);
        if (estimatedRt60Seconds < 0) {
            throw new IllegalArgumentException(
                    "estimatedRt60Seconds must not be negative: " + estimatedRt60Seconds);
        }
    }
}
