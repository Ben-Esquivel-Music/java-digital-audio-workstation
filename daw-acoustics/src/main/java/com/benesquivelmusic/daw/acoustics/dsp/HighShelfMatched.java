package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicReference;

/**
 * First-order matched high-shelf filter. Ported from RoomAcoustiCpp {@code HighShelfMatched}.
 */
public final class HighShelfMatched extends IIRFilter1 {

    private final AtomicReference<Double> targetFc;
    private final AtomicReference<Double> targetGain;
    private double currentFc;
    private double currentGain;

    public HighShelfMatched(double fc, double gain, int sampleRate) {
        super(sampleRate);
        this.targetFc = new AtomicReference<>(fc);
        this.targetGain = new AtomicReference<>(gain);
        this.currentFc = fc;
        this.currentGain = gain;
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
        double fm = 0.9;
        double fmSq = 1.0 / (fm * fm);
        double newFc = 2.0 * fc * T;
        newFc *= newFc;
        double Phim = 1.0 - Math.cos(Definitions.PI_1 * fm);
        Phim = 1.0 / Phim;

        double alpha = 2.0 / Definitions.PI_SQ * (fmSq + 1.0 / (gain * newFc)) - Phim;
        double beta = 2.0 / Definitions.PI_SQ * (fmSq + gain / newFc) - Phim;

        a1 = -alpha / (1.0 + alpha + Math.sqrt(1.0 + 2.0 * alpha));
        double bAll = -beta / (1.0 + beta + Math.sqrt(1.0 + 2.0 * beta));
        b0 = (1.0 + alpha) / (1.0 + bAll);
        b1 = bAll * b0;

        double DCg = (b0 + b1) / (1.0 + a1);
        b0 /= DCg;
        b1 /= DCg;
    }
}
