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
        double fm = fc / Math.sqrt(gain);
        double phim = 1.0 - Math.cos(Definitions.PI_2 * fm * T);
        double phi0 = 1.0 - Math.cos(Definitions.PI_1 * T / T); // PI / sampleRate
        double phiM = 1.0 - Math.cos(Definitions.PI_2 * fc * T * Math.sqrt(gain));
        // Simplified matched shelf: p = sqrt( phim / phi0 ), then b0 / a0
        double p = phim != 0 ? Math.sqrt(phim / (2.0 * (1.0 - Math.cos(Definitions.PI_2 * fm * T)))) : 0.5;
        double gFac = gain;
        a0 = 1.0;
        b0 = gFac * (1.0 + p) / (1.0 + gFac * p);
        a1 = -(1.0 - gFac * p) / (1.0 + gFac * p);
        b1 = gFac * (1.0 - p) / (1.0 + gFac * p);
    }
}
