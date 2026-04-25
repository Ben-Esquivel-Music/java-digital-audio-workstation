package com.benesquivelmusic.daw.core.mixer;

/**
 * Identifies the signal-flow point at which a {@link Send} taps audio out of a
 * mixer channel. This is the per-send "pre/post" toggle exposed by every
 * professional mixing console.
 *
 * <ul>
 *   <li>{@link #PRE_INSERTS} — tapped <em>before</em> any insert effect.
 *       Useful for parallel-style processing where the send chain must see
 *       the dry input regardless of the channel's inserts (e.g. parallel
 *       compression, analytical metering, stem-style routing).</li>
 *   <li>{@link #PRE_FADER} — tapped <em>after</em> inserts but <em>before</em>
 *       the channel fader and pan. This is the right choice for cue/monitor
 *       sends so the headphone mix does not change when the engineer rides
 *       the control-room fader.</li>
 *   <li>{@link #POST_FADER} — tapped at the final channel output, after
 *       inserts and after the fader. This is the right choice for time-based
 *       effects (reverb, delay) so the wet signal scales with the dry
 *       signal. This is the default and matches the legacy DAW behaviour.</li>
 * </ul>
 *
 * <p>Java {@code enum} types are inherently sealed: only the listed constants
 * may exist, which lets the renderer dispatch with an exhaustive
 * pattern-matching switch (no {@code default} branch needed).</p>
 */
public enum SendTap {

    /** Send tap-point is <em>before</em> the channel insert chain. */
    PRE_INSERTS,

    /** Send tap-point is after inserts but before the channel fader. */
    PRE_FADER,

    /** Send tap-point is after the channel fader (default). */
    POST_FADER
}
