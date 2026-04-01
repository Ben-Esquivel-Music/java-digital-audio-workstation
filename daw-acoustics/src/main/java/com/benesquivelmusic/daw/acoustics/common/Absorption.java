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
}
