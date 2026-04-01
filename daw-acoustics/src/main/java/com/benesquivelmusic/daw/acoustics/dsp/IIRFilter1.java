package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * First-order IIR filter using Direct-Form-II.
 * Ported from RoomAcoustiCpp {@code IIRFilter1}.
 */
public abstract class IIRFilter1 {

    protected final double T;
    protected double a0, a1;
    protected double b0, b1;
    protected double y0;
    protected final AtomicBoolean parametersEqual = new AtomicBoolean(false);
    protected final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean clearBuffers = new AtomicBoolean(false);

    protected IIRFilter1(int sampleRate) {
        this.T = 1.0 / (double) sampleRate;
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return input;
        if (clearBuffers.compareAndSet(true, false)) y0 = 0.0;
        if (!parametersEqual.get()) interpolateParameters(lerpFactor);
        double output = b0 * input + y0;
        y0 = b1 * input - a1 * output;
        return output;
    }

    public void clearBuffers() { clearBuffers.set(true); }

    public Coefficients getFrequencyResponse(Coefficients frequencies) {
        Coefficients response = new Coefficients(frequencies.length());
        for (int i = 0; i < frequencies.length(); i++) {
            double omega = Definitions.PI_2 * frequencies.get(i) * T;
            double cosW = Math.cos(omega);
            double sinW = Math.sin(omega);
            double numRe = b0 + b1 * cosW;
            double numIm = -b1 * sinW;
            double denRe = a0 + a1 * cosW;
            double denIm = -a1 * sinW;
            response.set(i, Math.sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)));
        }
        return response;
    }

    protected abstract void interpolateParameters(double lerpFactor);
}
