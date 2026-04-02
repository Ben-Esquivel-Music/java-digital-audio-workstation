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
        double omega = Definitions.cot(Definitions.PI_1 * fc * T); // 2 * PI * fc * T / 2
        double omega_sq = omega * omega;
        a0 = 1.0 / (1.0 + Definitions.SQRT_2 * omega + omega_sq); // a0 isn't used in getOutput
        a1 = (2.0 - 2.0 * omega_sq) * a0;
        a2 = (1.0 - Definitions.SQRT_2 * omega + omega_sq) * a0;
        b0 = omega_sq * a0;
        b1 = -2.0 * omega_sq * a0;
        b2 = omega_sq * a0;
    }
}
