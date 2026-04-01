package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicReference;

/**
 * First-order high-shelf filter. Ported from RoomAcoustiCpp {@code HighShelf}.
 */
public final class HighShelf extends IIRFilter1 {

    private final AtomicReference<Double> targetFc;
    private final AtomicReference<Double> targetGain;
    private double currentFc;
    private double currentGain;

    public HighShelf(int sampleRate) { this(1000.0, 1.0, sampleRate); }

    public HighShelf(double fc, double gain, int sampleRate) {
        super(sampleRate);
        this.targetFc = new AtomicReference<>(fc);
        this.targetGain = new AtomicReference<>(gain);
        this.currentFc = fc;
        this.currentGain = gain;
        a0 = 1.0; b1 = 0.0;
        updateCoefficients(currentFc, currentGain);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetParameters(double fc, double gain) {
        targetFc.set(fc);
        targetGain.set(gain);
        parametersEqual.set(false);
    }

    @Override
    protected void interpolateParameters(double lerpFactor) {
        parametersEqual.set(true);
        double tFc = targetFc.get();
        double tGain = targetGain.get();
        currentFc = Interpolation.lerp(currentFc, tFc, lerpFactor);
        currentGain = Interpolation.lerp(currentGain, tGain, lerpFactor);
        if (Interpolation.equals(currentFc, tFc) && Interpolation.equals(currentGain, tGain)) {
            currentFc = tFc; currentGain = tGain;
        } else {
            parametersEqual.set(false);
        }
        updateCoefficients(currentFc, currentGain);
    }

    private void updateCoefficients(double fc, double gain) {
        double omega = Definitions.PI_2 * fc * T;
        double fPre = 2.0 * fc * Math.tan(omega / 2.0);
        double A = gain * T * fPre;
        double B = A + 2.0 * gain;
        double C = T * fPre + 2.0;
        b0 = B / C;
        a1 = (T * fPre - 2.0) / C;
        b1 = (A - 2.0 * gain) / C;
    }
}
