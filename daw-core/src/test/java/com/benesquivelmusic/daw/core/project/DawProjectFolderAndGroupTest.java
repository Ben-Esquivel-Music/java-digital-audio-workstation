package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DawProjectFolderAndGroupTest {

    // ── Folder track creation ───────────────────────────────────────────────

    @Test
    void shouldCreateFolderTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        Track folder = project.createFolderTrack("Drums");

        assertThat(folder.getName()).isEqualTo("Drums");
        assertThat(folder.getType()).isEqualTo(TrackType.FOLDER);
        assertThat(project.getTracks()).contains(folder);
    }

    @Test
    void shouldCreateMixerChannelForFolderTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        Track folder = project.createFolderTrack("Drums");

        assertThat(project.getMixerChannelForTrack(folder)).isNotNull();
        assertThat(project.getMixerChannelForTrack(folder).getName()).isEqualTo("Drums");
    }

    // ── Move track to folder ────────────────────────────────────────────────

    @Test
    void shouldMoveTrackToFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");

        project.moveTrackToFolder(kick, folder);

        assertThat(folder.getChildTracks()).containsExactly(kick);
        assertThat(kick.getParentTrack()).isSameAs(folder);
    }

    @Test
    void shouldMoveMultipleTracksToFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");

        project.moveTrackToFolder(kick, folder);
        project.moveTrackToFolder(snare, folder);

        assertThat(folder.getChildTracks()).containsExactly(kick, snare);
    }

    @Test
    void shouldMoveTrackBetweenFolders() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folderA = project.createFolderTrack("Folder A");
        Track folderB = project.createFolderTrack("Folder B");
        Track track = project.createAudioTrack("Vocals");

        project.moveTrackToFolder(track, folderA);
        project.moveTrackToFolder(track, folderB);

        assertThat(folderA.getChildTracks()).isEmpty();
        assertThat(folderB.getChildTracks()).containsExactly(track);
        assertThat(track.getParentTrack()).isSameAs(folderB);
    }

    @Test
    void shouldRejectMoveToNonFolderTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track audio = project.createAudioTrack("Vocals");
        Track other = project.createAudioTrack("Guitar");

        assertThatThrownBy(() -> project.moveTrackToFolder(other, audio))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("folder");
    }

    @Test
    void shouldRejectMoveTrackIntoItself() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Folder");

        assertThatThrownBy(() -> project.moveTrackToFolder(folder, folder))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTrackInMoveToFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Folder");

        assertThatThrownBy(() -> project.moveTrackToFolder(null, folder))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullFolderInMoveToFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Vocals");

        assertThatThrownBy(() -> project.moveTrackToFolder(track, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Remove track from folder ────────────────────────────────────────────

    @Test
    void shouldRemoveTrackFromFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");
        project.moveTrackToFolder(kick, folder);

        project.removeTrackFromFolder(kick);

        assertThat(folder.getChildTracks()).isEmpty();
        assertThat(kick.getParentTrack()).isNull();
    }

    @Test
    void shouldRejectRemoveFromFolderWhenNotInFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Vocals");

        assertThatThrownBy(() -> project.removeTrackFromFolder(track))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in a folder");
    }

    @Test
    void shouldRejectNullInRemoveFromFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        assertThatThrownBy(() -> project.removeTrackFromFolder(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Nested folders ──────────────────────────────────────────────────────

    @Test
    void shouldSupportNestedFoldersThroughProject() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track outer = project.createFolderTrack("All Instruments");
        Track inner = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");

        project.moveTrackToFolder(inner, outer);
        project.moveTrackToFolder(kick, inner);

        assertThat(outer.getChildTracks()).containsExactly(inner);
        assertThat(inner.getChildTracks()).containsExactly(kick);
        assertThat(kick.getDepth()).isEqualTo(2);
    }

    // ── Track group creation ────────────────────────────────────────────────

    @Test
    void shouldCreateTrackGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");

        TrackGroup group = project.createTrackGroup("Drums", List.of(kick, snare));

        assertThat(group.getName()).isEqualTo("Drums");
        assertThat(group.getTracks()).containsExactly(kick, snare);
        assertThat(project.getTrackGroups()).containsExactly(group);
    }

    @Test
    void shouldCreateMultipleGroups() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        Track bass = project.createAudioTrack("Bass");

        TrackGroup drums = project.createTrackGroup("Drums", List.of(kick, snare));
        TrackGroup rhythm = project.createTrackGroup("Rhythm", List.of(kick, snare, bass));

        assertThat(project.getTrackGroups()).containsExactly(drums, rhythm);
    }

    @Test
    void shouldRemoveTrackGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick));

        boolean removed = project.removeTrackGroup(group);

        assertThat(removed).isTrue();
        assertThat(project.getTrackGroups()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        TrackGroup group = new TrackGroup("Orphan");

        assertThat(project.removeTrackGroup(group)).isFalse();
    }

    @Test
    void shouldReturnUnmodifiableGroupList() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        assertThatThrownBy(() -> project.getTrackGroups().add(new TrackGroup("Illegal")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReAddGroupViaAddTrackGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick));

        project.removeTrackGroup(group);
        assertThat(project.getTrackGroups()).isEmpty();

        project.addTrackGroup(group);
        assertThat(project.getTrackGroups()).containsExactly(group);
    }

    @Test
    void shouldNotDuplicateGroupOnReAdd() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick));

        project.addTrackGroup(group);

        assertThat(project.getTrackGroups()).hasSize(1);
    }

    @Test
    void shouldRejectNullGroupName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> project.createTrackGroup(null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrackList() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> project.createTrackGroup("Group", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullGroupInAddTrackGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> project.addTrackGroup(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Linked group operations via project ─────────────────────────────────

    @Test
    void shouldLinkMuteOperationsThroughGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick, snare));

        group.setMuted(true);

        assertThat(kick.isMuted()).isTrue();
        assertThat(snare.isMuted()).isTrue();
    }

    @Test
    void shouldLinkSoloOperationsThroughGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick, snare));

        group.setSolo(true);

        assertThat(kick.isSolo()).isTrue();
        assertThat(snare.isSolo()).isTrue();
    }
}
