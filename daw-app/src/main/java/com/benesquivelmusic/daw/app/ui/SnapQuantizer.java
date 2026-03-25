package com.benesquivelmusic.daw.app.ui;

/**
 * Utility for snapping beat positions to the nearest grid boundary.
 *
 * <p>When snap-to-grid is enabled, editing operations (transport seek, note
 * placement, clip positioning) should quantize positions using
 * {@link #quantize(double, GridResolution, int)}.</p>
 */
public final class SnapQuantizer {

    private SnapQuantizer() {
        // utility class — not instantiable
    }

    /**
     * Snaps a beat position to the nearest grid boundary determined by the
     * given {@link GridResolution} and time-signature numerator (beats per bar).
     *
     * <p>The result is always non-negative. Values below zero are clamped to
     * {@code 0.0}.</p>
     *
     * @param positionInBeats the raw beat position to quantize
     * @param resolution      the active grid resolution
     * @param beatsPerBar     the number of beats per bar (e.g. 4 for 4/4 time)
     * @return the quantized beat position (nearest grid boundary, &ge; 0)
     * @throws IllegalArgumentException if {@code resolution} is {@code null}
     *                                  or {@code beatsPerBar} is not positive
     */
    public static double quantize(double positionInBeats, GridResolution resolution, int beatsPerBar) {
        if (resolution == null) {
            throw new IllegalArgumentException("resolution must not be null");
        }
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("beatsPerBar must be positive: " + beatsPerBar);
        }
        double gridSize = resolution.beatsPerGrid(beatsPerBar);
        double snapped = Math.round(positionInBeats / gridSize) * gridSize;
        return Math.max(0.0, snapped);
    }
}
