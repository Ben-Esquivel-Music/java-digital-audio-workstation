package com.benesquivelmusic.daw.sdk.spatial;

import java.util.Objects;

/**
 * Immutable parameters for a mono-to-Ambisonic panner.
 *
 * <p>Describes where in the sound field a mono source is placed before
 * being encoded into B-format using ACN/SN3D (AmbiX) conventions:
 * azimuth, elevation, and the target Ambisonic order. The encoder
 * itself lives in {@code daw-core}; this record is the SDK-facing data
 * carrier that can be persisted, serialized, and passed across module
 * boundaries.</p>
 *
 * <p>Conventions match {@link SphericalCoordinate}:</p>
 * <ul>
 *   <li>{@code azimuthDegrees} — counter-clockwise from front, in
 *       {@code (-180, 180]}; {@code 0} = front, {@code +90} = left,
 *       {@code 180} = behind, {@code -90} = right.</li>
 *   <li>{@code elevationDegrees} — in {@code [-90, 90]}; {@code 0} =
 *       horizontal, {@code +90} = above, {@code -90} = below.</li>
 * </ul>
 *
 * @param azimuthDegrees   the source azimuth in degrees
 * @param elevationDegrees the source elevation in degrees
 * @param order            the target Ambisonic order
 */
public record MonoAmbisonicPanner(
        double azimuthDegrees,
        double elevationDegrees,
        AmbisonicOrder order) {

    public MonoAmbisonicPanner {
        Objects.requireNonNull(order, "order must not be null");
        if (Double.isNaN(azimuthDegrees) || Double.isInfinite(azimuthDegrees)) {
            throw new IllegalArgumentException(
                    "azimuthDegrees must be finite: " + azimuthDegrees);
        }
        if (azimuthDegrees <= -180.0 || azimuthDegrees > 180.0) {
            throw new IllegalArgumentException(
                    "azimuthDegrees must be in (-180, 180]: " + azimuthDegrees);
        }
        if (Double.isNaN(elevationDegrees) || elevationDegrees < -90.0 || elevationDegrees > 90.0) {
            throw new IllegalArgumentException(
                    "elevationDegrees must be in [-90, 90]: " + elevationDegrees);
        }
    }

    /**
     * Creates a panner at the given azimuth on the horizontal plane
     * ({@code elevation = 0}).
     *
     * @param azimuthDegrees the source azimuth in degrees
     * @param order          the target Ambisonic order
     * @return a horizontal-plane panner
     */
    public static MonoAmbisonicPanner horizontal(double azimuthDegrees, AmbisonicOrder order) {
        return new MonoAmbisonicPanner(azimuthDegrees, 0.0, order);
    }

    /**
     * Returns the number of Ambisonic output channels produced by an
     * encoder built from this panner.
     *
     * @return the output channel count
     */
    public int outputChannelCount() {
        return order.channelCount();
    }
}
