package com.benesquivelmusic.daw.sdk.audio;

/**
 * Represents a region of audio or MIDI data placed on a track timeline.
 *
 * <p>Regions have a start position, duration, and optional offset into
 * the source material (for trimmed clips).</p>
 */
public interface TimelineRegion {

    /** Returns the unique identifier for this region. */
    String getId();

    /** Returns the display name. */
    String getName();

    /** Returns the start position in beats on the timeline. */
    double getStartBeat();

    /** Returns the duration in beats. */
    double getDurationBeats();

    /** Returns the offset into the source material in beats. */
    double getSourceOffsetBeats();
}
