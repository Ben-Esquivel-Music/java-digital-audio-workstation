package com.benesquivelmusic.daw.sdk.visualization;

import java.util.Objects;

/**
 * Immutable snapshot of frequency-domain spectrum data for visualization.
 *
 * <p>Contains magnitude values (in dB) for evenly-spaced frequency bins
 * produced by FFT analysis. Each bin spans {@code sampleRate / fftSize} Hz.
 * Optionally includes peak-hold magnitudes representing the maximum level
 * reached at each frequency bin.</p>
 *
 * @param magnitudesDb per-bin magnitude in dB (length = fftSize / 2)
 * @param peakHoldDb   per-bin peak-hold magnitude in dB, or {@code null} if peak hold is not enabled
 * @param fftSize      the FFT window size used to produce this data
 * @param sampleRate   the sample rate in Hz
 */
public record SpectrumData(float[] magnitudesDb, float[] peakHoldDb, int fftSize, double sampleRate) {

    public SpectrumData {
        Objects.requireNonNull(magnitudesDb, "magnitudesDb must not be null");
        magnitudesDb = magnitudesDb.clone();
        if (peakHoldDb != null) {
            peakHoldDb = peakHoldDb.clone();
        }
        if (fftSize <= 0) {
            throw new IllegalArgumentException("fftSize must be positive: " + fftSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
    }

    /**
     * Creates spectrum data without peak hold information.
     *
     * @param magnitudesDb per-bin magnitude in dB
     * @param fftSize      the FFT window size
     * @param sampleRate   the sample rate in Hz
     */
    public SpectrumData(float[] magnitudesDb, int fftSize, double sampleRate) {
        this(magnitudesDb, null, fftSize, sampleRate);
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

    /**
     * Returns whether peak hold data is available.
     *
     * @return {@code true} if peak hold magnitudes are present
     */
    public boolean hasPeakHold() {
        return peakHoldDb != null;
    }
}
