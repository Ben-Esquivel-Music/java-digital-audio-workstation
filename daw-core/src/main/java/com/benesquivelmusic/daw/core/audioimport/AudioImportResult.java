package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of importing a single audio file into a project.
 *
 * @param track          the track onto which the clip was placed
 * @param clip           the audio clip that was created
 * @param sourceFile     the original file that was imported
 * @param wasConverted   {@code true} if sample rate conversion was applied
 */
public record AudioImportResult(Track track, AudioClip clip, Path sourceFile, boolean wasConverted) {

    public AudioImportResult {
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(clip, "clip must not be null");
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
    }
}
