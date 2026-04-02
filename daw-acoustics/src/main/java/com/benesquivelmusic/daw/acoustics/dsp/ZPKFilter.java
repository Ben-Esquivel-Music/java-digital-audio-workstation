package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Second-order ZPK-based IIR filter. Ported from RoomAcoustiCpp {@code ZPKFilter}.
 */
public final class ZPKFilter extends IIRFilter2 {

    private final AtomicReference<double[]> targetZPK;
    private double[] currentZPK;

    public ZPKFilter(int sampleRate) {
        this(new double[]{0.25, -0.99, 0.99, -0.25, 0.0}, sampleRate);
    }

    public ZPKFilter(double[] zpk, int sampleRate) {
        super(sampleRate);
        this.currentZPK = zpk.clone();
        this.targetZPK = new AtomicReference<>(zpk.clone());
        a0 = 1.0;
        updateCoefficients(currentZPK);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetParameters(double[] zpk) {
        targetZPK.set(zpk.clone());
        parametersEqual.set(false);
    }

    public void setTargetGain(double k) {
        double[] current = targetZPK.get().clone();
        current[4] = k;
        targetZPK.set(current);
        parametersEqual.set(false);
    }

    @Override
    protected void interpolateParameters(double lerpFactor) {
        parametersEqual.set(true);
        double[] tgt = targetZPK.get();
        boolean equal = true;
        for (int i = 0; i < 5; i++) {
            currentZPK[i] = Interpolation.lerp(currentZPK[i], tgt[i], lerpFactor);
            if (!Interpolation.equals(currentZPK[i], tgt[i])) equal = false;
        }
        if (equal) System.arraycopy(tgt, 0, currentZPK, 0, 5);
        else parametersEqual.set(false);
        updateCoefficients(currentZPK);
    }

    private void updateCoefficients(double[] zpk) {
        double z1 = zpk[0], z2 = zpk[1], p1 = zpk[2], p2 = zpk[3], k = zpk[4];
        b0 = k;
        b1 = -k * (z1 + z2);
        b2 = k * z1 * z2;
        a1 = -(p1 + p2);
        a2 = p1 * p2;
    }
}
