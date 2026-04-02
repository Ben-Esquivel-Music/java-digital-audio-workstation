package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Definitions;

/**
 * Second-order low-shelf filter (used by GraphicEQ). Ported from RoomAcoustiCpp {@code PeakLowShelf}.
 */
public final class PeakLowShelf extends IIRFilter2Param1 {

    private final double cosOmega;
    private final double alpha;

    public PeakLowShelf(double fc, double Q, int sampleRate) { this(fc, 1.0, Q, sampleRate); }

    public PeakLowShelf(double fc, double gain, double Q, int sampleRate) {
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
        double A = Math.sqrt(gain);
        double v1 = A + 1.0;
        double v2 = A - 1.0;
        double v3 = v1 * cosOmega;
        double v4 = v2 * cosOmega;
        double v5 = Math.sqrt(A) * alpha; // 2 * sqrt(A) * alpha_standard
        double norm = v1 + v4 + v5;
        a0 = 1.0;
        a1 = (-2.0 * (v2 + v3)) / norm;
        a2 = (v1 + v4 - v5) / norm;
        b0 = A * (v1 - v4 + v5) / norm;
        b1 = 2.0 * A * (v2 - v3) / norm;
        b2 = A * (v1 - v4 - v5) / norm;
    }
}
