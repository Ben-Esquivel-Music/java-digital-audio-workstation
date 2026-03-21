package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

/**
 * Generates waveform overview data suitable for visual display rendering.
 *
 * <p>Decimates source audio into a fixed number of display columns, each
 * containing min/max peak and RMS values. This enables efficient rendering
 * of waveform overviews at any zoom level.</p>
 *
 * <p>Supports the waveform and spectral visualization requirements from
 * the mastering-techniques research (§2 — Critical Listening).</p>
 */
public final class WaveformGenerator {

    private WaveformGenerator() {
        // utility class
    }

    /**
     * Generates waveform overview data from mono audio samples.
     *
     * @param samples     the source audio samples
     * @param numSamples  number of samples to process
     * @param columns     desired number of display columns
     * @return the waveform data ready for rendering
     */
    public static WaveformData generate(float[] samples, int numSamples, int columns) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        if (numSamples <= 0) {
            throw new IllegalArgumentException("numSamples must be positive: " + numSamples);
        }
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive: " + columns);
        }

        float[] minValues = new float[columns];
        float[] maxValues = new float[columns];
        float[] rmsValues = new float[columns];

        double samplesPerColumn = (double) numSamples / columns;

        for (int col = 0; col < columns; col++) {
            int start = (int) (col * samplesPerColumn);
            int end = (int) Math.min((col + 1) * samplesPerColumn, numSamples);
            if (end <= start) end = start + 1;
            end = Math.min(end, numSamples);

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            double sumSquares = 0.0;
            int count = 0;

            for (int i = start; i < end; i++) {
                float s = samples[i];
                if (s < min) min = s;
                if (s > max) max = s;
                sumSquares += (double) s * s;
                count++;
            }

            minValues[col] = (count > 0) ? min : 0;
            maxValues[col] = (count > 0) ? max : 0;
            rmsValues[col] = (count > 0) ? (float) Math.sqrt(sumSquares / count) : 0;
        }

        return new WaveformData(minValues, maxValues, rmsValues, columns);
    }

    /**
     * Generates waveform overview data using the full sample array.
     *
     * @param samples the source audio samples
     * @param columns desired number of display columns
     * @return the waveform data ready for rendering
     */
    public static WaveformData generate(float[] samples, int columns) {
        return generate(samples, samples.length, columns);
    }
}
