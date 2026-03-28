package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.Objects;

/**
 * A single entry on the internal clipboard, representing a clip and the
 * track it was copied or cut from.
 *
 * <p>This is an immutable snapshot; the referenced {@link AudioClip} is the
 * <em>original</em> clip instance. Callers that need independent copies
 * (e.g. paste) should use {@link AudioClip#duplicate()} before inserting
 * the clip onto a track.</p>
 *
 * @param sourceTrack the track the clip was on when it was copied or cut
 * @param clip        the audio clip
 */
public record ClipboardEntry(Track sourceTrack, AudioClip clip) {

    public ClipboardEntry {
        Objects.requireNonNull(sourceTrack, "sourceTrack must not be null");
        Objects.requireNonNull(clip, "clip must not be null");
    }
}
