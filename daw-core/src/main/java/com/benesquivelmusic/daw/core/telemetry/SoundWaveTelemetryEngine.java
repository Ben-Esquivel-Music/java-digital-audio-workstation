package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes room sound wave telemetry from a {@link RoomConfiguration}.
 *
 * <p>For each source–microphone pair the engine computes:</p>
 * <ul>
 *   <li>The <b>direct path</b> — straight line from source to mic.</li>
 *   <li><b>First-order reflection paths</b> off the six room surfaces
 *       (four walls, floor, ceiling) using the image-source method.</li>
 * </ul>
 *
 * <p>The engine also estimates the RT60 reverberation time using the
 * Sabine equation and generates suggestions for microphone repositioning,
 * angle adjustment, and room dampening changes.</p>
 */
public final class SoundWaveTelemetryEngine {

    /** Speed of sound in air at room temperature in meters per second. */
    static final double SPEED_OF_SOUND_MPS = 343.0;

    /** Maximum acceptable RT60 for a general recording room (seconds). */
    static final double MAX_IDEAL_RT60 = 0.6;

    /** Minimum desirable RT60 — overly dead rooms sound unnatural (seconds). */
    static final double MIN_IDEAL_RT60 = 0.2;

    /** Threshold angle (degrees) beyond which a mic-aim suggestion is generated. */
    static final double MIC_AIM_THRESHOLD_DEGREES = 30.0;

    private SoundWaveTelemetryEngine() {
        // utility class
    }

    /**
     * Computes full room telemetry for the given configuration.
     *
     * @param config the room configuration
     * @return an immutable telemetry data snapshot
     */
    public static RoomTelemetryData compute(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");

        RoomDimensions dims = config.getDimensions();
        WallMaterial material = config.getWallMaterial();

        List<SoundWavePath> allPaths = new ArrayList<>();

        for (SoundSource source : config.getSoundSources()) {
            for (MicrophonePlacement mic : config.getMicrophones()) {
                allPaths.add(computeDirectPath(source, mic));
                allPaths.addAll(computeFirstOrderReflections(source, mic, dims, material));
            }
        }

        double rt60 = estimateRt60(dims, material);
        List<TelemetrySuggestion> suggestions = generateSuggestions(config, allPaths, rt60);

        return new RoomTelemetryData(dims, allPaths, rt60, suggestions,
                config.getAudienceMembers(), config.getSoundSources(),
                config.getMicrophones(), material);
    }

    // ----------------------------------------------------------------
    // Direct path
    // ----------------------------------------------------------------

    static SoundWavePath computeDirectPath(SoundSource source, MicrophonePlacement mic) {
        double distance = source.position().distanceTo(mic.position());
        double delayMs = (distance / SPEED_OF_SOUND_MPS) * 1000.0;
        double attenuationDb = -20.0 * Math.log10(Math.max(distance, 0.001));

        return new SoundWavePath(
                source.name(),
                mic.name(),
                List.of(source.position(), mic.position()),
                distance,
                delayMs,
                attenuationDb,
                false
        );
    }

    // ----------------------------------------------------------------
    // First-order reflections (image-source method)
    // ----------------------------------------------------------------

    static List<SoundWavePath> computeFirstOrderReflections(
            SoundSource source, MicrophonePlacement mic,
            RoomDimensions dims, WallMaterial material) {

        List<SoundWavePath> reflections = new ArrayList<>();
        Position3D sp = source.position();

        // Image sources for each of the six surfaces
        Position3D[] images = {
                new Position3D(-sp.x(), sp.y(), sp.z()),                    // left wall   (x = 0)
                new Position3D(2 * dims.width() - sp.x(), sp.y(), sp.z()), // right wall  (x = width)
                new Position3D(sp.x(), -sp.y(), sp.z()),                    // front wall  (y = 0)
                new Position3D(sp.x(), 2 * dims.length() - sp.y(), sp.z()),// back wall   (y = length)
                new Position3D(sp.x(), sp.y(), -sp.z()),                    // floor       (z = 0)
                new Position3D(sp.x(), sp.y(), 2 * dims.height() - sp.z()) // ceiling     (z = height)
        };

        String[] surfaceNames = {
                "left wall", "right wall", "front wall", "back wall", "floor", "ceiling"
        };

        for (int i = 0; i < images.length; i++) {
            Position3D image = images[i];
            Position3D mp = mic.position();
            double totalDist = image.distanceTo(mp);
            double delayMs = (totalDist / SPEED_OF_SOUND_MPS) * 1000.0;

            // Inverse-square attenuation + absorption loss
            double distAtten = -20.0 * Math.log10(Math.max(totalDist, 0.001));
            double absorptionLoss = 10.0 * Math.log10(1.0 - material.absorptionCoefficient());
            double attenuationDb = distAtten + absorptionLoss;

            // Compute the reflection point on the surface (midpoint along image–mic line
            // projected onto the reflecting surface)
            Position3D reflectionPoint = computeReflectionPoint(sp, image, mp, i, dims);

            reflections.add(new SoundWavePath(
                    source.name(),
                    mic.name(),
                    List.of(source.position(), reflectionPoint, mic.position()),
                    totalDist,
                    delayMs,
                    attenuationDb,
                    true
            ));
        }

        return reflections;
    }

    private static Position3D computeReflectionPoint(
            Position3D source, Position3D image, Position3D mic,
            int surfaceIndex, RoomDimensions dims) {

        // The reflection point lies on the line from image source to mic,
        // at the intersection with the reflecting surface.
        double t;
        return switch (surfaceIndex) {
            case 0 -> { // left wall  (x = 0)
                t = (image.x() == mic.x()) ? 0.5 : -image.x() / (mic.x() - image.x());
                yield interpolate(image, mic, clamp01(t));
            }
            case 1 -> { // right wall (x = width)
                t = (image.x() == mic.x()) ? 0.5
                        : (dims.width() - image.x()) / (mic.x() - image.x());
                yield interpolate(image, mic, clamp01(t));
            }
            case 2 -> { // front wall (y = 0)
                t = (image.y() == mic.y()) ? 0.5 : -image.y() / (mic.y() - image.y());
                yield interpolate(image, mic, clamp01(t));
            }
            case 3 -> { // back wall  (y = length)
                t = (image.y() == mic.y()) ? 0.5
                        : (dims.length() - image.y()) / (mic.y() - image.y());
                yield interpolate(image, mic, clamp01(t));
            }
            case 4 -> { // floor      (z = 0)
                t = (image.z() == mic.z()) ? 0.5 : -image.z() / (mic.z() - image.z());
                yield interpolate(image, mic, clamp01(t));
            }
            case 5 -> { // ceiling    (z = height)
                t = (image.z() == mic.z()) ? 0.5
                        : (dims.height() - image.z()) / (mic.z() - image.z());
                yield interpolate(image, mic, clamp01(t));
            }
            default -> source; // should never happen
        };
    }

    private static Position3D interpolate(Position3D a, Position3D b, double t) {
        return new Position3D(
                a.x() + t * (b.x() - a.x()),
                a.y() + t * (b.y() - a.y()),
                a.z() + t * (b.z() - a.z())
        );
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    // ----------------------------------------------------------------
    // Sabine RT60 estimation
    // ----------------------------------------------------------------

    /**
     * Estimates RT60 reverberation time using the Sabine equation:
     * {@code RT60 = 0.161 * V / A} where V is room volume and A is
     * total absorption in sabins.
     */
    static double estimateRt60(RoomDimensions dims, WallMaterial material) {
        double volume = dims.volume();
        double surfaceArea = dims.surfaceArea();
        double totalAbsorption = surfaceArea * material.absorptionCoefficient();
        if (totalAbsorption <= 0) {
            return Double.MAX_VALUE;
        }
        return 0.161 * volume / totalAbsorption;
    }

    // ----------------------------------------------------------------
    // Suggestion generation
    // ----------------------------------------------------------------

    static List<TelemetrySuggestion> generateSuggestions(
            RoomConfiguration config, List<SoundWavePath> paths, double rt60) {

        List<TelemetrySuggestion> suggestions = new ArrayList<>();

        // 1. RT60 / dampening suggestions
        if (rt60 > MAX_IDEAL_RT60) {
            suggestions.add(new TelemetrySuggestion.AddDampening(
                    "room walls",
                    "RT60 is %.2fs (ideal < %.1fs); add absorptive material to reduce reflections"
                            .formatted(rt60, MAX_IDEAL_RT60)));
        } else if (rt60 < MIN_IDEAL_RT60) {
            suggestions.add(new TelemetrySuggestion.RemoveDampening(
                    "room walls",
                    "RT60 is %.2fs (ideal > %.1fs); the room is overly dampened"
                            .formatted(rt60, MIN_IDEAL_RT60)));
        }

        // 2. Mic angle suggestions — check if the mic is aimed at the primary source
        for (MicrophonePlacement mic : config.getMicrophones()) {
            SoundSource nearestSource = findNearestSource(mic, config.getSoundSources());
            if (nearestSource != null) {
                double idealAzimuth = computeAzimuthToward(mic.position(), nearestSource.position());
                double idealElevation = computeElevationToward(mic.position(), nearestSource.position());

                double azimuthDiff = angleDifference(mic.azimuth(), idealAzimuth);
                double elevationDiff = Math.abs(mic.elevation() - idealElevation);

                if (azimuthDiff > MIC_AIM_THRESHOLD_DEGREES || elevationDiff > MIC_AIM_THRESHOLD_DEGREES) {
                    suggestions.add(new TelemetrySuggestion.AdjustMicAngle(
                            mic.name(), idealAzimuth, idealElevation,
                            "microphone is not aimed at nearest source '%s'"
                                    .formatted(nearestSource.name())));
                }
            }
        }

        // 3. Mic position suggestions — if a mic is very close to a wall, suggest moving it
        RoomDimensions dims = config.getDimensions();
        double wallProximityThreshold = 0.3; // 30 cm
        for (MicrophonePlacement mic : config.getMicrophones()) {
            Position3D p = mic.position();
            if (p.x() < wallProximityThreshold || (dims.width() - p.x()) < wallProximityThreshold
                    || p.y() < wallProximityThreshold || (dims.length() - p.y()) < wallProximityThreshold) {
                double newX = Math.max(wallProximityThreshold,
                        Math.min(dims.width() - wallProximityThreshold, p.x()));
                double newY = Math.max(wallProximityThreshold,
                        Math.min(dims.length() - wallProximityThreshold, p.y()));
                suggestions.add(new TelemetrySuggestion.AdjustMicPosition(
                        mic.name(),
                        new Position3D(newX, newY, p.z()),
                        "microphone is too close to a wall, which causes comb-filtering from early reflections"));
            }
        }

        return suggestions;
    }

    private static SoundSource findNearestSource(MicrophonePlacement mic, List<SoundSource> sources) {
        SoundSource nearest = null;
        double minDist = Double.MAX_VALUE;
        for (SoundSource source : sources) {
            double dist = mic.position().distanceTo(source.position());
            if (dist < minDist) {
                minDist = dist;
                nearest = source;
            }
        }
        return nearest;
    }

    static double computeAzimuthToward(Position3D from, Position3D to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double angle = Math.toDegrees(Math.atan2(dx, dy)); // 0 = +Y, 90 = +X
        if (angle < 0) angle += 360;
        return angle;
    }

    static double computeElevationToward(Position3D from, Position3D to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double horizontalDist = Math.sqrt(dx * dx + dy * dy);
        return Math.toDegrees(Math.atan2(dz, horizontalDist));
    }

    private static double angleDifference(double a, double b) {
        double diff = Math.abs(a - b) % 360;
        return diff > 180 ? 360 - diff : diff;
    }
}
