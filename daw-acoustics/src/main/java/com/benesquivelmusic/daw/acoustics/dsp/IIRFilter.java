package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Variable-order IIR filter using Direct-Form-II.
 * Ported from RoomAcoustiCpp {@code IIRFilter}.
 */
public abstract class IIRFilter {

    protected final int order;
    protected final double T;
    protected Coefficients b;
    protected Coefficients a;
    protected Buffer y;
    protected final AtomicBoolean parametersEqual = new AtomicBoolean(false);
    protected final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean clearBuffers = new AtomicBoolean(false);

    protected IIRFilter(int filterOrder, int sampleRate) {
        this.order = filterOrder;
        this.T = 1.0 / (double) sampleRate;
        b = new Coefficients(filterOrder + 1);
        a = new Coefficients(filterOrder + 1);
        y = new Buffer(filterOrder + 1);
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return 0.0;
        if (clearBuffers.compareAndSet(true, false)) y.reset();
        if (!parametersEqual.get()) interpolateParameters(lerpFactor);

        double output = b.get(0) * input + y.get(0);
        for (int i = 0; i < order - 1; i++)
            y.set(i, b.get(i + 1) * input - a.get(i + 1) * output + y.get(i + 1));
        y.set(order - 1, b.get(order) * input - a.get(order) * output);
        return output;
    }

    public void clearBuffers() { clearBuffers.set(true); }

    public Coefficients getFrequencyResponse(Coefficients frequencies) {
        Coefficients response = new Coefficients(frequencies.length());
        for (int i = 0; i < frequencies.length(); i++) {
            double omega = Definitions.PI_2 * frequencies.get(i) * T;
            double numRe = 0, numIm = 0, denRe = 0, denIm = 0;
            for (int k = 0; k <= order; k++) {
                double ck = Math.cos(k * omega);
                double sk = Math.sin(k * omega);
                numRe += b.get(k) * ck;
                numIm -= b.get(k) * sk;
                denRe += a.get(k) * ck;
                denIm -= a.get(k) * sk;
            }
            double mag = Math.sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm));
            response.set(i, 20.0 * Definitions.log10(mag));
        }
        return response;
    }

    protected abstract void interpolateParameters(double lerpFactor);
}
