package com.benesquivelmusic.daw.core.track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderTrackTest {

    @Test
    void shouldCreateFolderTrack() {
        Track folder = new Track("Drums", TrackType.FOLDER);

        assertThat(folder.getName()).isEqualTo("Drums");
        assertThat(folder.getType()).isEqualTo(TrackType.FOLDER);
        assertThat(folder.getChildTracks()).isEmpty();
        assertThat(folder.isCollapsed()).isFalse();
        assertThat(folder.getParentTrack()).isNull();
    }

    @Test
    void shouldAddChildTrack() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);

        folder.addChildTrack(kick);

        assertThat(folder.getChildTracks()).containsExactly(kick);
        assertThat(kick.getParentTrack()).isSameAs(folder);
    }

    @Test
    void shouldAddMultipleChildren() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        Track snare = new Track("Snare", TrackType.AUDIO);
        Track hiHat = new Track("Hi-Hat", TrackType.AUDIO);

        folder.addChildTrack(kick);
        folder.addChildTrack(snare);
        folder.addChildTrack(hiHat);

        assertThat(folder.getChildTracks()).containsExactly(kick, snare, hiHat);
    }

    @Test
    void shouldRemoveChildTrack() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        folder.addChildTrack(kick);

        boolean removed = folder.removeChildTrack(kick);

        assertThat(removed).isTrue();
        assertThat(folder.getChildTracks()).isEmpty();
        assertThat(kick.getParentTrack()).isNull();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentChild() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);

        assertThat(folder.removeChildTrack(kick)).isFalse();
    }

    @Test
    void shouldReturnUnmodifiableChildList() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        folder.addChildTrack(new Track("Kick", TrackType.AUDIO));

        assertThatThrownBy(() -> folder.getChildTracks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullChild() {
        Track folder = new Track("Drums", TrackType.FOLDER);

        assertThatThrownBy(() -> folder.addChildTrack(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectChildOnNonFolderTrack() {
        Track audio = new Track("Vocals", TrackType.AUDIO);
        Track child = new Track("Kick", TrackType.AUDIO);

        assertThatThrownBy(() -> audio.addChildTrack(child))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only folder tracks");
    }

    @Test
    void shouldRejectSelfAsChild() {
        Track folder = new Track("Folder", TrackType.FOLDER);

        assertThatThrownBy(() -> folder.addChildTrack(folder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own child");
    }

    // ── Collapse / expand tests ─────────────────────────────────────────────

    @Test
    void shouldCollapseAndExpand() {
        Track folder = new Track("Drums", TrackType.FOLDER);

        folder.setCollapsed(true);
        assertThat(folder.isCollapsed()).isTrue();

        folder.setCollapsed(false);
        assertThat(folder.isCollapsed()).isFalse();
    }

    // ── Nesting / depth tests ───────────────────────────────────────────────

    @Test
    void shouldReturnZeroDepthForTopLevelTrack() {
        Track track = new Track("Vocals", TrackType.AUDIO);
        assertThat(track.getDepth()).isZero();
    }

    @Test
    void shouldReturnDepthOneForChildTrack() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        folder.addChildTrack(kick);

        assertThat(kick.getDepth()).isEqualTo(1);
    }

    @Test
    void shouldSupportNestedFolders() {
        Track outerFolder = new Track("All Drums", TrackType.FOLDER);
        Track innerFolder = new Track("Cymbals", TrackType.FOLDER);
        Track hiHat = new Track("Hi-Hat", TrackType.AUDIO);

        outerFolder.addChildTrack(innerFolder);
        innerFolder.addChildTrack(hiHat);

        assertThat(outerFolder.getDepth()).isZero();
        assertThat(innerFolder.getDepth()).isEqualTo(1);
        assertThat(hiHat.getDepth()).isEqualTo(2);
        assertThat(innerFolder.getParentTrack()).isSameAs(outerFolder);
        assertThat(hiHat.getParentTrack()).isSameAs(innerFolder);
    }

    @Test
    void shouldUpdateDepthWhenRemovedFromFolder() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        folder.addChildTrack(kick);
        assertThat(kick.getDepth()).isEqualTo(1);

        folder.removeChildTrack(kick);
        assertThat(kick.getDepth()).isZero();
    }

    // ── Folder within the track hierarchy ───────────────────────────────────

    @Test
    void shouldAllowFolderTrackAsChildOfAnotherFolder() {
        Track outer = new Track("Instruments", TrackType.FOLDER);
        Track inner = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);

        outer.addChildTrack(inner);
        inner.addChildTrack(kick);

        assertThat(outer.getChildTracks()).containsExactly(inner);
        assertThat(inner.getChildTracks()).containsExactly(kick);
        assertThat(kick.getDepth()).isEqualTo(2);
    }

    @Test
    void shouldTrackParentWhenMovedBetweenFolders() {
        Track folderA = new Track("Folder A", TrackType.FOLDER);
        Track folderB = new Track("Folder B", TrackType.FOLDER);
        Track track = new Track("Vocals", TrackType.AUDIO);

        folderA.addChildTrack(track);
        assertThat(track.getParentTrack()).isSameAs(folderA);

        folderA.removeChildTrack(track);
        folderB.addChildTrack(track);
        assertThat(track.getParentTrack()).isSameAs(folderB);
        assertThat(folderA.getChildTracks()).isEmpty();
    }
}
