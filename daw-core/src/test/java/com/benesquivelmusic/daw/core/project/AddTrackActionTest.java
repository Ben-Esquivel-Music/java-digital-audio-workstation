package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddTrackActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Drums", TrackType.AUDIO);

        assertThat(new AddTrackAction(project, track).description()).isEqualTo("Add Track");
    }

    @Test
    void shouldAddTrackOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Drums", TrackType.AUDIO);

        AddTrackAction action = new AddTrackAction(project, track);
        action.execute();

        assertThat(project.getTracks()).containsExactly(track);
    }

    @Test
    void shouldRemoveTrackOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Drums", TrackType.AUDIO);

        AddTrackAction action = new AddTrackAction(project, track);
        action.execute();
        action.undo();

        assertThat(project.getTracks()).isEmpty();
    }

    @Test
    void shouldWorkWithUndoManager() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Drums", TrackType.AUDIO);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new AddTrackAction(project, track));
        assertThat(project.getTracks()).containsExactly(track);

        undoManager.undo();
        assertThat(project.getTracks()).isEmpty();

        undoManager.redo();
        assertThat(project.getTracks()).containsExactly(track);
    }

    @Test
    void shouldRejectNullProject() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new AddTrackAction(null, track))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> new AddTrackAction(project, null))
                .isInstanceOf(NullPointerException.class);
    }
}
