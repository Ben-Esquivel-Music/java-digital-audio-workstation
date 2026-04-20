package com.benesquivelmusic.daw.core.telemetry.advisor;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Service that analyzes a {@link RoomConfiguration} and proposes a ranked
 * list of {@link AcousticTreatment} placements for maximum acoustic
 * improvement per panel.
 *
 * <p>Heuristics considered:
 * <ul>
 *     <li><b>First-reflection points</b> from each source to each mic on
 *         the four walls, floor (&quot;desk-bounce&quot;) and ceiling —
 *         absorbers are proposed at those points when the underlying
 *         surface is reflective.</li>
 *     <li><b>Corner low-frequency traps</b> at each of the four vertical
 *         room corners, with effective improvement scaled by room volume
 *         (small rooms benefit the most from LF trapping).</li>
 *     <li><b>Rear-wall flutter-echo mitigation</b> — a skyline diffuser is
 *         proposed on the wall farthest behind the primary mic when that
 *         wall is reflective and more than 2 m away.</li>
 * </ul>
 *
 * <p>Treatments that have already been marked as applied on the supplied
 * room configuration ({@link RoomConfiguration#getAppliedTreatments()})
 * suppress suggestions at overlapping locations, so the advisor never
 * re-recommends a spot the user has already treated. This matches the
 * behaviour required by the acoustic-consulting workflow: once the user
 * installs a panel at a first-reflection point, the next analysis ranks
 * that spot out of its top suggestions.</p>
 */
public final class TreatmentAdvisor {

    /** Standard broadband absorber panel dimensions (meters). */
    static final double ABSORBER_PANEL_W = 0.6;
    static final double ABSORBER_PANEL_H = 1.2;

    /** Standard LF trap dimensions (meters). */
    static final double LF_TRAP_W = 0.6;
    static final double LF_TRAP_H = 1.8;

    /** Standard diffuser module dimensions (meters). */
    static final double DIFFUSER_W = 0.6;
    static final double DIFFUSER_H = 0.6;

    /** Absorption below which a surface is a candidate for absorbent treatment. */
    static final double REFLECTIVE_THRESHOLD = 0.25;

    /** Minimum distance from mic to rear wall for a diffuser suggestion. */
    static final double MIN_REAR_WALL_DISTANCE_M = 2.0;

    /** Floor point within this distance of the direct source→mic line is a desk-bounce candidate. */
    static final double DESK_BOUNCE_PROXIMITY_M = 1.5;

    /**
     * Analyzes the supplied room configuration and returns a list of
     * acoustic-treatment suggestions ordered by descending
     * {@link AcousticTreatment#predictedImprovementLufs()}.
     *
     * @param config the room to analyze (must not be {@code null})
     * @return an immutable, ranked list of treatment suggestions
     */
    public List<AcousticTreatment> analyze(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");

        RoomDimensions dims = config.getDimensions();
        SurfaceMaterialMap materials = config.getMaterialMap();
        List<AcousticTreatment> applied = config.getAppliedTreatments();

        List<AcousticTreatment> suggestions = new ArrayList<>();

        // 1. First-reflection absorbers for each source–mic pair.
        for (SoundSource source : config.getSoundSources()) {
            for (MicrophonePlacement mic : config.getMicrophones()) {
                suggestions.addAll(
                        firstReflectionSuggestions(source, mic, dims, materials));
            }
        }

        // 2. Corner LF traps.
        suggestions.addAll(cornerLfTrapSuggestions(dims, materials));

        // 3. Rear-wall flutter-echo diffuser.
        suggestions.addAll(rearWallDiffuserSuggestions(config, dims, materials));

        // Filter out suggestions whose footprint overlaps an already-applied
        // treatment so the advisor never re-recommends a treated spot.
        List<AcousticTreatment> filtered = new ArrayList<>(suggestions.size());
        for (AcousticTreatment s : suggestions) {
            if (!isSuppressedByApplied(s, applied)) {
                filtered.add(s);
            }
        }

        filtered.sort((a, b) -> Double.compare(
                b.predictedImprovementLufs(), a.predictedImprovementLufs()));
        return Collections.unmodifiableList(filtered);
    }

    // ------------------------------------------------------------------
    // First-reflection absorbers
    // ------------------------------------------------------------------

    private List<AcousticTreatment> firstReflectionSuggestions(
            SoundSource source, MicrophonePlacement mic,
            RoomDimensions dims, SurfaceMaterialMap materials) {

        List<AcousticTreatment> out = new ArrayList<>();
        Position3D sp = source.position();
        Position3D mp = mic.position();
        double directDist = sp.distanceTo(mp);

        // Image sources for the six surfaces (ceiling handled as flat plane).
        double ceilingZ = dims.height();
        RoomSurface[] surfaces = {
                RoomSurface.LEFT_WALL, RoomSurface.RIGHT_WALL,
                RoomSurface.FRONT_WALL, RoomSurface.BACK_WALL,
                RoomSurface.FLOOR, RoomSurface.CEILING
        };
        Position3D[] images = {
                new Position3D(-sp.x(), sp.y(), sp.z()),
                new Position3D(2 * dims.width() - sp.x(), sp.y(), sp.z()),
                new Position3D(sp.x(), -sp.y(), sp.z()),
                new Position3D(sp.x(), 2 * dims.length() - sp.y(), sp.z()),
                new Position3D(sp.x(), sp.y(), -sp.z()),
                new Position3D(sp.x(), sp.y(), 2 * ceilingZ - sp.z())
        };

        for (int i = 0; i < surfaces.length; i++) {
            RoomSurface surface = surfaces[i];
            WallMaterial current = materials.materialAt(surface);

            // Compute the geometric reflection point where the segment
            // image→mic crosses the surface plane.
            Position3D rp = reflectionPoint(images[i], mp, surface, dims);
            if (rp == null) continue;
            if (!isInsideSurface(rp, surface, dims)) continue;

            // Reflected path length and naive intensity contribution.
            double reflDist = images[i].distanceTo(mp);
            if (reflDist <= 0) continue;
            double pathRatio = directDist / reflDist; // 0..1
            double currentReflectivity = 1.0 - current.absorptionCoefficient();
            double treatmentReflectivity = 1.0
                    - TreatmentKind.ABSORBER_BROADBAND.effectiveAbsorption();
            double reflectivityDelta = currentReflectivity - treatmentReflectivity;

            // Skip if the surface is already quite absorptive and the delta
            // would be tiny.
            if (reflectivityDelta <= 0.05
                    || current.absorptionCoefficient() >= REFLECTIVE_THRESHOLD * 2) {
                continue;
            }

            // Predicted improvement — ranking heuristic: 6 LUFS of
            // theoretical max gain weighted by path-ratio energy and the
            // delta in reflectivity. Bounded so the number stays in a
            // sensible [0, ~3] range for typical rooms.
            double improvement = 6.0 * pathRatio * reflectivityDelta;

            // Desk-bounce (floor) gets a small bonus when the direct line
            // passes close to the reflection point.
            if (surface == RoomSurface.FLOOR
                    && distancePointToSegment(rp, sp, mp) < DESK_BOUNCE_PROXIMITY_M) {
                improvement += 0.5;
            }

            WallAttachment location = onSurface(surface, rp);
            Rectangle2D size = new Rectangle2D.Double(
                    -ABSORBER_PANEL_W / 2.0, -ABSORBER_PANEL_H / 2.0,
                    ABSORBER_PANEL_W, ABSORBER_PANEL_H);
            out.add(new AcousticTreatment(
                    TreatmentKind.ABSORBER_BROADBAND, location, size, improvement));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Corner LF traps
    // ------------------------------------------------------------------

    private List<AcousticTreatment> cornerLfTrapSuggestions(
            RoomDimensions dims, SurfaceMaterialMap materials) {

        List<AcousticTreatment> out = new ArrayList<>();

        // Small rooms benefit more from LF trapping — gain scales inversely
        // with volume (clamped).
        double volume = Math.max(dims.volume(), 1.0);
        double volumeFactor = Math.min(1.0, 60.0 / volume);

        RoomSurface[][] corners = {
                { RoomSurface.FRONT_WALL, RoomSurface.LEFT_WALL },
                { RoomSurface.FRONT_WALL, RoomSurface.RIGHT_WALL },
                { RoomSurface.BACK_WALL,  RoomSurface.LEFT_WALL },
                { RoomSurface.BACK_WALL,  RoomSurface.RIGHT_WALL }
        };

        for (RoomSurface[] corner : corners) {
            WallMaterial mA = materials.materialAt(corner[0]);
            WallMaterial mB = materials.materialAt(corner[1]);
            double cornerReflectivity =
                    (2.0 - mA.absorptionCoefficient() - mB.absorptionCoefficient()) / 2.0;

            // LF traps shine when the corner is hard.
            if (cornerReflectivity < 0.6) continue;

            double improvement = 3.5 * volumeFactor * cornerReflectivity;
            WallAttachment loc = new WallAttachment.InCorner(
                    corner[0], corner[1], dims.height() / 2.0);
            Rectangle2D size = new Rectangle2D.Double(
                    -LF_TRAP_W / 2.0, -LF_TRAP_H / 2.0, LF_TRAP_W, LF_TRAP_H);
            out.add(new AcousticTreatment(
                    TreatmentKind.ABSORBER_LF_TRAP, loc, size, improvement));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Rear-wall diffuser
    // ------------------------------------------------------------------

    private List<AcousticTreatment> rearWallDiffuserSuggestions(
            RoomConfiguration config, RoomDimensions dims, SurfaceMaterialMap materials) {

        List<MicrophonePlacement> mics = config.getMicrophones();
        if (mics.isEmpty()) return List.of();

        // Identify the wall opposite the (first) mic's facing: use back
        // wall as the &quot;rear&quot; wall — that's the convention for a
        // forward-facing mic at a mix position.
        MicrophonePlacement mic = mics.get(0);
        double rearDistance = dims.length() - mic.position().y();
        if (rearDistance < MIN_REAR_WALL_DISTANCE_M) return List.of();

        WallMaterial rear = materials.materialAt(RoomSurface.BACK_WALL);
        if (rear.absorptionCoefficient() >= REFLECTIVE_THRESHOLD) return List.of();

        double reflectivity = 1.0 - rear.absorptionCoefficient();
        double improvement = 2.0 * reflectivity * Math.min(1.0, rearDistance / 4.0);

        WallAttachment loc = new WallAttachment.OnSurface(
                RoomSurface.BACK_WALL, dims.width() / 2.0, dims.height() / 2.0);
        Rectangle2D size = new Rectangle2D.Double(
                -DIFFUSER_W / 2.0, -DIFFUSER_H / 2.0, DIFFUSER_W, DIFFUSER_H);
        return List.of(new AcousticTreatment(
                TreatmentKind.DIFFUSER_SKYLINE, loc, size, improvement));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns the 3D point where the straight segment from {@code image}
     * to {@code mic} intersects the plane of {@code surface}, or
     * {@code null} if the segment is parallel to that plane.
     */
    private static Position3D reflectionPoint(
            Position3D image, Position3D mic, RoomSurface surface, RoomDimensions dims) {
        return switch (surface) {
            case LEFT_WALL   -> intersectPlane(image, mic, 0, 0.0);
            case RIGHT_WALL  -> intersectPlane(image, mic, 0, dims.width());
            case FRONT_WALL  -> intersectPlane(image, mic, 1, 0.0);
            case BACK_WALL   -> intersectPlane(image, mic, 1, dims.length());
            case FLOOR       -> intersectPlane(image, mic, 2, 0.0);
            case CEILING     -> intersectPlane(image, mic, 2, dims.height());
        };
    }

    /** axis: 0 = x, 1 = y, 2 = z. */
    private static Position3D intersectPlane(Position3D a, Position3D b, int axis, double value) {
        double av = component(a, axis);
        double bv = component(b, axis);
        double denom = bv - av;
        if (Math.abs(denom) < 1.0e-9) return null;
        double t = (value - av) / denom;
        if (t < 0 || t > 1) return null;
        return new Position3D(
                a.x() + t * (b.x() - a.x()),
                a.y() + t * (b.y() - a.y()),
                a.z() + t * (b.z() - a.z()));
    }

    private static double component(Position3D p, int axis) {
        return switch (axis) { case 0 -> p.x(); case 1 -> p.y(); default -> p.z(); };
    }

    private static boolean isInsideSurface(Position3D p, RoomSurface surface, RoomDimensions dims) {
        double w = dims.width(), l = dims.length(), h = dims.height();
        return switch (surface) {
            case LEFT_WALL, RIGHT_WALL   -> p.y() >= 0 && p.y() <= l && p.z() >= 0 && p.z() <= h;
            case FRONT_WALL, BACK_WALL   -> p.x() >= 0 && p.x() <= w && p.z() >= 0 && p.z() <= h;
            case FLOOR, CEILING          -> p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= l;
        };
    }

    /**
     * Converts a 3D point on the given surface to the surface's local
     * {@code (u, v)} coordinate space.
     */
    private static WallAttachment onSurface(RoomSurface surface, Position3D p) {
        return switch (surface) {
            case LEFT_WALL, RIGHT_WALL   -> new WallAttachment.OnSurface(surface, p.y(), p.z());
            case FRONT_WALL, BACK_WALL   -> new WallAttachment.OnSurface(surface, p.x(), p.z());
            case FLOOR, CEILING          -> new WallAttachment.OnSurface(surface, p.x(), p.y());
        };
    }

    /** Shortest distance from point {@code p} to the line segment {@code a..b}. */
    private static double distancePointToSegment(Position3D p, Position3D a, Position3D b) {
        double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
        double ab2 = abx * abx + aby * aby + abz * abz;
        if (ab2 < 1.0e-12) return p.distanceTo(a);
        double apx = p.x() - a.x(), apy = p.y() - a.y(), apz = p.z() - a.z();
        double t = Math.max(0.0, Math.min(1.0,
                (apx * abx + apy * aby + apz * abz) / ab2));
        Position3D proj = new Position3D(
                a.x() + t * abx, a.y() + t * aby, a.z() + t * abz);
        return p.distanceTo(proj);
    }

    /**
     * Returns {@code true} when the supplied suggestion overlaps an
     * already-applied treatment — used to suppress re-recommending a spot
     * that already carries a panel.
     */
    private static boolean isSuppressedByApplied(
            AcousticTreatment suggestion, List<AcousticTreatment> applied) {
        for (AcousticTreatment a : applied) {
            if (overlaps(suggestion, a)) return true;
        }
        return false;
    }

    static boolean overlaps(AcousticTreatment a, AcousticTreatment b) {
        return switch (a.location()) {
            case WallAttachment.OnSurface sa -> switch (b.location()) {
                case WallAttachment.OnSurface sb -> sa.surface() == sb.surface()
                        && Math.abs(sa.u() - sb.u()) < (a.sizeMeters().getWidth()
                                + b.sizeMeters().getWidth()) / 2.0
                        && Math.abs(sa.v() - sb.v()) < (a.sizeMeters().getHeight()
                                + b.sizeMeters().getHeight()) / 2.0;
                case WallAttachment.InCorner cb -> false;
            };
            case WallAttachment.InCorner ca -> switch (b.location()) {
                case WallAttachment.InCorner cb -> sameCorner(ca, cb);
                case WallAttachment.OnSurface sb -> false;
            };
        };
    }

    private static boolean sameCorner(WallAttachment.InCorner a, WallAttachment.InCorner b) {
        return (a.surfaceA() == b.surfaceA() && a.surfaceB() == b.surfaceB())
                || (a.surfaceA() == b.surfaceB() && a.surfaceB() == b.surfaceA());
    }
}
