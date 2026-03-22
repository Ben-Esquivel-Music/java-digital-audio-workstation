package com.benesquivelmusic.daw.sdk.visualization;

import java.util.Objects;

/**
 * Immutable snapshot of frequency-domain spectrum data for visualization.
 *
 * <p>Contains magnitude values (in dB) for evenly-spaced frequency bins
 * produced by FFT analysis. Each bin spans {@code sampleRate / fftSize} Hz.</p>
 *
 * @param magnitudesDb per-bin magnitude in dB (length = fftSize / 2)
 * @param fftSize      the FFT window size used to produce this data
 * @param sampleRate   the sample rate in Hz
 */
public record SpectrumData(float[] magnitudesDb, int fftSize, double sampleRate) {

    public SpectrumData {
        Objects.requireNonNull(magnitudesDb, "magnitudesDb must not be null");
        if (fftSize <= 0) {
            throw new IllegalArgumentException("fftSize must be positive: " + fftSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
    }

    /**
     * Returns the number of frequency bins.
     *
     * @return the bin count (fftSize / 2)
     */
    public int binCount() {
        return magnitudesDb.length;
    }

    /**
     * Returns the frequency in Hz for the given bin index.
     *
     * @param bin the bin index
     * @return the center frequency of the bin in Hz
     */
    public double frequencyOfBin(int bin) {
        return (double) bin * sampleRate / fftSize;
    }
}
