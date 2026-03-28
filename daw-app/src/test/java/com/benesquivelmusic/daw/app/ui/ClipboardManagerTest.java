package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipboardManagerTest {

    @Test
    void shouldStartEmpty() {
        ClipboardManager clipboard = new ClipboardManager();
        assertThat(clipboard.hasContent()).isFalse();
    }

    @Test
    void markCopiedShouldSetContentAvailable() {
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.markCopied();
        assertThat(clipboard.hasContent()).isTrue();
    }

    @Test
    void clearShouldRemoveContent() {
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.markCopied();
        clipboard.clear();
        assertThat(clipboard.hasContent()).isFalse();
    }

    @Test
    void multipleCopiesShouldKeepContentAvailable() {
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.markCopied();
        clipboard.markCopied();
        assertThat(clipboard.hasContent()).isTrue();
    }

    @Test
    void clearOnEmptyClipboardShouldBeNoOp() {
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.clear();
        assertThat(clipboard.hasContent()).isFalse();
    }

    // ── Clip entry storage tests ────────────────────────────────────────────

    @Test
    void shouldStartWithEmptyEntries() {
        ClipboardManager clipboard = new ClipboardManager();
        assertThat(clipboard.getEntries()).isEmpty();
    }

    @Test
    void copyClipsShouldStoreEntriesAndMarkContent() {
        ClipboardManager clipboard = new ClipboardManager();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        ClipboardEntry entry = new ClipboardEntry(track, clip);

        clipboard.copyClips(List.of(entry));

        assertThat(clipboard.hasContent()).isTrue();
        assertThat(clipboard.getEntries()).containsExactly(entry);
    }

    @Test
    void copyClipsShouldReplaceExistingEntries() {
        ClipboardManager clipboard = new ClipboardManager();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);

        clipboard.copyClips(List.of(new ClipboardEntry(track, clip1)));
        clipboard.copyClips(List.of(new ClipboardEntry(track, clip2)));

        assertThat(clipboard.getEntries()).hasSize(1);
        assertThat(clipboard.getEntries().get(0).clip()).isSameAs(clip2);
    }

    @Test
    void clearShouldRemoveEntries() {
        ClipboardManager clipboard = new ClipboardManager();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        clipboard.copyClips(List.of(new ClipboardEntry(track, clip)));
        clipboard.clear();

        assertThat(clipboard.getEntries()).isEmpty();
        assertThat(clipboard.hasContent()).isFalse();
    }

    @Test
    void copyClipsShouldRejectNullList() {
        ClipboardManager clipboard = new ClipboardManager();
        assertThatThrownBy(() -> clipboard.copyClips(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copyClipsShouldRejectEmptyList() {
        ClipboardManager clipboard = new ClipboardManager();
        assertThatThrownBy(() -> clipboard.copyClips(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copyClipsShouldStoreMultipleEntries() {
        ClipboardManager clipboard = new ClipboardManager();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);
        ClipboardEntry entry1 = new ClipboardEntry(track, clip1);
        ClipboardEntry entry2 = new ClipboardEntry(track, clip2);

        clipboard.copyClips(List.of(entry1, entry2));

        assertThat(clipboard.getEntries()).containsExactly(entry1, entry2);
    }

    @Test
    void getEntriesShouldReturnUnmodifiableList() {
        ClipboardManager clipboard = new ClipboardManager();
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        clipboard.copyClips(List.of(new ClipboardEntry(track, clip)));

        assertThatThrownBy(() -> clipboard.getEntries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
