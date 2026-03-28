package com.benesquivelmusic.daw.core.performance;

import java.util.Objects;

/**
 * Immutable snapshot of a single track's DSP processing load.
 *
 * <p>Produced by {@link PerformanceMonitor} and included in the list returned
 * by {@link PerformanceMonitor#getTrackMetrics()}.</p>
 *
 * @param trackName      the display name of the track
 * @param dspLoadPercent the track's DSP processing time as a percentage of the
 *                       total available buffer time budget (0.0–100.0)
 */
public record TrackPerformanceMetrics(
        String trackName,
        double dspLoadPercent
) {

    public TrackPerformanceMetrics {
        Objects.requireNonNull(trackName, "trackName must not be null");
        if (dspLoadPercent < 0.0) {
            throw new IllegalArgumentException("dspLoadPercent must be >= 0: " + dspLoadPercent);
        }
    }
}
