package com.benesquivelmusic.daw.core.audio;

/**
 * The shape of a fade-in or fade-out curve applied to an {@link AudioClip}.
 *
 * <ul>
 *   <li>{@link #LINEAR} – straight-line amplitude ramp (constant rate of change).</li>
 *   <li>{@link #EQUAL_POWER} – maintains constant perceived loudness across
 *       the fade by using a cosine/sine curve, ideal for crossfades.</li>
 *   <li>{@link #S_CURVE} – slow start and slow end with a fast middle,
 *       producing a smooth, musical-sounding transition.</li>
 * </ul>
 */
public enum FadeCurveType {

    /** Straight-line amplitude ramp. */
    LINEAR,

    /** Cosine/sine curve that preserves perceived loudness. */
    EQUAL_POWER,

    /** Smooth S-shaped curve (slow–fast–slow). */
    S_CURVE
}
