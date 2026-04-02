package com.benesquivelmusic.daw.acoustics.common;

/**
 * Absorption coefficients with an associated area.
 * Extends {@link Coefficients} adding a {@code mArea} field.
 * Ported from RoomAcoustiCpp {@code Absorption<>}.
 */
public class Absorption extends Coefficients {

    /** Surface area associated with these absorption coefficients. */
    public double mArea;

    public Absorption(int length) { super(length); this.mArea = 0.0; }

    public Absorption(double[] values) { super(values); this.mArea = 0.0; }

    public Absorption(Absorption other) { super(other); this.mArea = other.mArea; }

    /**
     * Creates an Absorption from absorption coefficients R, converting to
     * pressure reflection coefficients: sqrt(1 - R[i]), clamped to [0, 1].
     * Matches the C++ {@code Absorption(const T& R)} constructor.
     */
    public static Absorption fromAbsorption(double[] R) {
        double[] reflectance = new double[R.length];
        for (int i = 0; i < R.length; i++) {
            if (R[i] < 0.0) reflectance[i] = 1.0;
            else if (R[i] > 1.0) reflectance[i] = 0.0;
            else reflectance[i] = Math.sqrt(1.0 - R[i]);
        }
        return new Absorption(reflectance);
    }

    /** Reset all values to 1.0 (full reflectance). Matches C++ Reset(). */
    public void resetToOne() {
        for (int i = 0; i < data.length; i++) data[i] = 1.0;
    }
}
