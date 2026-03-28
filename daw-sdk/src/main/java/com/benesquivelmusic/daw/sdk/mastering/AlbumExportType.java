package com.benesquivelmusic.daw.sdk.mastering;

/**
 * Export types for album assembly output.
 *
 * <p>Determines whether the album is exported as a single continuous file
 * or as individual track files with proper timing and metadata.</p>
 */
public enum AlbumExportType {

    /** Export as a single continuous WAV file with all tracks and transitions. */
    SINGLE_CONTINUOUS,

    /** Export as individual track files with correct timing metadata. */
    INDIVIDUAL_TRACKS
}
