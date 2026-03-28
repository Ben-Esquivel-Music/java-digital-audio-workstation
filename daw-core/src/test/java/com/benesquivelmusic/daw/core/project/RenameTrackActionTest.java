package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenameTrackActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThat(new RenameTrackAction(track, "Percussion").description())
                .isEqualTo("Rename Track");
    }

    @Test
    void shouldRenameTrackOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);

        RenameTrackAction action = new RenameTrackAction(track, "Percussion");
        action.execute();

        assertThat(track.getName()).isEqualTo("Percussion");
    }

    @Test
    void shouldRestoreNameOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);

        RenameTrackAction action = new RenameTrackAction(track, "Percussion");
        action.execute();
        action.undo();

        assertThat(track.getName()).isEqualTo("Drums");
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new RenameTrackAction(track, "Percussion"));
        assertThat(track.getName()).isEqualTo("Percussion");

        undoManager.undo();
        assertThat(track.getName()).isEqualTo("Drums");

        undoManager.redo();
        assertThat(track.getName()).isEqualTo("Percussion");
    }

    @Test
    void shouldRejectNullTrack() {
        assertThatThrownBy(() -> new RenameTrackAction(null, "Name"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullName() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new RenameTrackAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }
}
