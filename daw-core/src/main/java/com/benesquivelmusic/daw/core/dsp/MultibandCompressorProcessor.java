package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.visualization.MultibandCompressorData;

import java.util.Arrays;
import java.util.Objects;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Multiband dynamic range compressor that splits the audio signal into
 * configurable frequency bands and applies independent compression to each band.
 *
 * <p>Implements the multiband compression technique described in the
 * mastering-techniques research (§4 — Dynamics Processing), enabling precise
 * dynamic control of specific frequency ranges without affecting others —
 * essential for mastering and advanced mixing.</p>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Linkwitz-Riley 4th-order (LR4) crossover filter network splits the
 *       signal into 2, 3, or 4 frequency bands.</li>
 *   <li>Each band has an independent {@link CompressorProcessor} with its own
 *       threshold, ratio, attack, release, knee, and makeup gain.</li>
 *   <li>Per-band solo and bypass controls allow auditioning individual bands.</li>
 *   <li>Phase-correct band recombination preserves the original signal when all
 *       bands are uncompressed.</li>
 *   <li>Per-band gain reduction metering provides visualization data via
 *       {@link #getMeteringData()}.</li>
 * </ul>
 *
 * <h2>Band Layout</h2>
 * <p>For N crossover frequencies, there are N+1 bands:
 * <pre>
 *   2-band: [low | high]                    — 1 crossover
 *   3-band: [low | mid | high]              — 2 crossovers
 *   4-band: [low | low-mid | high-mid | high] — 3 crossovers
 * </pre>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class MultibandCompressorProcessor implements AudioProcessor {

    private final int channels;
    private final double sampleRate;
    private final int bandCount;
    private final double[] crossoverFrequencies;

    // Per-channel crossover filters: [crossoverIndex][channelIndex]
    private final CrossoverFilter[][] crossovers;

    // Per-band compressors
    private final CompressorProcessor[] bandCompressors;

    // Per-band controls
    private final boolean[] bandBypassed;
    private final boolean[] bandSoloed;
    private final double[] bandMakeupGainDb;

    // Working buffers for band splitting (reused across process calls)
    // bandBuffers[bandIndex][channelIndex][frame]
    private final float[][][] bandBuffers;

    // Temporary buffers used during crossover splitting
    private final float[][] tempLow;
    private final float[][] tempHigh;

    private int bufferSize;

    /**
     * Creates a multiband compressor with the specified crossover frequencies.
     *
     * <p>The number of bands equals {@code crossoverFrequencies.length + 1}.
     * Supported configurations are 2-band (1 crossover), 3-band (2 crossovers),
     * and 4-band (3 crossovers).</p>
     *
     * @param channels             number of audio channels
     * @param sampleRate           the sample rate in Hz
     * @param crossoverFrequencies the crossover frequencies in Hz, in ascending order
     * @throws IllegalArgumentException if parameters are invalid
     */
    public MultibandCompressorProcessor(int channels, double sampleRate,
                                        double[] crossoverFrequencies) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        Objects.requireNonNull(crossoverFrequencies, "crossoverFrequencies must not be null");
        if (crossoverFrequencies.length < 1 || crossoverFrequencies.length > 3) {
            throw new IllegalArgumentException(
                    "crossoverFrequencies must have 1 to 3 elements (2 to 4 bands): "
                            + crossoverFrequencies.length);
        }
        // Validate ascending order and Nyquist
        double nyquist = sampleRate / 2.0;
        for (int i = 0; i < crossoverFrequencies.length; i++) {
            if (crossoverFrequencies[i] <= 0 || crossoverFrequencies[i] >= nyquist) {
                throw new IllegalArgumentException(
                        "crossover frequency must be in (0, " + nyquist + "): "
                                + crossoverFrequencies[i]);
            }
            if (i > 0 && crossoverFrequencies[i] <= crossoverFrequencies[i - 1]) {
                throw new IllegalArgumentException(
                        "crossover frequencies must be in ascending order");
            }
        }

        this.channels = channels;
        this.sampleRate = sampleRate;
        this.crossoverFrequencies = crossoverFrequencies.clone();
        this.bandCount = crossoverFrequencies.length + 1;

        // Create crossover filters: one per crossover point per channel
        this.crossovers = new CrossoverFilter[crossoverFrequencies.length][channels];
        for (int xover = 0; xover < crossoverFrequencies.length; xover++) {
            for (int ch = 0; ch < channels; ch++) {
                crossovers[xover][ch] = new CrossoverFilter(sampleRate, crossoverFrequencies[xover]);
            }
        }

        // Create per-band compressors
        this.bandCompressors = new CompressorProcessor[bandCount];
        for (int band = 0; band < bandCount; band++) {
            bandCompressors[band] = new CompressorProcessor(channels, sampleRate);
        }

        // Per-band controls
        this.bandBypassed = new boolean[bandCount];
        this.bandSoloed = new boolean[bandCount];
        this.bandMakeupGainDb = new double[bandCount];

        // Allocate working buffers with default size
        this.bufferSize = 1024;
        this.bandBuffers = new float[bandCount][channels][bufferSize];
        this.tempLow = new float[channels][bufferSize];
        this.tempHigh = new float[channels][bufferSize];
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        ensureBufferCapacity(numFrames);

        // Split input signal into frequency bands using cascaded crossovers
        splitBands(inputBuffer, numFrames);

        // Process each band through its compressor
        for (int band = 0; band < bandCount; band++) {
            if (bandBypassed[band]) {
                // Bypass: band data from splitBands() passes through uncompressed
            } else {
                float[][] bandInput = bandBuffers[band];
                float[][] bandOutput = new float[channels][numFrames];
                bandCompressors[band].process(bandInput, bandOutput, numFrames);
                for (int ch = 0; ch < channels; ch++) {
                    System.arraycopy(bandOutput[ch], 0, bandBuffers[band][ch], 0, numFrames);
                }
            }

            // Apply per-band makeup gain
            if (bandMakeupGainDb[band] != 0.0) {
                double gainLinear = Math.pow(10.0, bandMakeupGainDb[band] / 20.0);
                for (int ch = 0; ch < channels; ch++) {
                    for (int frame = 0; frame < numFrames; frame++) {
                        bandBuffers[band][ch][frame] *= (float) gainLinear;
                    }
                }
            }
        }

        // Recombine bands: check if any band is soloed
        boolean anySoloed = false;
        for (boolean s : bandSoloed) {
            if (s) {
                anySoloed = true;
                break;
            }
        }

        // Sum bands into output
        for (int ch = 0; ch < Math.min(channels, outputBuffer.length); ch++) {
            Arrays.fill(outputBuffer[ch], 0, numFrames, 0.0f);
            for (int band = 0; band < bandCount; band++) {
                if (anySoloed && !bandSoloed[band]) {
                    continue; // Skip non-soloed bands when soloing
                }
                for (int frame = 0; frame < numFrames; frame++) {
                    outputBuffer[ch][frame] += bandBuffers[band][ch][frame];
                }
            }
        }
    }

    /**
     * Splits the input signal into frequency bands using cascaded LR4 crossovers.
     *
     * <p>For a 2-band setup (1 crossover), the input is split into low and high.
     * For 3 bands (2 crossovers), the input is first split at crossover[0] into
     * low and rest; the rest is then split at crossover[1] into mid and high.
     * For 4 bands (3 crossovers), an additional split is applied.</p>
     */
    private void splitBands(float[][] inputBuffer, int numFrames) {
        int numCrossovers = crossoverFrequencies.length;

        if (numCrossovers == 1) {
            // 2-band: single split
            for (int ch = 0; ch < channels; ch++) {
                crossovers[0][ch].process(inputBuffer[ch],
                        bandBuffers[0][ch], bandBuffers[1][ch], 0, numFrames);
            }
        } else if (numCrossovers == 2) {
            // 3-band: split at low crossover, then split high portion at mid crossover
            for (int ch = 0; ch < channels; ch++) {
                crossovers[0][ch].process(inputBuffer[ch],
                        bandBuffers[0][ch], tempHigh[ch], 0, numFrames);
                crossovers[1][ch].process(tempHigh[ch],
                        bandBuffers[1][ch], bandBuffers[2][ch], 0, numFrames);
            }
        } else {
            // 4-band: split at low, then mid from upper, then high-mid/high from upper
            for (int ch = 0; ch < channels; ch++) {
                crossovers[0][ch].process(inputBuffer[ch],
                        bandBuffers[0][ch], tempHigh[ch], 0, numFrames);
                crossovers[1][ch].process(tempHigh[ch],
                        bandBuffers[1][ch], tempLow[ch], 0, numFrames);
                crossovers[2][ch].process(tempLow[ch],
                        bandBuffers[2][ch], bandBuffers[3][ch], 0, numFrames);
            }
        }
    }

    private void ensureBufferCapacity(int numFrames) {
        if (numFrames > bufferSize) {
            bufferSize = numFrames;
            for (int band = 0; band < bandCount; band++) {
                for (int ch = 0; ch < channels; ch++) {
                    bandBuffers[band][ch] = new float[bufferSize];
                }
            }
            for (int ch = 0; ch < channels; ch++) {
                tempLow[ch] = new float[bufferSize];
                tempHigh[ch] = new float[bufferSize];
            }
        }
    }

    // --- Per-band compressor parameter access ---

    /**
     * Returns the number of frequency bands.
     *
     * @return band count (2, 3, or 4)
     */
    public int getBandCount() {
        return bandCount;
    }

    /**
     * Returns a defensive copy of the crossover frequencies.
     *
     * @return crossover frequencies in Hz
     */
    public double[] getCrossoverFrequencies() {
        return crossoverFrequencies.clone();
    }

    /**
     * Returns the compressor for the given band, allowing independent parameter control.
     *
     * @param band the band index (0 = lowest frequency band)
     * @return the per-band compressor
     * @throws IndexOutOfBoundsException if band is out of range
     */
    public CompressorProcessor getBandCompressor(int band) {
        checkBandIndex(band);
        return bandCompressors[band];
    }

    // --- Per-band solo / bypass ---

    /**
     * Sets the bypass state for a band. When bypassed, no compression is applied
     * to the band, but it still contributes to the output sum.
     *
     * @param band     the band index
     * @param bypassed {@code true} to bypass compression
     */
    public void setBandBypassed(int band, boolean bypassed) {
        checkBandIndex(band);
        bandBypassed[band] = bypassed;
    }

    /**
     * Returns whether a band is bypassed.
     *
     * @param band the band index
     * @return {@code true} if bypassed
     */
    public boolean isBandBypassed(int band) {
        checkBandIndex(band);
        return bandBypassed[band];
    }

    /**
     * Sets the solo state for a band. When any band is soloed, only soloed
     * bands are included in the output.
     *
     * @param band   the band index
     * @param soloed {@code true} to solo the band
     */
    public void setBandSoloed(int band, boolean soloed) {
        checkBandIndex(band);
        bandSoloed[band] = soloed;
    }

    /**
     * Returns whether a band is soloed.
     *
     * @param band the band index
     * @return {@code true} if soloed
     */
    public boolean isBandSoloed(int band) {
        checkBandIndex(band);
        return bandSoloed[band];
    }

    // --- Per-band makeup gain ---

    /**
     * Sets the per-band makeup gain in dB. This is applied after the per-band
     * compressor (and is separate from the compressor's own makeup gain).
     *
     * @param band         the band index
     * @param makeupGainDb the makeup gain in dB
     */
    public void setBandMakeupGainDb(int band, double makeupGainDb) {
        checkBandIndex(band);
        bandMakeupGainDb[band] = makeupGainDb;
    }

    /**
     * Returns the per-band makeup gain in dB.
     *
     * @param band the band index
     * @return the makeup gain in dB
     */
    public double getBandMakeupGainDb(int band) {
        checkBandIndex(band);
        return bandMakeupGainDb[band];
    }

    // --- Metering ---

    /**
     * Returns per-band gain reduction in dB for the specified band.
     *
     * @param band the band index
     * @return gain reduction in dB (always ≤ 0)
     */
    public double getBandGainReductionDb(int band) {
        checkBandIndex(band);
        return bandCompressors[band].getGainReductionDb();
    }

    /**
     * Returns a snapshot of the current metering data for all bands.
     *
     * @return immutable metering data
     */
    public MultibandCompressorData getMeteringData() {
        double[] gainReductions = new double[bandCount];
        for (int band = 0; band < bandCount; band++) {
            gainReductions[band] = bandCompressors[band].getGainReductionDb();
        }
        return new MultibandCompressorData(gainReductions, crossoverFrequencies.clone(), bandCount);
    }

    // --- AudioProcessor ---

    @Override
    public void reset() {
        for (CrossoverFilter[] perChannel : crossovers) {
            for (CrossoverFilter filter : perChannel) {
                filter.reset();
            }
        }
        for (CompressorProcessor comp : bandCompressors) {
            comp.reset();
        }
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }

    private void checkBandIndex(int band) {
        if (band < 0 || band >= bandCount) {
            throw new IndexOutOfBoundsException(
                    "band index " + band + " out of range [0, " + (bandCount - 1) + "]");
        }
    }
}
