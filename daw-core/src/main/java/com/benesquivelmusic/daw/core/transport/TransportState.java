package com.benesquivelmusic.daw.core.transport;

/**
 * Enumerates the possible states of the DAW transport.
 */
public enum TransportState {

    /** The transport is stopped at a specific position. */
    STOPPED,

    /** The transport is actively playing back. */
    PLAYING,

    /** The transport is recording. */
    RECORDING,

    /** The transport is paused (position retained). */
    PAUSED
}
