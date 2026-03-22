package com.benesquivelmusic.daw.core.spatial.ambisonics;

/**
 * Computes real spherical harmonic coefficients using ACN (Ambisonic Channel
 * Number) ordering and SN3D (Schmidt Semi-Normalization).
 *
 * <p>ACN ordering maps degree {@code l} and order {@code m} to channel index
 * {@code n = l² + l + m}. For a given order <em>N</em>, the total channel
 * count is {@code (N+1)²}.</p>
 *
 * <p>SN3D normalization ensures that each harmonic has unit power when
 * integrated over the sphere, making it the standard normalization for
 * the AmbiX format used in modern spatial audio production.</p>
 */
public final class SphericalHarmonics {

    private SphericalHarmonics() {
        // utility class
    }

    /**
     * Computes the real spherical harmonic coefficients for a source at the
     * given direction, up to the specified Ambisonic order.
     *
     * <p>The returned array has {@code (order+1)²} elements in ACN order
     * with SN3D normalization.</p>
     *
     * @param azimuthRadians   the azimuth angle in radians (0 = front, π/2 = left)
     * @param elevationRadians the elevation angle in radians (0 = horizontal, π/2 = above)
     * @param order            the maximum Ambisonic order (1, 2, or 3)
     * @return the SN3D-normalized spherical harmonic coefficients in ACN order
     */
    public static double[] encode(double azimuthRadians, double elevationRadians, int order) {
        if (order < 1 || order > 3) {
            throw new IllegalArgumentException("order must be 1, 2, or 3: " + order);
        }

        int channelCount = (order + 1) * (order + 1);
        double[] coefficients = new double[channelCount];

        double cosEl = Math.cos(elevationRadians);
        double sinEl = Math.sin(elevationRadians);
        double cosAz = Math.cos(azimuthRadians);
        double sinAz = Math.sin(azimuthRadians);

        // Order 0: ACN 0 = Y_0^0 (W — omnidirectional)
        coefficients[0] = 1.0;

        // Order 1: ACN 1 = Y_1^{-1} (Y), ACN 2 = Y_1^0 (Z), ACN 3 = Y_1^1 (X)
        coefficients[1] = sinAz * cosEl;         // Y
        coefficients[2] = sinEl;                  // Z
        coefficients[3] = cosAz * cosEl;          // X

        if (order >= 2) {
            double cos2Az = Math.cos(2.0 * azimuthRadians);
            double sin2Az = Math.sin(2.0 * azimuthRadians);
            double sinEl2 = sinEl * sinEl;
            double cosEl2 = cosEl * cosEl;

            // Order 2: ACN 4–8
            // Y_2^{-2} = sqrt(3/4) * cos(el)^2 * sin(2*az)
            coefficients[4] = SQRT3_OVER_2 * cosEl2 * sin2Az;
            // Y_2^{-1} = sqrt(3/4) * sin(2*el) * sin(az)
            coefficients[5] = SQRT3_OVER_2 * 2.0 * sinEl * cosEl * sinAz;
            // Y_2^0 = 0.5 * (3*sin(el)^2 - 1)
            coefficients[6] = 0.5 * (3.0 * sinEl2 - 1.0);
            // Y_2^1 = sqrt(3/4) * sin(2*el) * cos(az)
            coefficients[7] = SQRT3_OVER_2 * 2.0 * sinEl * cosEl * cosAz;
            // Y_2^2 = sqrt(3/4) * cos(el)^2 * cos(2*az)
            coefficients[8] = SQRT3_OVER_2 * cosEl2 * cos2Az;

            if (order >= 3) {
                double cos3Az = Math.cos(3.0 * azimuthRadians);
                double sin3Az = Math.sin(3.0 * azimuthRadians);
                double cosEl3 = cosEl2 * cosEl;

                // Order 3: ACN 9–15
                // Y_3^{-3} = sqrt(5/8) * cos(el)^3 * sin(3*az)
                coefficients[9] = SQRT5_OVER_2SQRT2 * cosEl3 * sin3Az;
                // Y_3^{-2} = sqrt(15/4) * sin(el) * cos(el)^2 * sin(2*az)
                coefficients[10] = SQRT15_OVER_2 * sinEl * cosEl2 * sin2Az;
                // Y_3^{-1} = sqrt(3/8) * cos(el) * (5*sin(el)^2 - 1) * sin(az)
                coefficients[11] = SQRT3_OVER_2SQRT2 * cosEl * (5.0 * sinEl2 - 1.0) * sinAz;
                // Y_3^0 = 0.5 * sin(el) * (5*sin(el)^2 - 3)
                coefficients[12] = 0.5 * sinEl * (5.0 * sinEl2 - 3.0);
                // Y_3^1 = sqrt(3/8) * cos(el) * (5*sin(el)^2 - 1) * cos(az)
                coefficients[13] = SQRT3_OVER_2SQRT2 * cosEl * (5.0 * sinEl2 - 1.0) * cosAz;
                // Y_3^2 = sqrt(15/4) * sin(el) * cos(el)^2 * cos(2*az)
                coefficients[14] = SQRT15_OVER_2 * sinEl * cosEl2 * cos2Az;
                // Y_3^3 = sqrt(5/8) * cos(el)^3 * cos(3*az)
                coefficients[15] = SQRT5_OVER_2SQRT2 * cosEl3 * cos3Az;
            }
        }

        return coefficients;
    }

    /**
     * Computes the SN3D normalization factor for degree {@code l} and order {@code m}.
     *
     * @param l the spherical harmonic degree (≥ 0)
     * @param m the spherical harmonic order (|m| ≤ l)
     * @return the SN3D normalization factor
     */
    public static double sn3dNormalization(int l, int m) {
        int absM = Math.abs(m);
        double delta = (m == 0) ? 1.0 : 0.0;
        return Math.sqrt((2.0 - delta) * factorial(l - absM) / factorial(l + absM));
    }

    /**
     * Converts an ACN channel index to its degree and order.
     *
     * @param acn the ACN channel index
     * @return a two-element array {@code [degree, order]}
     */
    public static int[] acnToDegreeOrder(int acn) {
        if (acn < 0) {
            throw new IllegalArgumentException("ACN index must be non-negative: " + acn);
        }
        int l = (int) Math.floor(Math.sqrt(acn));
        int m = acn - l * l - l;
        return new int[]{l, m};
    }

    /**
     * Converts degree and order to an ACN channel index.
     *
     * @param l the degree
     * @param m the order
     * @return the ACN channel index
     */
    public static int degreeOrderToAcn(int l, int m) {
        return l * l + l + m;
    }

    // Pre-computed constants for SN3D normalization
    private static final double SQRT3_OVER_2 = Math.sqrt(3.0) / 2.0;
    private static final double SQRT5_OVER_2SQRT2 = Math.sqrt(5.0) / (2.0 * Math.sqrt(2.0));
    private static final double SQRT15_OVER_2 = Math.sqrt(15.0) / 2.0;
    private static final double SQRT3_OVER_2SQRT2 = Math.sqrt(3.0) / (2.0 * Math.sqrt(2.0));

    private static double factorial(int n) {
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
