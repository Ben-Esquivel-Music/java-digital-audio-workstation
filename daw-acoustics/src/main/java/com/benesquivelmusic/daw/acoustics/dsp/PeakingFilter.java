package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

/**
 * Second-order peaking EQ filter. Ported from RoomAcoustiCpp {@code PeakingFilter}.
 */
public final class PeakingFilter extends IIRFilter2Param1 {

    private final double cosOmega;
    private final double alpha;

    public PeakingFilter(double fc, double Q, int sampleRate) { this(fc, 1.0, Q, sampleRate); }

    public PeakingFilter(double fc, double gain, double Q, int sampleRate) {
        super(gain, sampleRate);
        this.cosOmega = -2.0 * Math.cos(Definitions.PI_2 * fc * T);
        this.alpha = Math.sin(Definitions.PI_2 * fc * T) / (2.0 * Q);
        updateCoefficients(gain);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetGain(double gain) { setTargetParameter(gain); }

    @Override
    protected void updateCoefficients(double gain) {
        double A = Math.sqrt(gain);
        double v1 = alpha * A;
        double v2 = alpha / A;
        double norm = 1.0 + v2;
        a0 = 1.0;
        a1 = cosOmega / norm;
        a2 = (1.0 - v2) / norm;
        b0 = (1.0 + v1) / norm;
        b1 = a1;
        b2 = (1.0 - v1) / norm;
    }
}
