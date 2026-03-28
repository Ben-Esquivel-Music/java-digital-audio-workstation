package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveTrackActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");

        assertThat(new RemoveTrackAction(project, track).description()).isEqualTo("Remove Track");
    }

    @Test
    void shouldRemoveTrackOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");

        RemoveTrackAction action = new RemoveTrackAction(project, drums);
        action.execute();

        assertThat(project.getTracks()).containsExactly(bass);
    }

    @Test
    void shouldRestoreTrackOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");

        RemoveTrackAction action = new RemoveTrackAction(project, drums);
        action.execute();
        action.undo();

        assertThat(project.getTracks()).containsExactly(drums, bass);
    }

    @Test
    void shouldRestoreTrackAtOriginalPosition() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        Track vocals = project.createAudioTrack("Vocals");

        RemoveTrackAction action = new RemoveTrackAction(project, bass);
        action.execute();
        assertThat(project.getTracks()).containsExactly(drums, vocals);

        action.undo();
        assertThat(project.getTracks().get(1).getName()).isEqualTo("Bass");
    }

    @Test
    void shouldWorkWithUndoManager() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new RemoveTrackAction(project, drums));
        assertThat(project.getTracks()).isEmpty();

        undoManager.undo();
        assertThat(project.getTracks()).containsExactly(drums);

        undoManager.redo();
        assertThat(project.getTracks()).isEmpty();
    }

    @Test
    void shouldRejectNullProject() {
        assertThatThrownBy(() -> new RemoveTrackAction(null, new com.benesquivelmusic.daw.core.track.Track("t", com.benesquivelmusic.daw.core.track.TrackType.AUDIO)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> new RemoveTrackAction(project, null))
                .isInstanceOf(NullPointerException.class);
    }
}
