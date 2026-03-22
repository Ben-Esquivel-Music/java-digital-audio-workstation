package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Standard speaker labels for immersive audio layouts.
 *
 * <p>Covers 7.1.4 Dolby Atmos bed speakers — the standard configuration
 * for Atmos music production. Each label maps to a fixed speaker position
 * in the standard layout, identified by its ITU name and approximate
 * spherical position (azimuth, elevation) relative to the listener.</p>
 */
public enum SpeakerLabel {

    /** Left front (azimuth ≈ 30°, elevation 0°). */
    L(30.0, 0.0),

    /** Right front (azimuth ≈ 330°, elevation 0°). */
    R(330.0, 0.0),

    /** Center front (azimuth 0°, elevation 0°). */
    C(0.0, 0.0),

    /** Low-Frequency Effects (subwoofer, no directional position). */
    LFE(0.0, -90.0),

    /** Left surround side (azimuth ≈ 110°, elevation 0°). */
    LS(110.0, 0.0),

    /** Right surround side (azimuth ≈ 250°, elevation 0°). */
    RS(250.0, 0.0),

    /** Left rear surround (azimuth ≈ 150°, elevation 0°). */
    LRS(150.0, 0.0),

    /** Right rear surround (azimuth ≈ 210°, elevation 0°). */
    RRS(210.0, 0.0),

    /** Left top front (azimuth ≈ 45°, elevation ≈ 45°). */
    LTF(45.0, 45.0),

    /** Right top front (azimuth ≈ 315°, elevation ≈ 45°). */
    RTF(315.0, 45.0),

    /** Left top rear (azimuth ≈ 135°, elevation ≈ 45°). */
    LTR(135.0, 45.0),

    /** Right top rear (azimuth ≈ 225°, elevation ≈ 45°). */
    RTR(225.0, 45.0);

    private final double azimuthDegrees;
    private final double elevationDegrees;

    SpeakerLabel(double azimuthDegrees, double elevationDegrees) {
        this.azimuthDegrees = azimuthDegrees;
        this.elevationDegrees = elevationDegrees;
    }

    /** Returns the nominal azimuth in degrees for this speaker position. */
    public double azimuthDegrees() {
        return azimuthDegrees;
    }

    /** Returns the nominal elevation in degrees for this speaker position. */
    public double elevationDegrees() {
        return elevationDegrees;
    }

    /**
     * Returns the speaker position as a {@link SpatialPosition} at unit distance.
     *
     * @return the speaker position
     */
    public SpatialPosition toSpatialPosition() {
        double elevation = Math.max(-90.0, Math.min(90.0, elevationDegrees));
        return new SpatialPosition(azimuthDegrees, elevation, 1.0);
    }
}
