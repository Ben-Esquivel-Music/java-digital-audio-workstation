package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.util.Objects;

/**
 * Derives a plausible rectangular {@link RoomDimensions} from a single
 * source-to-microphone distance, the surrounding {@link WallMaterial}, and
 * a representative source power level.
 *
 * <p>Users of a recording session rarely know their room's interior
 * dimensions in meters, but they can always measure the distance between a
 * microphone and the sound source in front of it. Given that measurement,
 * plus the kind of room they are in (via the material's absorption
 * coefficient) and how loud the source projects into the room (its power
 * in dB SPL), we can estimate a room size whose <em>critical distance</em>
 * — the radius at which direct and reverberant energy are equal — matches
 * the entered mic distance.</p>
 *
 * <p>The model uses the classical Hopkins-Stryker reverberation radius
 * {@code r_c ≈ 0.14·√R}, where {@code R = S·α/(1−α)} is the room constant,
 * {@code S} the total surface area, and {@code α} the wall absorption
 * coefficient. The volume is split into width, length, and height using a
 * Sepmeyer-like aspect ratio (1 : 4/3 : 2/3) that avoids degenerate modal
 * spacing. The result is clamped to the validation range of
 * {@link RoomDimensions} and to the slider ranges used by the telemetry
 * setup panel — never throws for finite inputs.</p>
 *
 * <p>Pure, stateless, and thread-safe.</p>
 */
public final class RoomGeometrySolver {

    /** Aspect ratio: width : length : height = 1 : 4/3 : 2/3 (Sepmeyer-like). */
    private static final double LENGTH_RATIO = 4.0 / 3.0;
    private static final double HEIGHT_RATIO = 2.0 / 3.0;

    /** Surface-area coefficient for the chosen aspect: S = SURFACE_COEFF · W². */
    private static final double SURFACE_COEFF =
            2.0 * (LENGTH_RATIO + HEIGHT_RATIO + LENGTH_RATIO * HEIGHT_RATIO);

    /** Hopkins-Stryker reverberation-radius constant. */
    private static final double RC_CONSTANT = 0.14;

    /** Reference source power in dB SPL (85 dB — typical vocal/instrument level). */
    private static final double REFERENCE_POWER_DB = 85.0;

    /**
     * Power scaling factor: each 60 dB of source power doubles/halves the
     * critical-distance ratio. Small enough to avoid the power input
     * dominating the material choice.
     */
    private static final double POWER_SCALE_DB = 60.0;

    /** Slider and safety bounds — must all be strictly positive. */
    static final double MIN_WIDTH = 1.0;
    static final double MAX_WIDTH = 60.0;
    static final double MIN_LENGTH = 1.0;
    static final double MAX_LENGTH = 80.0;
    static final double MIN_HEIGHT = 1.5;
    static final double MAX_HEIGHT = 30.0;

    private RoomGeometrySolver() {
        // utility class
    }

    /**
     * Solves for plausible room dimensions consistent with the given
     * source-to-microphone distance, wall material, and source power.
     *
     * <p>More reflective materials (concrete, marble) produce larger rooms
     * — their critical distance for a given volume is small, so matching
     * the user's mic distance requires a bigger volume. More absorbent
     * materials (acoustic foam, heavy curtains) produce smaller rooms.</p>
     *
     * <p>Results are clamped to the valid range of {@link RoomDimensions}
     * and the slider ranges used by the telemetry setup panel. Non-finite
     * inputs fall back to safe defaults rather than throwing.</p>
     *
     * @param micDistance the source-to-microphone distance in meters
     *                    (values {@code ≤ 0} or non-finite are treated as a
     *                    small positive fallback)
     * @param material    the predominant wall material (must not be null)
     * @param powerDb     the source's emitted sound power in dB SPL
     * @return a non-null, valid {@link RoomDimensions}
     */
    public static RoomDimensions solve(double micDistance, WallMaterial material, double powerDb) {
        Objects.requireNonNull(material, "material must not be null");

        double r = (Double.isFinite(micDistance) && micDistance > 0.0) ? micDistance : 0.1;
        double power = Double.isFinite(powerDb) ? powerDb : REFERENCE_POWER_DB;

        // Absorption coefficient — clamp strictly into (0, 1) to keep the
        // analytical formula finite.
        double alpha = Math.max(1e-3, Math.min(0.999, material.absorptionCoefficient()));

        // A louder source implies the user expects more direct-field
        // dominance at the mic, i.e. a larger critical-distance/mic-distance
        // ratio — which in turn implies a larger room for a given material.
        double powerFactor = Math.pow(10.0, (power - REFERENCE_POWER_DB) / POWER_SCALE_DB);

        // r_c = RC_CONSTANT · sqrt(R) = RC_CONSTANT · sqrt(S·α/(1−α))
        //     = RC_CONSTANT · W · sqrt(SURFACE_COEFF · α/(1−α))
        // Setting r_c = powerFactor · r and solving for W:
        double k = RC_CONSTANT * Math.sqrt(SURFACE_COEFF * alpha / (1.0 - alpha));
        double width = (powerFactor * r) / k;

        double length = width * LENGTH_RATIO;
        double height = width * HEIGHT_RATIO;

        width = clamp(width, MIN_WIDTH, MAX_WIDTH);
        length = clamp(length, MIN_LENGTH, MAX_LENGTH);
        height = clamp(height, MIN_HEIGHT, MAX_HEIGHT);

        return new RoomDimensions(width, length, height);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
