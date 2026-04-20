package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.List;
import java.util.Objects;

/**
 * Immutable description of a sound wave path from source to microphone.
 *
 * <p>A path may be a direct line-of-sight path or a reflected path that
 * bounces off one or more room surfaces. The telemetry engine uses these
 * paths to visualize sound propagation and compute arrival-time differences.</p>
 *
 * @param sourceName      the name of the originating sound source
 * @param microphoneName  the name of the destination microphone
 * @param waypoints       ordered list of positions the wave passes through
 *                        (source position first, microphone position last)
 * @param totalDistance    total path length in meters
 * @param delayMs         propagation delay in milliseconds
 * @param attenuationDb   signal attenuation along the path in dB
 * @param reflected       {@code true} if the path includes one or more reflections
 * @param reflectingSurface for first-order reflections, the surface that
 *                          produced the reflection (for UI highlighting);
 *                          {@code null} for direct paths or when not known
 */
public record SoundWavePath(
        String sourceName,
        String microphoneName,
        List<Position3D> waypoints,
        double totalDistance,
        double delayMs,
        double attenuationDb,
        boolean reflected,
        RoomSurface reflectingSurface
) {

    public SoundWavePath {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(microphoneName, "microphoneName must not be null");
        Objects.requireNonNull(waypoints, "waypoints must not be null");
        if (waypoints.size() < 2) {
            throw new IllegalArgumentException("waypoints must contain at least source and microphone positions");
        }
        waypoints = List.copyOf(waypoints);
        if (totalDistance < 0) {
            throw new IllegalArgumentException("totalDistance must not be negative: " + totalDistance);
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must not be negative: " + delayMs);
        }
    }

    /**
     * Backwards-compatible constructor that omits the reflecting surface
     * (set to {@code null}). Existing callers that do not yet track which
     * surface produced a reflection continue to work unchanged.
     */
    public SoundWavePath(
            String sourceName,
            String microphoneName,
            List<Position3D> waypoints,
            double totalDistance,
            double delayMs,
            double attenuationDb,
            boolean reflected) {
        this(sourceName, microphoneName, waypoints, totalDistance,
                delayMs, attenuationDb, reflected, null);
    }
}
