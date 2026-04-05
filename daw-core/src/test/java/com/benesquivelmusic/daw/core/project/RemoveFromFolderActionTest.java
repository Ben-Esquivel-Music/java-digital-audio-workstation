package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveFromFolderActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        Track folder = new Track("Folder", TrackType.FOLDER);
        Track track = new Track("Vocals", TrackType.AUDIO);

        RemoveFromFolderAction action = new RemoveFromFolderAction(track, folder);

        assertThat(action.description()).isEqualTo("Remove from Folder");
    }

    @Test
    void shouldRemoveTrackFromFolderOnExecute() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        folder.addChildTrack(kick);

        RemoveFromFolderAction action = new RemoveFromFolderAction(kick, folder);
        action.execute();

        assertThat(folder.getChildTracks()).isEmpty();
        assertThat(kick.getParentTrack()).isNull();
    }

    @Test
    void shouldRestoreToFolderOnUndo() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        folder.addChildTrack(kick);

        RemoveFromFolderAction action = new RemoveFromFolderAction(kick, folder);
        action.execute();
        action.undo();

        assertThat(folder.getChildTracks()).containsExactly(kick);
        assertThat(kick.getParentTrack()).isSameAs(folder);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track folder = new Track("Drums", TrackType.FOLDER);
        Track kick = new Track("Kick", TrackType.AUDIO);
        folder.addChildTrack(kick);

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new RemoveFromFolderAction(kick, folder));

        assertThat(folder.getChildTracks()).isEmpty();

        undoManager.undo();
        assertThat(folder.getChildTracks()).containsExactly(kick);

        undoManager.redo();
        assertThat(folder.getChildTracks()).isEmpty();
    }

    @Test
    void shouldRejectNullTrack() {
        Track folder = new Track("Folder", TrackType.FOLDER);

        assertThatThrownBy(() -> new RemoveFromFolderAction(null, folder))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullFolder() {
        Track track = new Track("Track", TrackType.AUDIO);

        assertThatThrownBy(() -> new RemoveFromFolderAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }
}
