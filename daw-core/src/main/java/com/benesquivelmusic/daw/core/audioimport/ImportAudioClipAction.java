package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that records the result of importing an audio file.
 *
 * <p>The actual file I/O and decoding are performed by {@link AudioFileImporter}
 * <em>before</em> this action is created. This action only manages the project
 * state changes (track and clip addition/removal) so that undo removes the
 * imported clip (and optionally the newly created track), and redo restores them.</p>
 *
 * <p>On the first {@link #execute()} call the action is a no-op because the
 * import has already been applied to the project by the caller. Subsequent
 * {@code execute()} calls (redo) re-add the track and clip.</p>
 */
public final class ImportAudioClipAction implements UndoableAction {

    private final DawProject project;
    private final Track track;
    private final AudioClip clip;
    private final boolean trackCreatedByImport;
    private boolean initialExecute = true;

    /**
     * Creates a new import-audio-clip action.
     *
     * @param project               the project containing the track
     * @param track                 the track the clip was placed on
     * @param clip                  the imported audio clip
     * @param trackCreatedByImport  {@code true} if the track was newly created
     *                              by the import (so undo should remove it)
     */
    public ImportAudioClipAction(DawProject project, Track track, AudioClip clip,
                                 boolean trackCreatedByImport) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.trackCreatedByImport = trackCreatedByImport;
    }

    @Override
    public String description() {
        return "Import Audio File";
    }

    @Override
    public void execute() {
        if (initialExecute) {
            // Track and clip already added by AudioFileImporter — nothing to do
            initialExecute = false;
            return;
        }
        // Redo: re-add track (if it was created by import) and clip
        if (trackCreatedByImport) {
            project.addTrack(track);
        }
        track.addClip(clip);
    }

    @Override
    public void undo() {
        track.removeClip(clip);
        if (trackCreatedByImport) {
            project.removeTrack(track);
        }
    }

    /** Returns the track that the clip was placed on. */
    public Track getTrack() {
        return track;
    }

    /** Returns the imported audio clip. */
    public AudioClip getClip() {
        return clip;
    }

    /** Returns whether the track was newly created by the import. */
    public boolean isTrackCreatedByImport() {
        return trackCreatedByImport;
    }
}
