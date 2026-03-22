package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Immutable snapshot of per-band gain reduction data for a multiband compressor.
 *
 * <p>Each element in the {@code bandGainReductionDb} array corresponds to one
 * frequency band (ordered from lowest to highest frequency). Values are in dB
 * and are always ≤ 0 (negative values indicate gain reduction).</p>
 *
 * <p>This data structure is intended for driving per-band gain reduction meters
 * in the UI, a standard visual element on professional multiband compressors.</p>
 *
 * @param bandGainReductionDb per-band gain reduction in dB (each value ≤ 0)
 * @param crossoverFrequencies the crossover frequencies separating the bands, in Hz
 * @param bandCount the number of frequency bands
 */
public record MultibandCompressorData(double[] bandGainReductionDb,
                                      double[] crossoverFrequencies,
                                      int bandCount) {

    public MultibandCompressorData {
        if (bandGainReductionDb == null) {
            throw new IllegalArgumentException("bandGainReductionDb must not be null");
        }
        if (crossoverFrequencies == null) {
            throw new IllegalArgumentException("crossoverFrequencies must not be null");
        }
        if (bandCount < 2) {
            throw new IllegalArgumentException("bandCount must be at least 2: " + bandCount);
        }
        if (bandGainReductionDb.length != bandCount) {
            throw new IllegalArgumentException(
                    "bandGainReductionDb length must equal bandCount: "
                            + bandGainReductionDb.length + " != " + bandCount);
        }
        if (crossoverFrequencies.length != bandCount - 1) {
            throw new IllegalArgumentException(
                    "crossoverFrequencies length must equal bandCount - 1: "
                            + crossoverFrequencies.length + " != " + (bandCount - 1));
        }
        // Defensive copies
        bandGainReductionDb = bandGainReductionDb.clone();
        crossoverFrequencies = crossoverFrequencies.clone();
    }

    /** Returns a defensive copy of the per-band gain reduction array. */
    @Override
    public double[] bandGainReductionDb() {
        return bandGainReductionDb.clone();
    }

    /** Returns a defensive copy of the crossover frequencies array. */
    @Override
    public double[] crossoverFrequencies() {
        return crossoverFrequencies.clone();
    }
}
