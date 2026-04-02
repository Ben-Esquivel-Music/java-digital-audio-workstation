package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Second-order IIR filter using Direct-Form-II.
 * Ported from RoomAcoustiCpp {@code IIRFilter2}.
 */
public abstract class IIRFilter2 {

    protected final double T;
    protected double a0, a1, a2;
    protected double b0, b1, b2;
    protected double y0, y1;
    protected final AtomicBoolean parametersEqual = new AtomicBoolean(false);
    protected final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean clearBuffers = new AtomicBoolean(false);

    protected IIRFilter2(int sampleRate) {
        this.T = 1.0 / (double) sampleRate;
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return 0.0;
        if (clearBuffers.compareAndSet(true, false)) { y0 = 0.0; y1 = 0.0; }
        if (!parametersEqual.get()) interpolateParameters(lerpFactor);
        double output = b0 * input + y0;
        y0 = b1 * input - a1 * output + y1;
        y1 = b2 * input - a2 * output;
        return output;
    }

    public void clearBuffers() { clearBuffers.set(true); }

    public Coefficients getFrequencyResponse(Coefficients frequencies) {
        Coefficients response = new Coefficients(frequencies.length());
        for (int i = 0; i < frequencies.length(); i++) {
            double omega = Definitions.PI_2 * frequencies.get(i) * T;
            double c1 = Math.cos(omega), c2 = Math.cos(2 * omega);
            double s1 = Math.sin(omega), s2 = Math.sin(2 * omega);
            double numRe = b0 + b1 * c1 + b2 * c2;
            double numIm = -b1 * s1 - b2 * s2;
            double denRe = 1.0 + a1 * c1 + a2 * c2;
            double denIm = -a1 * s1 - a2 * s2;
            double mag = Math.sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm));
            response.set(i, 20.0 * Definitions.log10(mag));
        }
        return response;
    }

    protected abstract void interpolateParameters(double lerpFactor);
}
