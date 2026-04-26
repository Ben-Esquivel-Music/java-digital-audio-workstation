package com.benesquivelmusic.daw.core.mixer;

/**
 * Determines how a fader (or other linked numeric parameter) edit on one
 * member of a {@link ChannelLink} pair propagates to the other member.
 *
 * <p>This mirrors the standard "Link" mode dropdown found on every
 * professional DAW (Pro Tools, Logic, Cubase, Studio One): the user picks
 * either a strict "values are equal" mode or an "offsets are preserved"
 * mode for relative rides.</p>
 *
 * <ul>
 *   <li>{@link #ABSOLUTE} — the partner channel's value is set to
 *       the same value as the source channel after every edit. The two
 *       channels' values are always equal.</li>
 *   <li>{@link #RELATIVE} — the partner channel preserves whatever
 *       offset existed before the edit and is incremented by the same
 *       delta the source moved. This is the standard "preserve offset,
 *       move together" mode used for stereo pairs whose individual
 *       balance was already trimmed.</li>
 * </ul>
 *
 * @see ChannelLink
 * @see ChannelLinkManager
 */
public enum LinkMode {
    /** Partner channel mirrors the source channel's exact value. */
    ABSOLUTE,
    /** Partner channel preserves its offset; both faders move by the same delta. */
    RELATIVE
}
