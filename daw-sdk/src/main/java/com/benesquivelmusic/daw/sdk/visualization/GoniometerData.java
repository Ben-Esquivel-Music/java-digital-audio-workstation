package com.benesquivelmusic.daw.sdk.visualization;

import java.util.Objects;

/**
 * Immutable snapshot of goniometer and vectorscope visualization data.
 *
 * <p>Provides Lissajous XY display data and polar-coordinate vectorscope
 * data for stereo field visualization. The Lissajous display plots the
 * stereo field as:
 * <ul>
 *   <li>X axis — side signal: {@code (L − R) / √2}</li>
 *   <li>Y axis — mid signal: {@code (L + R) / √2}</li>
 * </ul>
 *
 * <p>The vectorscope represents the same data in polar coordinates:
 * <ul>
 *   <li>magnitude — distance from origin</li>
 *   <li>angle — direction in radians (0 = center/mono, ±π/2 = full left/right)</li>
 * </ul>
 *
 * <p>This data drives goniometer and vectorscope displays as recommended
 * for professional mastering workflows to visualize stereo imaging and
 * ensure mono compatibility.</p>
 *
 * @param xPoints    Lissajous X coordinates (side component)
 * @param yPoints    Lissajous Y coordinates (mid component)
 * @param magnitudes vectorscope polar magnitudes
 * @param angles     vectorscope polar angles in radians
 * @param pointCount number of valid data points in each array
 */
public record GoniometerData(
        float[] xPoints,
        float[] yPoints,
        float[] magnitudes,
        float[] angles,
        int pointCount
) {

    /** Empty goniometer data (silence). */
    public static final GoniometerData EMPTY = new GoniometerData(
            new float[0], new float[0], new float[0], new float[0], 0);

    public GoniometerData {
        Objects.requireNonNull(xPoints, "xPoints must not be null");
        Objects.requireNonNull(yPoints, "yPoints must not be null");
        Objects.requireNonNull(magnitudes, "magnitudes must not be null");
        Objects.requireNonNull(angles, "angles must not be null");
        if (pointCount < 0) {
            throw new IllegalArgumentException(
                    "pointCount must not be negative: " + pointCount);
        }
        if (xPoints.length < pointCount || yPoints.length < pointCount
                || magnitudes.length < pointCount || angles.length < pointCount) {
            throw new IllegalArgumentException(
                    "arrays must have at least pointCount elements");
        }
        xPoints = xPoints.clone();
        yPoints = yPoints.clone();
        magnitudes = magnitudes.clone();
        angles = angles.clone();
    }
}
