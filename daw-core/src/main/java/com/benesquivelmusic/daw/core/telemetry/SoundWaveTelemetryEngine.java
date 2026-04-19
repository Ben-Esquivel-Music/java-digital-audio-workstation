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

    /** Number of facets used to approximate a curved ceiling (dome, vault). */
    static final int CURVED_CEILING_FACETS = 8;

    /**
     * Radius (meters) around a dome/vault focus at which the engine emits a
     * whispering-gallery hot-spot suggestion.
     */
    static final double FOCUS_PROXIMITY_METERS = 1.0;

    /**
     * Cosine threshold (roughly 20°) for deciding that a source–mic line is
     * aligned with a cathedral's ridge axis, which produces flutter echoes.
     */
    static final double RIDGE_ALIGNMENT_COS_THRESHOLD = Math.cos(Math.toRadians(20.0));

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

        // Image sources for the four walls and the floor (the ceiling is
        // handled separately below because its shape may be non-flat).
        Position3D[] images = {
                new Position3D(-sp.x(), sp.y(), sp.z()),                    // left wall   (x = 0)
                new Position3D(2 * dims.width() - sp.x(), sp.y(), sp.z()), // right wall  (x = width)
                new Position3D(sp.x(), -sp.y(), sp.z()),                    // front wall  (y = 0)
                new Position3D(sp.x(), 2 * dims.length() - sp.y(), sp.z()),// back wall   (y = length)
                new Position3D(sp.x(), sp.y(), -sp.z())                     // floor       (z = 0)
        };

        String[] surfaceNames = {
                "left wall", "right wall", "front wall", "back wall", "floor"
        };

        for (int i = 0; i < images.length; i++) {
            Position3D image = images[i];
            Position3D mp = mic.position();
            double totalDist = image.distanceTo(mp);
            double delayMs = (totalDist / SPEED_OF_SOUND_MPS) * 1000.0;

            double distAtten = -20.0 * Math.log10(Math.max(totalDist, 0.001));
            double absorptionLoss = 10.0 * Math.log10(1.0 - material.absorptionCoefficient());
            double attenuationDb = distAtten + absorptionLoss;

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

        reflections.addAll(computeCeilingReflections(source, mic, dims, material));

        return reflections;
    }

    /**
     * Computes first-order reflections off the ceiling. For a flat ceiling
     * a single image-source reflection is produced; for curved ceilings
     * (dome, barrel vault) the surface is approximated with
     * {@link #CURVED_CEILING_FACETS} planar facets and one reflection is
     * produced per facet that the reflected ray actually strikes.
     */
    static List<SoundWavePath> computeCeilingReflections(
            SoundSource source, MicrophonePlacement mic,
            RoomDimensions dims, WallMaterial material) {

        List<SoundWavePath> reflections = new ArrayList<>();
        Position3D sp = source.position();
        Position3D mp = mic.position();
        CeilingShape shape = dims.ceiling();

        switch (shape) {
            case CeilingShape.Flat flat -> {
                Position3D image = new Position3D(sp.x(), sp.y(), 2 * flat.height() - sp.z());
                double totalDist = image.distanceTo(mp);
                double delayMs = (totalDist / SPEED_OF_SOUND_MPS) * 1000.0;
                double attenuationDb = -20.0 * Math.log10(Math.max(totalDist, 0.001))
                        + 10.0 * Math.log10(1.0 - material.absorptionCoefficient());
                double denominator = mp.z() - image.z();
                if (Math.abs(denominator) >= 1.0e-9) {
                    double t = (flat.height() - image.z()) / denominator;
                    Position3D rp = interpolate(image, mp, clamp01(t));
                    reflections.add(new SoundWavePath(
                            source.name(), mic.name(),
                            List.of(sp, rp, mp),
                            totalDist, delayMs, attenuationDb, true));
                }
            }
            case CeilingShape.Domed dome -> reflections.addAll(
                    facetedCeilingReflections(source, mic, dims, material,
                            sampleCurvedCeilingFacets(dims, CURVED_CEILING_FACETS)));
            case CeilingShape.BarrelVault vault -> reflections.addAll(
                    facetedCeilingReflections(source, mic, dims, material,
                            sampleCurvedCeilingFacets(dims, CURVED_CEILING_FACETS)));
            case CeilingShape.Cathedral cathedral -> reflections.addAll(
                    facetedCeilingReflections(source, mic, dims, material,
                            sampleCathedralFacets(dims, cathedral)));
            case CeilingShape.Angled angled -> reflections.addAll(
                    facetedCeilingReflections(source, mic, dims, material,
                            sampleAngledFacets(dims, angled)));
        }
        return reflections;
    }

    /**
     * A tiny triangular facet used to approximate curved ceilings. The
     * facet is defined by three vertices; its normal and centroid are
     * computed on demand.
     */
    record CeilingFacet(Position3D a, Position3D b, Position3D c) {
        Position3D centroid() {
            return new Position3D(
                    (a.x() + b.x() + c.x()) / 3.0,
                    (a.y() + b.y() + c.y()) / 3.0,
                    (a.z() + b.z() + c.z()) / 3.0);
        }

        /** Upward-pointing unit normal (z &gt; 0). */
        double[] normal() {
            double ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
            double vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            if (nz < 0) { nx = -nx; ny = -ny; nz = -nz; }
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) return new double[] {0, 0, 1};
            return new double[] {nx / len, ny / len, nz / len};
        }
    }

    private static List<SoundWavePath> facetedCeilingReflections(
            SoundSource source, MicrophonePlacement mic,
            RoomDimensions dims, WallMaterial material,
            List<CeilingFacet> facets) {

        List<SoundWavePath> out = new ArrayList<>();
        Position3D sp = source.position();
        Position3D mp = mic.position();
        double roomW = dims.width();
        double roomL = dims.length();
        for (CeilingFacet facet : facets) {
            double[] n = facet.normal();
            Position3D c = facet.centroid();
            // Mirror source across the facet plane: image = sp - 2*((sp-c)·n)·n
            double d = (sp.x() - c.x()) * n[0] + (sp.y() - c.y()) * n[1] + (sp.z() - c.z()) * n[2];
            Position3D image = new Position3D(
                    sp.x() - 2 * d * n[0],
                    sp.y() - 2 * d * n[1],
                    sp.z() - 2 * d * n[2]);

            // Intersect the image->mic line with the facet plane to get the true
            // specular reflection point for this facet.
            double lineDx = mp.x() - image.x();
            double lineDy = mp.y() - image.y();
            double lineDz = mp.z() - image.z();
            double denom = lineDx * n[0] + lineDy * n[1] + lineDz * n[2];
            if (Math.abs(denom) < 1e-9) {
                continue;
            }
            double t = ((c.x() - image.x()) * n[0]
                    + (c.y() - image.y()) * n[1]
                    + (c.z() - image.z()) * n[2]) / denom;

            // Reject intersections behind the image or beyond the mic.
            if (t < 0.0 || t > 1.0) {
                continue;
            }

            Position3D rp = new Position3D(
                    image.x() + t * lineDx,
                    image.y() + t * lineDy,
                    image.z() + t * lineDz);

            // Reject points outside the room floor bounds.
            if (rp.x() < 0 || rp.x() > roomW || rp.y() < 0 || rp.y() > roomL) {
                continue;
            }

            // Barycentric point-in-triangle test for the reflection point.
            if (!pointInTriangle(rp, facet.a(), facet.b(), facet.c())) {
                continue;
            }

            double totalDist = sp.distanceTo(rp) + rp.distanceTo(mp);
            double delayMs = (totalDist / SPEED_OF_SOUND_MPS) * 1000.0;
            double attenuationDb = -20.0 * Math.log10(Math.max(totalDist, 0.001))
                    + 10.0 * Math.log10(1.0 - material.absorptionCoefficient());
            out.add(new SoundWavePath(
                    source.name(), mic.name(),
                    List.of(sp, rp, mp),
                    totalDist, delayMs, attenuationDb, true));
        }
        return out;
    }

    /**
     * Tests whether 3D point {@code p} lies inside the triangle
     * defined by vertices {@code a}, {@code b}, {@code c} using
     * a barycentric coordinate test.
     */
    static boolean pointInTriangle(Position3D p, Position3D a, Position3D b, Position3D c) {
        double v0x = c.x() - a.x(), v0y = c.y() - a.y(), v0z = c.z() - a.z();
        double v1x = b.x() - a.x(), v1y = b.y() - a.y(), v1z = b.z() - a.z();
        double v2x = p.x() - a.x(), v2y = p.y() - a.y(), v2z = p.z() - a.z();

        double dot00 = v0x * v0x + v0y * v0y + v0z * v0z;
        double dot01 = v0x * v1x + v0y * v1y + v0z * v1z;
        double dot02 = v0x * v2x + v0y * v2y + v0z * v2z;
        double dot11 = v1x * v1x + v1y * v1y + v1z * v1z;
        double dot12 = v1x * v2x + v1y * v2y + v1z * v2z;

        double inv = dot00 * dot11 - dot01 * dot01;
        if (Math.abs(inv) < 1e-12) return false;
        double invDenom = 1.0 / inv;
        double u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        double v = (dot00 * dot12 - dot01 * dot02) * invDenom;
        return (u >= -1e-9) && (v >= -1e-9) && (u + v <= 1.0 + 1e-9);
    }

    static List<CeilingFacet> sampleCurvedCeilingFacets(RoomDimensions dims, int resolution) {
        int n = Math.max(2, resolution);
        List<CeilingFacet> facets = new ArrayList<>();
        double w = dims.width(), l = dims.length();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double x0 = w * i / n, x1 = w * (i + 1) / n;
                double y0 = l * j / n, y1 = l * (j + 1) / n;
                Position3D p00 = new Position3D(x0, y0, dims.ceiling().heightAt(x0, y0, w, l));
                Position3D p10 = new Position3D(x1, y0, dims.ceiling().heightAt(x1, y0, w, l));
                Position3D p01 = new Position3D(x0, y1, dims.ceiling().heightAt(x0, y1, w, l));
                Position3D p11 = new Position3D(x1, y1, dims.ceiling().heightAt(x1, y1, w, l));
                facets.add(new CeilingFacet(p00, p10, p11));
                facets.add(new CeilingFacet(p00, p11, p01));
            }
        }
        return facets;
    }

    static List<CeilingFacet> sampleCathedralFacets(RoomDimensions dims, CeilingShape.Cathedral c) {
        double w = dims.width(), l = dims.length();
        List<CeilingFacet> facets = new ArrayList<>();
        if (c.ridgeAxis() == CeilingShape.Axis.X) {
            Position3D a = new Position3D(0, 0, c.eaveHeight());
            Position3D b = new Position3D(w, 0, c.eaveHeight());
            Position3D r0 = new Position3D(0, l / 2.0, c.ridgeHeight());
            Position3D r1 = new Position3D(w, l / 2.0, c.ridgeHeight());
            Position3D d = new Position3D(0, l, c.eaveHeight());
            Position3D e = new Position3D(w, l, c.eaveHeight());
            facets.add(new CeilingFacet(a, b, r1));
            facets.add(new CeilingFacet(a, r1, r0));
            facets.add(new CeilingFacet(r0, r1, e));
            facets.add(new CeilingFacet(r0, e, d));
        } else {
            Position3D a = new Position3D(0, 0, c.eaveHeight());
            Position3D b = new Position3D(0, l, c.eaveHeight());
            Position3D r0 = new Position3D(w / 2.0, 0, c.ridgeHeight());
            Position3D r1 = new Position3D(w / 2.0, l, c.ridgeHeight());
            Position3D d = new Position3D(w, 0, c.eaveHeight());
            Position3D e = new Position3D(w, l, c.eaveHeight());
            facets.add(new CeilingFacet(a, b, r1));
            facets.add(new CeilingFacet(a, r1, r0));
            facets.add(new CeilingFacet(r0, r1, e));
            facets.add(new CeilingFacet(r0, e, d));
        }
        return facets;
    }

    static List<CeilingFacet> sampleAngledFacets(RoomDimensions dims, CeilingShape.Angled a) {
        double w = dims.width(), l = dims.length();
        Position3D p00 = new Position3D(0, 0, dims.ceiling().heightAt(0, 0, w, l));
        Position3D p10 = new Position3D(w, 0, dims.ceiling().heightAt(w, 0, w, l));
        Position3D p01 = new Position3D(0, l, dims.ceiling().heightAt(0, l, w, l));
        Position3D p11 = new Position3D(w, l, dims.ceiling().heightAt(w, l, w, l));
        return List.of(new CeilingFacet(p00, p10, p11), new CeilingFacet(p00, p11, p01));
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

        // 4. Ceiling-shape suggestions: whispering-gallery focus for dome/vault
        //    and flutter-echo risk for cathedral-ridge-aligned source/mic pairs.
        suggestions.addAll(generateCeilingShapeSuggestions(config));

        return suggestions;
    }

    static List<TelemetrySuggestion> generateCeilingShapeSuggestions(RoomConfiguration config) {
        List<TelemetrySuggestion> suggestions = new ArrayList<>();
        RoomDimensions dims = config.getDimensions();
        CeilingShape shape = dims.ceiling();
        double w = dims.width(), l = dims.length();

        switch (shape) {
            case CeilingShape.Domed dome -> {
                Position3D focus = dome.focus(w, l);
                double safeMarginX = Math.min(0.5, w / 2.0);
                double safeMarginY = Math.min(0.5, l / 2.0);
                for (MicrophonePlacement mic : config.getMicrophones()) {
                    if (mic.position().distanceTo(focus) < FOCUS_PROXIMITY_METERS) {
                        double saferX = Math.max(safeMarginX, Math.min(w - safeMarginX, mic.position().x() - 1.0));
                        double saferY = Math.max(safeMarginY, Math.min(l - safeMarginY, mic.position().y()));
                        Position3D safer = new Position3D(
                                saferX,
                                saferY,
                                mic.position().z());
                        suggestions.add(new TelemetrySuggestion.AdjustMicPosition(
                                mic.name(), safer,
                                "microphone is at the focal point of the domed ceiling; "
                                        + "a whispering-gallery hot spot focuses reflections here"));
                    }
                }
            }
            case CeilingShape.BarrelVault vault -> {
                Position3D[] line = vault.focusLine(w, l);
                double focalZ = line[0].z();
                for (MicrophonePlacement mic : config.getMicrophones()) {
                    Position3D p = mic.position();
                    boolean nearFocalHeight = Math.abs(p.z() - focalZ) < FOCUS_PROXIMITY_METERS;
                    boolean onAxis = vault.axis() == CeilingShape.Axis.X
                            ? Math.abs(p.y() - l / 2.0) < FOCUS_PROXIMITY_METERS
                            : Math.abs(p.x() - w / 2.0) < FOCUS_PROXIMITY_METERS;
                    if (nearFocalHeight && onAxis) {
                        suggestions.add(new TelemetrySuggestion.AdjustMicPosition(
                                mic.name(),
                                new Position3D(p.x(), p.y(), Math.max(0.5, focalZ - 1.0)),
                                "microphone sits on the focal line of the barrel-vault ceiling; "
                                        + "this produces strong comb filtering from converging reflections"));
                    }
                }
            }
            case CeilingShape.Cathedral cathedral -> {
                boolean ridgeAlongX = cathedral.ridgeAxis() == CeilingShape.Axis.X;
                for (MicrophonePlacement mic : config.getMicrophones()) {
                    List<String> alignedSourceNames = new ArrayList<>();
                    for (SoundSource src : config.getSoundSources()) {
                        double dx = mic.position().x() - src.position().x();
                        double dy = mic.position().y() - src.position().y();
                        double horizDist = Math.sqrt(dx * dx + dy * dy);
                        if (horizDist < 1e-6) continue;
                        double cosAxis = ridgeAlongX
                                ? Math.abs(dx) / horizDist
                                : Math.abs(dy) / horizDist;
                        if (cosAxis > RIDGE_ALIGNMENT_COS_THRESHOLD) {
                            alignedSourceNames.add(src.name());
                        }
                    }
                    if (!alignedSourceNames.isEmpty()) {
                        Position3D p = mic.position();
                        double offsetMeters = 0.5;
                        Position3D suggestedPosition = ridgeAlongX
                                ? new Position3D(
                                        p.x(),
                                        Math.min(Math.max(0.5, p.y() + offsetMeters), l - 0.5),
                                        p.z())
                                : new Position3D(
                                        Math.min(Math.max(0.5, p.x() + offsetMeters), w - 0.5),
                                        p.y(),
                                        p.z());
                        suggestions.add(new TelemetrySuggestion.AdjustMicPosition(
                                mic.name(), suggestedPosition,
                                "source(s) %s and microphone are aligned with the cathedral ridge axis; "
                                        .formatted(String.join(", ", alignedSourceNames))
                                + "this geometry risks flutter echoes between the two rake planes, so move the mic slightly off the ridge axis"));
                    }
                }
            }
            case CeilingShape.Flat flat -> { /* no shape-specific suggestions */ }
            case CeilingShape.Angled angled -> { /* angled ceilings are already diffusing — no warning */ }
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
