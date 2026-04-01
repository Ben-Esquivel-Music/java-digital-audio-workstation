package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

/**
 * Second-order high-pass filter (used by LinkwitzRiley). Ported from RoomAcoustiCpp {@code HighPass}.
 */
public final class HighPass extends IIRFilter2Param1 {

    public HighPass(int sampleRate) { this(1000.0, sampleRate); }

    public HighPass(double fc, int sampleRate) {
        super(fc, sampleRate);
        updateCoefficients(fc);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetFc(double fc) { setTargetParameter(fc); }

    @Override
    protected void updateCoefficients(double fc) {
        double omega = Definitions.PI_2 * fc * T;
        double cosW = Math.cos(omega);
        double alpha = Math.sin(omega) / Definitions.SQRT_2;
        double norm = 1.0 + alpha;
        b0 = (1.0 + cosW) / (2.0 * norm);
        b1 = -(1.0 + cosW) / norm;
        b2 = b0;
        a0 = 1.0;
        a1 = -2.0 * cosW / norm;
        a2 = (1.0 - alpha) / norm;
    }
}
