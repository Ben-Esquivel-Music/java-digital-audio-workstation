package com.benesquivelmusic.daw.sdk.model;

/**
 * The point in a mixer channel's signal flow at which a {@link Send} taps
 * the signal.
 *
 * <ul>
 *   <li>{@link #PRE_INSERTS} — before any insert effect.</li>
 *   <li>{@link #PRE_FADER} — after inserts, before the channel fader.</li>
 *   <li>{@link #POST_FADER} — after the fader (the canonical default).</li>
 * </ul>
 */
public enum SendTap {
    PRE_INSERTS,
    PRE_FADER,
    POST_FADER
}
