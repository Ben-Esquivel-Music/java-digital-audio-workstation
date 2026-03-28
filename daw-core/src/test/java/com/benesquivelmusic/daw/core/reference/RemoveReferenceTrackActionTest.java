package com.benesquivelmusic.daw.core.reference;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveReferenceTrackActionTest {

    @Test
    void shouldRemoveReferenceTrackOnExecute() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        project.addReferenceTrack(ref);
        RemoveReferenceTrackAction action = new RemoveReferenceTrackAction(project, ref);

        action.execute();

        assertThat(project.getReferenceTrackManager().getReferenceTracks()).isEmpty();
    }

    @Test
    void shouldReAddReferenceTrackOnUndo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        project.addReferenceTrack(ref);
        RemoveReferenceTrackAction action = new RemoveReferenceTrackAction(project, ref);

        action.execute();
        action.undo();

        assertThat(project.getReferenceTrackManager().getReferenceTracks()).containsExactly(ref);
    }

    @Test
    void shouldHaveDescription() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        RemoveReferenceTrackAction action = new RemoveReferenceTrackAction(project, ref);

        assertThat(action.description()).isEqualTo("Remove Reference Track");
    }
}
