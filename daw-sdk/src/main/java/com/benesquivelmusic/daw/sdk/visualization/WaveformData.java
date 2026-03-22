package com.benesquivelmusic.daw.sdk.visualization;

import java.util.Objects;

/**
 * Immutable waveform overview data for display rendering.
 *
 * <p>Contains min/max sample pairs decimated from the source audio
 * to a resolution suitable for the display width. Each element pair
 * in the arrays represents the minimum and maximum sample value for
 * a visual "column" of the waveform.</p>
 *
 * @param minValues per-column minimum sample values
 * @param maxValues per-column maximum sample values
 * @param rmsValues per-column RMS values (for filled waveform rendering)
 * @param columns   the number of display columns
 */
public record WaveformData(float[] minValues, float[] maxValues, float[] rmsValues, int columns) {

    public WaveformData {
        Objects.requireNonNull(minValues, "minValues must not be null");
        Objects.requireNonNull(maxValues, "maxValues must not be null");
        Objects.requireNonNull(rmsValues, "rmsValues must not be null");
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive: " + columns);
        }
        if (minValues.length != columns || maxValues.length != columns || rmsValues.length != columns) {
            throw new IllegalArgumentException(
                    "all arrays must have length equal to columns (" + columns + ")");
        }
        minValues = minValues.clone();
        maxValues = maxValues.clone();
        rmsValues = rmsValues.clone();
    }
}
