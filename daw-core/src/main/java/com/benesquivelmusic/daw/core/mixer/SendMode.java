package com.benesquivelmusic.daw.core.mixer;

/**
 * Determines when a channel's audio is tapped for routing to a send bus.
 *
 * <ul>
 *   <li>{@link #PRE_FADER} — the send receives audio <em>before</em> the channel
 *       fader, so the send level is independent of the channel volume.</li>
 *   <li>{@link #POST_FADER} — the send receives audio <em>after</em> the channel
 *       fader, so adjusting the channel volume also changes the level going to
 *       the send bus.</li>
 * </ul>
 */
public enum SendMode {

    /** Audio is tapped before the channel fader (volume-independent). */
    PRE_FADER,

    /** Audio is tapped after the channel fader (volume-dependent). */
    POST_FADER
}
