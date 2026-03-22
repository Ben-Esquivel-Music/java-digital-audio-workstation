package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Ambisonic decoder type controlling the spatial reconstruction strategy.
 *
 * <p>Each decoder type applies different weighting to the spherical harmonic
 * channels, trading off between localization accuracy and robustness:</p>
 * <ul>
 *   <li><strong>BASIC</strong> — Sampling decoder; best for regular speaker layouts</li>
 *   <li><strong>MAX_RE</strong> — Max-rE weighting; improved energy localization at the expense of
 *       reduced low-frequency directivity. Preferred for most practical playback scenarios.</li>
 *   <li><strong>IN_PHASE</strong> — In-phase weighting; all speaker gains are non-negative,
 *       ideal for irregular or sparse layouts but lowest spatial resolution</li>
 * </ul>
 */
public enum DecoderType {

    /** Basic (sampling) decoder — equal weighting of all harmonics. */
    BASIC,

    /** Max-rE decoder — energy-optimized weighting for improved localization. */
    MAX_RE,

    /** In-phase decoder — non-negative gains, robust for irregular layouts. */
    IN_PHASE
}
