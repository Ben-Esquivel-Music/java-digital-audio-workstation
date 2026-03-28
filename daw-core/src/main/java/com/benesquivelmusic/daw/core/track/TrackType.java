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
    MASTER,

    /** A folder track that groups child tracks for organizational purposes. */
    FOLDER,

    /** A bed channel track assigned to a fixed speaker position (Dolby Atmos bed). */
    BED_CHANNEL,

    /** An audio object track with freely positionable 3D metadata (Dolby Atmos object). */
    AUDIO_OBJECT,

    /** A reference track for A/B comparison that bypasses the mixer effects chain. */
    REFERENCE
}
