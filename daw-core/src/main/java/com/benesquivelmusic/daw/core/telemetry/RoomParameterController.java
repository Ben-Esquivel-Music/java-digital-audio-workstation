package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Controller providing real-time room acoustic parameter computations for
 * the telemetry view.
 *
 * <p>This class bridges the room simulation engine with the UI, exposing
 * methods for computing RT60 reverberation time, early reflection data,
 * and optimal microphone placement suggestions without requiring a full
 * telemetry engine run.</p>
 *
 * <p>All computations are stateless and can be called from any thread.</p>
 */
public final class RoomParameterController {

    private RoomParameterController() {
        // utility class
    }

    /**
     * Sabine equation constant: {@code 0.161 * V / A}.
     */
    private static final double SABINE_CONSTANT = 0.161;

    /** Speed of sound in air at room temperature in meters per second. */
    private static final double SPEED_OF_SOUND_MPS = 343.0;

    /** Minimum recommended distance from walls to avoid comb-filtering (meters). */
    private static final double MIN_WALL_DISTANCE = 0.5;

    /**
     * Computes the estimated RT60 reverberation time using the Sabine equation.
     *
     * <p>RT60 = 0.161 × V / (S × α) where V is room volume, S is total
     * surface area, and α is the wall material absorption coefficient.</p>
     *
     * @param dimensions the room dimensions
     * @param material   the predominant wall material
     * @return the estimated RT60 in seconds
     */
    public static double computeRt60(RoomDimensions dimensions, WallMaterial material) {
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(material, "material must not be null");

        double volume = dimensions.volume();
        double surfaceArea = dimensions.surfaceArea();
        double totalAbsorption = surfaceArea * material.absorptionCoefficient();
        if (totalAbsorption <= 0) {
            return Double.MAX_VALUE;
        }
        return SABINE_CONSTANT * volume / totalAbsorption;
    }

    /**
     * Computes early reflection data for a source–microphone pair.
     *
     * <p>Uses the image-source method to compute first-order reflections
     * off the six room surfaces. Returns a list of
     * {@link EarlyReflection} records with distance, delay, and level.</p>
     *
     * @param dimensions the room dimensions
     * @param material   the predominant wall material
     * @param source     the sound source
     * @param mic        the microphone placement
     * @return an unmodifiable list of early reflections (6 entries, one per surface)
     */
    public static List<EarlyReflection> computeEarlyReflections(
            RoomDimensions dimensions, WallMaterial material,
            SoundSource source, MicrophonePlacement mic) {
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(material, "material must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(mic, "mic must not be null");

        Position3D sp = source.position();
        Position3D mp = mic.position();

        String[] surfaceNames = {
                "Left wall", "Right wall", "Front wall", "Back wall", "Floor", "Ceiling"
        };

        Position3D[] images = {
                new Position3D(-sp.x(), sp.y(), sp.z()),
                new Position3D(2 * dimensions.width() - sp.x(), sp.y(), sp.z()),
                new Position3D(sp.x(), -sp.y(), sp.z()),
                new Position3D(sp.x(), 2 * dimensions.length() - sp.y(), sp.z()),
                new Position3D(sp.x(), sp.y(), -sp.z()),
                new Position3D(sp.x(), sp.y(), 2 * dimensions.height() - sp.z())
        };

        double absorption = material.absorptionCoefficient();
        double reflectionGain = 1.0 - absorption;

        List<EarlyReflection> reflections = new ArrayList<>(6);
        for (int i = 0; i < images.length; i++) {
            double distance = images[i].distanceTo(mp);
            double delayMs = (distance / SPEED_OF_SOUND_MPS) * 1000.0;
            double levelDb = -20.0 * Math.log10(Math.max(distance, 0.001))
                    + 10.0 * Math.log10(Math.max(reflectionGain, 1e-10));
            reflections.add(new EarlyReflection(surfaceNames[i], distance, delayMs, levelDb));
        }
        return Collections.unmodifiableList(reflections);
    }

    /**
     * Suggests an optimal microphone position for a given set of sound sources.
     *
     * <p>Places the microphone at the centroid of all sources, offset slightly
     * toward the center of the room, and at ear height (1.2 m). Ensures the
     * suggested position is at least {@value #MIN_WALL_DISTANCE} meters from
     * any wall.</p>
     *
     * @param dimensions the room dimensions
     * @param sources    the sound sources in the room
     * @return the suggested microphone position, or {@code null} if no sources
     */
    public static Position3D suggestOptimalMicPosition(
            RoomDimensions dimensions, List<SoundSource> sources) {
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        if (sources.isEmpty()) {
            return null;
        }

        double sumX = 0, sumY = 0;
        for (SoundSource src : sources) {
            sumX += src.position().x();
            sumY += src.position().y();
        }
        double centroidX = sumX / sources.size();
        double centroidY = sumY / sources.size();

        // Nudge toward room center
        double width = dimensions.width();
        double length = dimensions.length();
        double roomCenterX = width / 2.0;
        double roomCenterY = length / 2.0;
        double x = centroidX + (roomCenterX - centroidX) * 0.3;
        double y = centroidY + (roomCenterY - centroidY) * 0.3;

        // Clamp to keep away from walls, adapting to very small rooms
        double effectiveMinX = Math.min(MIN_WALL_DISTANCE, width / 2.0);
        double effectiveMinY = Math.min(MIN_WALL_DISTANCE, length / 2.0);
        x = Math.max(effectiveMinX, Math.min(width - effectiveMinX, x));
        y = Math.max(effectiveMinY, Math.min(length - effectiveMinY, y));

        // Ensure final coordinates are strictly inside the room bounds
        x = Math.max(0.0, Math.min(width, x));
        y = Math.max(0.0, Math.min(length, y));
        return new Position3D(x, y, 1.2);
    }

    /**
     * Immutable record describing a single early reflection off a room surface.
     *
     * @param surfaceName the human-readable name of the reflecting surface
     * @param distance    the total reflection path distance in meters
     * @param delayMs     the propagation delay in milliseconds
     * @param levelDb     the reflection level in dB (relative, includes
     *                    distance attenuation and absorption loss)
     */
    public record EarlyReflection(String surfaceName, double distance, double delayMs, double levelDb) {

        public EarlyReflection {
            Objects.requireNonNull(surfaceName, "surfaceName must not be null");
        }
    }
}
