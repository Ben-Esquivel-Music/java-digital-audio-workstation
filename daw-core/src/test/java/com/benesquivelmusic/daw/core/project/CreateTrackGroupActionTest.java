package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateTrackGroupActionTest {

    @Test
    void shouldHaveDescriptiveName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        CreateTrackGroupAction action = new CreateTrackGroupAction(project, "Drums", List.of());

        assertThat(action.description()).isEqualTo("Create Track Group");
    }

    @Test
    void shouldCreateGroupOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");

        CreateTrackGroupAction action = new CreateTrackGroupAction(
                project, "Drums", List.of(kick, snare));
        action.execute();

        assertThat(project.getTrackGroups()).hasSize(1);
        assertThat(action.getGroup()).isNotNull();
        assertThat(action.getGroup().getName()).isEqualTo("Drums");
        assertThat(action.getGroup().getTracks()).containsExactly(kick, snare);
    }

    @Test
    void shouldRemoveGroupOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");

        CreateTrackGroupAction action = new CreateTrackGroupAction(
                project, "Drums", List.of(kick));
        action.execute();
        action.undo();

        assertThat(project.getTrackGroups()).isEmpty();
    }

    @Test
    void shouldReAddSameGroupOnRedo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");

        CreateTrackGroupAction action = new CreateTrackGroupAction(
                project, "Drums", List.of(kick));
        action.execute();
        TrackGroup group = action.getGroup();

        action.undo();
        action.execute();

        assertThat(project.getTrackGroups()).hasSize(1);
        assertThat(project.getTrackGroups().get(0)).isSameAs(group);
    }

    @Test
    void shouldWorkWithUndoManager() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");

        UndoManager undoManager = new UndoManager();
        CreateTrackGroupAction action = new CreateTrackGroupAction(
                project, "Drums", List.of(kick, snare));
        undoManager.execute(action);

        assertThat(project.getTrackGroups()).hasSize(1);

        undoManager.undo();
        assertThat(project.getTrackGroups()).isEmpty();

        undoManager.redo();
        assertThat(project.getTrackGroups()).hasSize(1);
    }

    @Test
    void shouldReturnNullGroupBeforeExecution() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        CreateTrackGroupAction action = new CreateTrackGroupAction(
                project, "Drums", List.of());

        assertThat(action.getGroup()).isNull();
    }

    @Test
    void shouldRejectNullProject() {
        assertThatThrownBy(() -> new CreateTrackGroupAction(null, "Drums", List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullGroupName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> new CreateTrackGroupAction(project, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrackList() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> new CreateTrackGroupAction(project, "Drums", null))
                .isInstanceOf(NullPointerException.class);
    }
}
