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

    /**
     * Returns {@code positionInBeats} quantized via
     * {@link #quantize(double, GridResolution, int)} when {@code snapEnabled},
     * otherwise the raw position unchanged. Hoisted (story 293 review #7) so the
     * gesture handlers (clip move / trim / fade) express the snap-or-pass
     * decision in one place instead of repeating the {@code if (snapEnabled) …}
     * block; callers that also need a lower bound clamp the pass-through result
     * themselves (the quantized branch is already {@code ≥ 0}).
     *
     * @param positionInBeats the raw beat position
     * @param snapEnabled     whether snap-to-grid is active
     * @param resolution      the active grid resolution (used only when snapping)
     * @param beatsPerBar     beats per bar (used only when snapping)
     * @return the quantized position when snapping, else {@code positionInBeats}
     */
    public static double snapIfEnabled(double positionInBeats, boolean snapEnabled,
                                       GridResolution resolution, int beatsPerBar) {
        return snapEnabled ? quantize(positionInBeats, resolution, beatsPerBar) : positionInBeats;
    }
}
