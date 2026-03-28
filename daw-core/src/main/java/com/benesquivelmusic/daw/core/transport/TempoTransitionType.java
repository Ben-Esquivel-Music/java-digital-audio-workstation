package com.benesquivelmusic.daw.core.transport;

/**
 * Enumerates the types of transitions between tempo change events.
 *
 * <p>When the playback position crosses a tempo change, the transition type
 * determines how the tempo moves from the previous value to the new one.</p>
 */
public enum TempoTransitionType {

    /** The tempo changes immediately to the new value (step change). */
    INSTANT,

    /** The tempo ramps linearly from the previous value to the new value (accelerando/ritardando). */
    LINEAR,

    /** The tempo follows a smooth S-curve from the previous value to the new value. */
    CURVED
}
