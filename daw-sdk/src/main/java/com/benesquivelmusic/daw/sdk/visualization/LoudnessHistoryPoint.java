package com.benesquivelmusic.daw.sdk.visualization;

/**
 * A single data point in a loudness-over-time history.
 *
 * <p>Used to build time-based loudness visualization graphs showing
 * how momentary, short-term, and integrated loudness evolve over the
 * duration of a program.</p>
 *
 * @param timestampSeconds time offset from the start of measurement in seconds
 * @param momentaryLufs    momentary loudness (400 ms window) at this point
 * @param shortTermLufs    short-term loudness (3 s window) at this point
 * @param integratedLufs   integrated loudness up to this point
 */
public record LoudnessHistoryPoint(
        double timestampSeconds,
        double momentaryLufs,
        double shortTermLufs,
        double integratedLufs
) {}
