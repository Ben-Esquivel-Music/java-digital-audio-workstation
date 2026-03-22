package com.benesquivelmusic.daw.sdk.mastering;

/**
 * Crossfade curve types for transitions between album tracks.
 *
 * <p>Each curve defines a different gain envelope for the outgoing
 * and incoming tracks during a crossfade region.</p>
 */
public enum CrossfadeCurve {

    /** Linear crossfade — straight-line gain ramps. */
    LINEAR,

    /** Equal-power crossfade — maintains constant perceived loudness. */
    EQUAL_POWER,

    /** S-curve crossfade — smooth ease-in/ease-out transition. */
    S_CURVE
}
