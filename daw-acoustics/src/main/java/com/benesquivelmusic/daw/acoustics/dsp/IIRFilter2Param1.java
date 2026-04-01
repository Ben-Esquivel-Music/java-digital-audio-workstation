package com.benesquivelmusic.daw.acoustics.dsp;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Second-order IIR filter with a single parameter. Base for LowPass, HighPass, PeakingFilter, etc.
 * Ported from RoomAcoustiCpp {@code IIRFilter2Param1}.
 */
public abstract class IIRFilter2Param1 extends IIRFilter2 {

    private final AtomicReference<Double> target;
    private double current;

    protected IIRFilter2Param1(double parameter, int sampleRate) {
        super(sampleRate);
        this.target = new AtomicReference<>(parameter);
        this.current = parameter;
    }

    protected void setTargetParameter(double parameter) {
        target.set(parameter);
        parametersEqual.set(false);
    }

    protected abstract void updateCoefficients(double parameter);

    @Override
    protected void interpolateParameters(double lerpFactor) {
        parametersEqual.set(true);
        double tgt = target.get();
        current = Interpolation.lerp(current, tgt, lerpFactor);
        if (Interpolation.equals(current, tgt)) current = tgt;
        else parametersEqual.set(false);
        updateCoefficients(current);
    }
}
