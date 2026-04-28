package com.benesquivelmusic.daw.sdk.spatial;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Personalized HRTF dataset imported from a SOFA file (AES69-2020).
 *
 * <p>Unlike the built-in {@link HrtfProfile} enum which selects between a
 * handful of generic head-size presets, a {@code PersonalizedHrtfProfile}
 * carries the actual measured impulse responses for an individual user —
 * typically computed by services such as Genelec Aural ID, 3D Tune-In or
 * SonicVR and distributed as {@code .sofa} files.</p>
 *
 * <p>The profile is named (the basename of the imported SOFA file by default)
 * so that project files can reference it portably; opening the project on
 * another machine that has the same profile installed under
 * {@code ~/.daw/hrtf/} restores the personalized rendering exactly.</p>
 *
 * <p>Impulse responses are stored as two parallel {@code [M][N]} arrays —
 * one per ear — already resampled to the session sample rate at import time
 * so the binaural renderer can use them without further conversion.</p>
 *
 * @param name                            human-readable, file-system-safe profile name
 * @param measurementCount                number of measured directions ({@code M})
 * @param sampleRate                      sample rate of the impulse responses in Hz
 * @param leftImpulses                    left-ear HRIRs as {@code [M][N]}
 * @param rightImpulses                   right-ear HRIRs as {@code [M][N]}
 * @param measurementPositionsSpherical   per-measurement source positions as
 *                                        {@code [M][3]} ({@code azimuth}°,
 *                                        {@code elevation}°, {@code distance} m)
 *
 * @see HrtfProfile
 * @see HrtfData
 */
public record PersonalizedHrtfProfile(
        String name,
        int measurementCount,
        double sampleRate,
        float[][] leftImpulses,
        float[][] rightImpulses,
        double[][] measurementPositionsSpherical) {

    public PersonalizedHrtfProfile {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(leftImpulses, "leftImpulses must not be null");
        Objects.requireNonNull(rightImpulses, "rightImpulses must not be null");
        Objects.requireNonNull(measurementPositionsSpherical,
                "measurementPositionsSpherical must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (measurementCount <= 0) {
            throw new IllegalArgumentException(
                    "measurementCount must be positive: " + measurementCount);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException(
                    "sampleRate must be positive: " + sampleRate);
        }
        if (leftImpulses.length != measurementCount) {
            throw new IllegalArgumentException(
                    "leftImpulses length (" + leftImpulses.length
                            + ") must equal measurementCount (" + measurementCount + ")");
        }
        if (rightImpulses.length != measurementCount) {
            throw new IllegalArgumentException(
                    "rightImpulses length (" + rightImpulses.length
                            + ") must equal measurementCount (" + measurementCount + ")");
        }
        if (measurementPositionsSpherical.length != measurementCount) {
            throw new IllegalArgumentException(
                    "measurementPositionsSpherical length ("
                            + measurementPositionsSpherical.length
                            + ") must equal measurementCount (" + measurementCount + ")");
        }
        int irLength = leftImpulses[0] != null ? leftImpulses[0].length : 0;
        if (irLength <= 0) {
            throw new IllegalArgumentException("impulse length must be positive");
        }
        for (int m = 0; m < measurementCount; m++) {
            if (leftImpulses[m] == null || leftImpulses[m].length != irLength) {
                throw new IllegalArgumentException(
                        "leftImpulses[" + m + "] must have length " + irLength);
            }
            if (rightImpulses[m] == null || rightImpulses[m].length != irLength) {
                throw new IllegalArgumentException(
                        "rightImpulses[" + m + "] must have length " + irLength);
            }
            double[] pos = measurementPositionsSpherical[m];
            if (pos == null || pos.length < 3) {
                throw new IllegalArgumentException(
                        "measurementPositionsSpherical[" + m
                                + "] must have 3 components (azimuth, elevation, distance)");
            }
        }
    }

    /** Returns the length of each impulse response in samples ({@code N}). */
    public int impulseLength() {
        return leftImpulses[0].length;
    }

    /**
     * Adapts this personalized profile to the generic {@link HrtfData} carrier
     * used by the binaural renderer.
     *
     * <p>The two ear-channel arrays are interleaved into the
     * {@code [M][2][N]} layout expected by {@link HrtfData}, and the spherical
     * positions are converted to {@link SphericalCoordinate} instances. ITD
     * delays are left at zero — they are already encoded into the impulse
     * responses themselves.</p>
     *
     * @return an {@link HrtfData} view backed by fresh copies of the impulse arrays
     */
    public HrtfData toHrtfData() {
        int m = measurementCount;
        int n = impulseLength();

        float[][][] ir = new float[m][2][n];
        float[][] delays = new float[m][2];
        List<SphericalCoordinate> positions = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            System.arraycopy(leftImpulses[i], 0, ir[i][0], 0, n);
            System.arraycopy(rightImpulses[i], 0, ir[i][1], 0, n);
            double[] p = measurementPositionsSpherical[i];
            positions.add(new SphericalCoordinate(p[0], p[1], p[2]));
        }
        return new HrtfData(name, sampleRate, positions, ir, delays);
    }
}
