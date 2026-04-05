package com.benesquivelmusic.daw.acoustics.dsp;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 4-band Linkwitz-Riley crossover filter.
 * Ported from RoomAcoustiCpp {@code LinkwitzRiley}.
 */
public final class LinkwitzRileyFilter {

    private final double[] fm;

    private final AtomicReference<double[]> targetGains;
    private double[] currentGains;

    private final LowPass[] lowPassFilters;
    private final HighPass[] highPassFilters;

    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean gainsEqual = new AtomicBoolean(false);

    public static final double[] DEFAULT_FC = {176.0, 775.0, 3408.0};

    public LinkwitzRileyFilter(int sampleRate) {
        this(new double[]{1.0, 1.0, 1.0, 1.0}, DEFAULT_FC, sampleRate);
    }

    public LinkwitzRileyFilter(double[] gains, int sampleRate) {
        this(gains, DEFAULT_FC, sampleRate);
    }

    public LinkwitzRileyFilter(double[] gains, double[] fc, int sampleRate) {
        this.fm = calculateMidFrequencies(fc);
        this.currentGains = gains.clone();
        this.targetGains = new AtomicReference<>(gains.clone());

        // 3 crossover points = 10 LP + 10 HP sections total for a 4th-order LR
        lowPassFilters = new LowPass[10];
        highPassFilters = new HighPass[10];
        initFilters(sampleRate, fc);

        setTargetGains(gains);
        gainsEqual.set(true);
        initialised.set(true);
    }

    public double getOutput(double input, double lerpFactor) {
        if (!initialised.get()) return input;
        if (!gainsEqual.get()) interpolateGains(lerpFactor);

        // Band 0 (low): cascaded low-pass from crossover 0
        double lp0a = lowPassFilters[0].getOutput(input, lerpFactor);
        double lp0b = lowPassFilters[1].getOutput(lp0a, lerpFactor);
        double band0 = lp0b * currentGains[0];

        // Band 1: HP of crossover 0 -> LP of crossover 1
        double hp0a = highPassFilters[0].getOutput(input, lerpFactor);
        double hp0b = highPassFilters[1].getOutput(hp0a, lerpFactor);
        double lp1a = lowPassFilters[2].getOutput(hp0b, lerpFactor);
        double lp1b = lowPassFilters[3].getOutput(lp1a, lerpFactor);
        double band1 = lp1b * currentGains[1];

        // Band 2: HP of crossover 0 -> HP of crossover 1 -> LP of crossover 2
        double hp1a = highPassFilters[2].getOutput(hp0b, lerpFactor);
        double hp1b = highPassFilters[3].getOutput(hp1a, lerpFactor);
        double lp2a = lowPassFilters[4].getOutput(hp1b, lerpFactor);
        double lp2b = lowPassFilters[5].getOutput(lp2a, lerpFactor);
        double band2 = lp2b * currentGains[2];

        // Band 3 (high): HP of crossover 2
        double hp2a = highPassFilters[4].getOutput(hp1b, lerpFactor);
        double hp2b = highPassFilters[5].getOutput(hp2a, lerpFactor);
        double band3 = hp2b * currentGains[3];

        return band0 + band1 + band2 + band3;
    }

    public void setTargetGains(double[] gains) {
        targetGains.set(gains.clone());
        gainsEqual.set(false);
    }

    public void clearBuffers() {
        for (LowPass lp : lowPassFilters) if (lp != null) lp.clearBuffers();
        for (HighPass hp : highPassFilters) if (hp != null) hp.clearBuffers();
    }

    public double[] getFm() { return fm.clone(); }

    public static double[] defaultFm() { return calculateMidFrequencies(DEFAULT_FC); }

    private void initFilters(int sampleRate, double[] fc) {
        // Crossover 0
        lowPassFilters[0] = new LowPass(fc[0], sampleRate);
        lowPassFilters[1] = new LowPass(fc[0], sampleRate);
        highPassFilters[0] = new HighPass(fc[0], sampleRate);
        highPassFilters[1] = new HighPass(fc[0], sampleRate);

        // Crossover 1
        lowPassFilters[2] = new LowPass(fc[1], sampleRate);
        lowPassFilters[3] = new LowPass(fc[1], sampleRate);
        highPassFilters[2] = new HighPass(fc[1], sampleRate);
        highPassFilters[3] = new HighPass(fc[1], sampleRate);

        // Crossover 2
        lowPassFilters[4] = new LowPass(fc[2], sampleRate);
        lowPassFilters[5] = new LowPass(fc[2], sampleRate);
        highPassFilters[4] = new HighPass(fc[2], sampleRate);
        highPassFilters[5] = new HighPass(fc[2], sampleRate);
    }

    private void interpolateGains(double lerpFactor) {
        gainsEqual.set(true);
        double[] tgt = targetGains.get();
        boolean equal = true;
        for (int i = 0; i < 4; i++) {
            currentGains[i] = Interpolation.lerp(currentGains[i], tgt[i], lerpFactor);
            if (!Interpolation.equals(currentGains[i], tgt[i])) equal = false;
        }
        if (equal) System.arraycopy(tgt, 0, currentGains, 0, 4);
        else gainsEqual.set(false);
    }

    private static double[] calculateMidFrequencies(double[] fc) {
        return new double[]{
                Math.sqrt(20.0 * fc[0]),
                Math.sqrt(fc[0] * fc[1]),
                Math.sqrt(fc[1] * fc[2]),
                Math.sqrt(fc[2] * 20000.0)};
    }
}
