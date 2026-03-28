package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoveToFolderActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Folder");
        Track track = project.createAudioTrack("Vocals");

        MoveToFolderAction action = new MoveToFolderAction(project, track, folder);

        assertThat(action.description()).isEqualTo("Move to Folder");
    }

    @Test
    void shouldMoveTrackToFolderOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");

        MoveToFolderAction action = new MoveToFolderAction(project, kick, folder);
        action.execute();

        assertThat(folder.getChildTracks()).containsExactly(kick);
        assertThat(kick.getParentTrack()).isSameAs(folder);
    }

    @Test
    void shouldRestoreTopLevelOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");

        MoveToFolderAction action = new MoveToFolderAction(project, kick, folder);
        action.execute();
        action.undo();

        assertThat(folder.getChildTracks()).isEmpty();
        assertThat(kick.getParentTrack()).isNull();
    }

    @Test
    void shouldRestorePreviousParentOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folderA = project.createFolderTrack("Folder A");
        Track folderB = project.createFolderTrack("Folder B");
        Track track = project.createAudioTrack("Vocals");
        project.moveTrackToFolder(track, folderA);

        MoveToFolderAction action = new MoveToFolderAction(project, track, folderB);
        action.execute();
        assertThat(track.getParentTrack()).isSameAs(folderB);

        action.undo();
        assertThat(track.getParentTrack()).isSameAs(folderA);
        assertThat(folderA.getChildTracks()).containsExactly(track);
        assertThat(folderB.getChildTracks()).isEmpty();
    }

    @Test
    void shouldWorkWithUndoManager() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Drums");
        Track kick = project.createAudioTrack("Kick");

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new MoveToFolderAction(project, kick, folder));

        assertThat(folder.getChildTracks()).containsExactly(kick);

        undoManager.undo();
        assertThat(folder.getChildTracks()).isEmpty();
        assertThat(kick.getParentTrack()).isNull();

        undoManager.redo();
        assertThat(folder.getChildTracks()).containsExactly(kick);
        assertThat(kick.getParentTrack()).isSameAs(folder);
    }

    @Test
    void shouldRejectNullProject() {
        Track folder = new Track("Folder", TrackType.FOLDER);
        Track track = new Track("Track", TrackType.AUDIO);

        assertThatThrownBy(() -> new MoveToFolderAction(null, track, folder))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track folder = project.createFolderTrack("Folder");

        assertThatThrownBy(() -> new MoveToFolderAction(project, null, folder))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullFolder() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Track");

        assertThatThrownBy(() -> new MoveToFolderAction(project, track, null))
                .isInstanceOf(NullPointerException.class);
    }
}
