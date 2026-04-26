package com.benesquivelmusic.daw.sdk.model;

/**
 * The kind of content carried by a {@link Track}.
 *
 * <p>This enum is part of the immutable record-based domain model and is
 * intentionally minimal — it mirrors the user-visible track classification
 * without carrying any behaviour.</p>
 */
public enum TrackType {
    /** A track that hosts {@link AudioClip audio clips}. */
    AUDIO,
    /** A track that hosts {@link MidiClip MIDI clips}. */
    MIDI,
    /** A bus track that aggregates the output of other tracks. */
    BUS,
    /** A folder track used purely for grouping in the arrangement view. */
    FOLDER
}
