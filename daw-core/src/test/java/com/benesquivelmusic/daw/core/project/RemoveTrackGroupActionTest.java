package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveTrackGroupActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        TrackGroup group = new TrackGroup("Drums");

        RemoveTrackGroupAction action = new RemoveTrackGroupAction(project, group);

        assertThat(action.description()).isEqualTo("Remove Track Group");
    }

    @Test
    void shouldRemoveGroupOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick));

        RemoveTrackGroupAction action = new RemoveTrackGroupAction(project, group);
        action.execute();

        assertThat(project.getTrackGroups()).isEmpty();
    }

    @Test
    void shouldReAddGroupOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick));

        RemoveTrackGroupAction action = new RemoveTrackGroupAction(project, group);
        action.execute();
        action.undo();

        assertThat(project.getTrackGroups()).containsExactly(group);
    }

    @Test
    void shouldWorkWithUndoManager() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        TrackGroup group = project.createTrackGroup("Drums", List.of(kick));

        UndoManager undoManager = new UndoManager();
        undoManager.execute(new RemoveTrackGroupAction(project, group));

        assertThat(project.getTrackGroups()).isEmpty();

        undoManager.undo();
        assertThat(project.getTrackGroups()).containsExactly(group);

        undoManager.redo();
        assertThat(project.getTrackGroups()).isEmpty();
    }

    @Test
    void shouldRejectNullProject() {
        TrackGroup group = new TrackGroup("Drums");

        assertThatThrownBy(() -> new RemoveTrackGroupAction(null, group))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullGroup() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        assertThatThrownBy(() -> new RemoveTrackGroupAction(project, null))
                .isInstanceOf(NullPointerException.class);
    }
}
