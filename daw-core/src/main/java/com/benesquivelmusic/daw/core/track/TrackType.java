package com.benesquivelmusic.daw.core.track;

/**
 * Enumerates the types of tracks in the DAW.
 */
public enum TrackType {

    /** An audio track for recording and playing back audio signals. */
    AUDIO,

    /** A MIDI track for recording and playing back MIDI data. */
    MIDI,

    /** An auxiliary/bus track for routing and sub-mixing. */
    AUX,

    /** The master output track. */
    MASTER
}
