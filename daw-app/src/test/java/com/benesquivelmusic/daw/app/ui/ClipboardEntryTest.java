package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipboardEntryTest {

    @Test
    void shouldStoreSourceTrackAndClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        ClipboardEntry entry = new ClipboardEntry(track, clip);

        assertThat(entry.sourceTrack()).isSameAs(track);
        assertThat(entry.clip()).isSameAs(clip);
    }

    @Test
    void shouldRejectNullSourceTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new ClipboardEntry(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new ClipboardEntry(track, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        ClipboardEntry entry1 = new ClipboardEntry(track, clip);
        ClipboardEntry entry2 = new ClipboardEntry(track, clip);

        assertThat(entry1).isEqualTo(entry2);
        assertThat(entry1.hashCode()).isEqualTo(entry2.hashCode());
    }
}
