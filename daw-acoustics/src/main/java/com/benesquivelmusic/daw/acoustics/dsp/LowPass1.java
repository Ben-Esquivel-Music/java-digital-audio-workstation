package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicReference;

/**
 * First-order low-pass filter. Ported from RoomAcoustiCpp {@code LowPass1}.
 */
public final class LowPass1 extends IIRFilter1 {

    private final AtomicReference<Double> targetFc;
    private double currentFc;

    public LowPass1(int sampleRate) { this(1000.0, sampleRate); }

    public LowPass1(double fc, int sampleRate) {
        super(sampleRate);
        this.targetFc = new AtomicReference<>(fc);
        this.currentFc = fc;
        a0 = 1.0;
        updateCoefficients(currentFc);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetFc(double fc) { targetFc.set(fc); parametersEqual.set(false); }

    @Override
    protected void interpolateParameters(double lerpFactor) {
        parametersEqual.set(true);
        double tFc = targetFc.get();
        currentFc = Interpolation.lerp(currentFc, tFc, lerpFactor);
        if (Interpolation.equals(currentFc, tFc)) currentFc = tFc;
        else parametersEqual.set(false);
        updateCoefficients(currentFc);
    }

    private void updateCoefficients(double fc) {
        double K = Definitions.PI_2 * fc * T;
        a0 = 1.0 / (K + 2.0); // a0 isn't used in getOutput
        a1 = (K - 2.0) * a0;
        b0 = K * a0;
        b1 = K * a0;
    }
}
