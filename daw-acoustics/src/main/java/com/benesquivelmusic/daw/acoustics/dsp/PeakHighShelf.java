package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

/**
 * Second-order high-shelf filter (used by GraphicEQ). Ported from RoomAcoustiCpp {@code PeakHighShelf}.
 */
public final class PeakHighShelf extends IIRFilter2Param1 {

    private final double cosOmega;
    private final double alpha;

    public PeakHighShelf(double fc, double Q, int sampleRate) { this(fc, 1.0, Q, sampleRate); }

    public PeakHighShelf(double fc, double gain, double Q, int sampleRate) {
        super(gain, sampleRate);
        this.cosOmega = Math.cos(Definitions.PI_2 * fc * T);
        this.alpha = Math.sin(Definitions.PI_2 * fc * T) / Q;
        updateCoefficients(gain);
        parametersEqual.set(true);
        initialised.set(true);
    }

    public void setTargetGain(double gain) { setTargetParameter(gain); }

    @Override
    protected void updateCoefficients(double gain) {
        double sqrtA = Math.sqrt(gain);
        double norm = (sqrtA + 1.0) - (sqrtA - 1.0) * cosOmega + alpha * sqrtA;
        b0 = (sqrtA * ((sqrtA + 1.0) + (sqrtA - 1.0) * cosOmega + alpha * sqrtA)) / norm;
        b1 = (-2.0 * sqrtA * ((sqrtA - 1.0) + (sqrtA + 1.0) * cosOmega)) / norm;
        b2 = (sqrtA * ((sqrtA + 1.0) + (sqrtA - 1.0) * cosOmega - alpha * sqrtA)) / norm;
        a0 = 1.0;
        a1 = (2.0 * ((sqrtA - 1.0) - (sqrtA + 1.0) * cosOmega)) / norm;
        a2 = ((sqrtA + 1.0) - (sqrtA - 1.0) * cosOmega - alpha * sqrtA) / norm;
    }
}
