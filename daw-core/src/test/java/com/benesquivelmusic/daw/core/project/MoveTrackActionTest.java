package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoveTrackActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MoveTrackAction action = new MoveTrackAction(project, 0, 1);

        assertThat(action.description()).isEqualTo("Move Track");
    }

    @Test
    void shouldMoveTrackOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        Track vocals = project.createAudioTrack("Vocals");

        MoveTrackAction action = new MoveTrackAction(project, 0, 2);
        action.execute();

        assertThat(project.getTracks()).containsExactly(bass, vocals, drums);
    }

    @Test
    void shouldRestoreOrderOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        Track vocals = project.createAudioTrack("Vocals");

        MoveTrackAction action = new MoveTrackAction(project, 0, 2);
        action.execute();
        action.undo();

        assertThat(project.getTracks()).containsExactly(drums, bass, vocals);
    }

    @Test
    void shouldWorkWithUndoManager() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        Track vocals = project.createAudioTrack("Vocals");

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new MoveTrackAction(project, 0, 2));

        assertThat(project.getTracks()).containsExactly(bass, vocals, drums);

        undoManager.undo();
        assertThat(project.getTracks()).containsExactly(drums, bass, vocals);

        undoManager.redo();
        assertThat(project.getTracks()).containsExactly(bass, vocals, drums);
    }

    @Test
    void shouldSyncMixerChannelsOnUndoRedo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        project.createAudioTrack("Bass");
        project.createAudioTrack("Vocals");

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new MoveTrackAction(project, 0, 2));

        assertThat(project.getMixer().getChannels().get(0).getName()).isEqualTo("Bass");
        assertThat(project.getMixer().getChannels().get(1).getName()).isEqualTo("Vocals");
        assertThat(project.getMixer().getChannels().get(2).getName()).isEqualTo("Drums");

        undoManager.undo();

        assertThat(project.getMixer().getChannels().get(0).getName()).isEqualTo("Drums");
        assertThat(project.getMixer().getChannels().get(1).getName()).isEqualTo("Bass");
        assertThat(project.getMixer().getChannels().get(2).getName()).isEqualTo("Vocals");
    }

    @Test
    void shouldRejectNullProject() {
        assertThatThrownBy(() -> new MoveTrackAction(null, 0, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
