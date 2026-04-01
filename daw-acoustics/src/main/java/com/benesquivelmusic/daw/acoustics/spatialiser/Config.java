package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global configuration for the spatialiser.
 * Ported from RoomAcoustiCpp {@code Config}.
 */
public final class Config {

    public final int fs;
    public final int numFrames;
    public final int numReverbSources;
    public final double Q;
    public final Coefficients frequencyBands;

    private final double lerpFactor;
    private final AtomicReference<DiffractionModel> diffractionModel = new AtomicReference<>(DiffractionModel.BTM);
    private final AtomicReference<SpatialisationMode> spatialisationMode;
    private final AtomicReference<LateReverbModel> lateReverbModel = new AtomicReference<>(LateReverbModel.FDN);
    private final AtomicBoolean impulseResponseMode = new AtomicBoolean(false);

    public Config() {
        this(48000, 512, 12, 2.0, 0.98,
                new Coefficients(new double[]{250.0, 500.0, 1000.0, 2000.0, 4000.0}),
                SpatialisationMode.NONE);
    }

    public Config(int sampleRate, int numFrames, int numReverbSources,
                  double lerpFactor, double Q, Coefficients frequencyBands) {
        this(sampleRate, numFrames, numReverbSources, lerpFactor, Q, frequencyBands, SpatialisationMode.NONE);
    }

    public Config(int sampleRate, int numFrames, int numReverbSources,
                  double lerpFactor, double Q, Coefficients frequencyBands, SpatialisationMode mode) {
        this.fs = sampleRate;
        this.numFrames = numFrames;
        this.numReverbSources = calculateNumReverbSources(numReverbSources);
        this.lerpFactor = calculateLerpFactor(lerpFactor, sampleRate);
        this.Q = Q;
        this.frequencyBands = frequencyBands;
        this.spatialisationMode = new AtomicReference<>(mode);
    }

    public boolean getImpulseResponseMode() { return impulseResponseMode.get(); }
    public SpatialisationMode getSpatialisationMode() { return spatialisationMode.get(); }
    public LateReverbModel getLateReverbModel() { return lateReverbModel.get(); }
    public DiffractionModel getDiffractionModel() { return diffractionModel.get(); }
    public double getLerpFactor() { return impulseResponseMode.get() ? 1.0 : lerpFactor; }

    public void setDiffractionModel(DiffractionModel model) { diffractionModel.set(model); }
    public void setSpatialisationMode(SpatialisationMode mode) { spatialisationMode.set(mode); }
    public void setLateReverbModel(LateReverbModel model) { lateReverbModel.set(model); }
    public void setImpulseResponseMode(boolean mode) { impulseResponseMode.set(mode); }

    private static int calculateNumReverbSources(int max) {
        if (max < 1) return 0;
        if (max < 2) return 1;
        if (max < 4) return 2;
        if (max < 6) return 4;
        if (max < 8) return 6;
        if (max < 12) return 8;
        if (max < 16) return 12;
        if (max < 20) return 16;
        if (max < 24) return 20;
        if (max < 32) return 24;
        return 32;
    }

    private static double calculateLerpFactor(double factor, int sampleRate) {
        factor *= 96.0 / (double) sampleRate;
        return Math.max(Math.min(factor, 1.0), 1.0 / (double) sampleRate);
    }
}
