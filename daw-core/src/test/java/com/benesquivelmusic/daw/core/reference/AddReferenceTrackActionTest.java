package com.benesquivelmusic.daw.core.reference;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddReferenceTrackActionTest {

    @Test
    void shouldAddReferenceTrackOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        AddReferenceTrackAction action = new AddReferenceTrackAction(project, ref);

        action.execute();

        assertThat(project.getReferenceTrackManager().getReferenceTracks()).containsExactly(ref);
    }

    @Test
    void shouldRemoveReferenceTrackOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        AddReferenceTrackAction action = new AddReferenceTrackAction(project, ref);

        action.execute();
        action.undo();

        assertThat(project.getReferenceTrackManager().getReferenceTracks()).isEmpty();
    }

    @Test
    void shouldSupportRedoAfterUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        AddReferenceTrackAction action = new AddReferenceTrackAction(project, ref);

        action.execute();
        action.undo();
        action.execute();

        assertThat(project.getReferenceTrackManager().getReferenceTracks()).containsExactly(ref);
    }

    @Test
    void shouldHaveDescription() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        AddReferenceTrackAction action = new AddReferenceTrackAction(project, ref);

        assertThat(action.description()).isEqualTo("Add Reference Track");
    }
}
