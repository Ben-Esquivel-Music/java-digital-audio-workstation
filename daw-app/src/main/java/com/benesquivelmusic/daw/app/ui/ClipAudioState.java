package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;

import java.util.Objects;

/**
 * What an audio clip's body shows on the arrangement (story 329, decision
 * D18): its waveform once it has audio, otherwise a placeholder that says
 * why it has none — never an empty body for a clip that reads from a file.
 * {@link OpenProjectAudio#stateOf} answers it for the open project;
 * {@link #unmanaged} answers it for a clip nothing is loading.
 */
enum ClipAudioState {

    /** The clip has audio: the waveform is drawn. */
    LOADED,

    /**
     * No audio and no source reference — an in-memory buffer, a
     * render-in-place result: the plain body, as before.
     */
    NO_SOURCE,

    /** No audio yet, and the open project's current run still owes its source: "Loading…". */
    LOADING,

    /** No audio, and nothing is loading it — missing, unreadable, skipped: "Offline". */
    OFFLINE;

    /**
     * The state of {@code clip} when nothing loads audio: {@link #LOADED},
     * {@link #NO_SOURCE} or {@link #OFFLINE}.
     *
     * @param clip the clip
     * @return the clip's state
     */
    static ClipAudioState unmanaged(AudioClip clip) {
        Objects.requireNonNull(clip, "clip must not be null");
        if (clip.getAudioData() != null) {
            return LOADED;
        }
        return AssetReference.of(clip).isPresent() ? OFFLINE : NO_SOURCE;
    }
}
