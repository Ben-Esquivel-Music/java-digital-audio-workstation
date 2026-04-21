package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipEditControllerTest {

    @Test
    void deleteSelectionWithRippleUsesTimeSelectionBounds() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);
        project.setRippleMode(RippleMode.PER_TRACK);

        AudioClip removed = new AudioClip("removed", 4.0, 2.0, null);
        AudioClip later = new AudioClip("later", 16.0, 4.0, null);
        track.addClip(removed);
        track.addClip(later);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.setSelection(4.0, 12.0);
        selectionModel.selectClip(track, removed);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onDeleteSelection();

        assertThat(track.getClips()).doesNotContain(removed);
        assertThat(later.getStartBeat()).isEqualTo(8.0);

        undoManager.undo();

        assertThat(track.getClips()).contains(removed, later);
        assertThat(removed.getStartBeat()).isEqualTo(4.0);
        assertThat(later.getStartBeat()).isEqualTo(16.0);
    }

    private static final class TestHost implements ClipEditController.Host {
        private final DawProject project;
        private final UndoManager undoManager;
        private final SelectionModel selectionModel;
        private final ClipboardManager clipboardManager = new ClipboardManager();

        private TestHost(DawProject project, UndoManager undoManager, SelectionModel selectionModel) {
            this.project = project;
            this.undoManager = undoManager;
            this.selectionModel = selectionModel;
        }

        @Override public DawProject project() { return project; }
        @Override public UndoManager undoManager() { return undoManager; }
        @Override public ClipboardManager clipboardManager() { return clipboardManager; }
        @Override public SelectionModel selectionModel() { return selectionModel; }
        @Override public void refreshArrangementCanvas() { }
        @Override public void updateUndoRedoState() { }
        @Override public void syncMenuState() { }
        @Override public void markProjectDirty() { }
        @Override public void updateStatusBar(String text, DawIcon icon) { }
        @Override public void showNotificationWithUndo(NotificationLevel level, String message, Runnable undoCallback) { }
        @Override public void showNotification(NotificationLevel level, String message) { }
        @Override public EditorView editorView() { return null; }
        @Override public RippleMode rippleMode() { return project.getRippleMode(); }
    }
}
