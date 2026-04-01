package com.benesquivelmusic.daw.acoustics.dsp;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic target/current parameter with interpolation. Ported from RoomAcoustiCpp {@code Parameter}.
 */
public final class Parameter {

    private final AtomicReference<Double> target;
    private double current;
    private final AtomicBoolean parametersEqual = new AtomicBoolean(false);

    public Parameter(double value) {
        this.target = new AtomicReference<>(value);
        this.current = value;
    }

    public void setTarget(double value) {
        if (target.get() == value) return;
        target.set(value);
        parametersEqual.set(false);
    }

    public double use(double lerpFactor) {
        if (!parametersEqual.get()) interpolate(lerpFactor);
        return current;
    }

    public boolean isZero() { return parametersEqual.get() && target.get() == 0.0; }

    public void reset(double newValue) { current = newValue; parametersEqual.set(false); }

    private void interpolate(double lerpFactor) {
        parametersEqual.set(true);
        double tgt = target.get();
        current = Interpolation.lerp(current, tgt, lerpFactor);
        if (Interpolation.equals(current, tgt)) current = tgt;
        else parametersEqual.set(false);
    }
}
