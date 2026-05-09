package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateTakeAndSetCompRegionActionTest {

    @Test
    void createTakeAddsLaneAndUndoRemovesIt() {
        TakeComping comping = new TakeComping();
        UndoManager undo = new UndoManager();
        AudioClip clip = new AudioClip("rec", 0.0, 4.0, null);
        CreateTakeAction action = new CreateTakeAction(comping, "Take 1", clip);

        undo.execute(action);
        assertThat(comping.getTakeLaneCount()).isEqualTo(1);
        assertThat(comping.getTakeLane(0).getName()).isEqualTo("Take 1");
        assertThat(comping.getTakeLane(0).getClips()).containsExactly(clip);
        assertThat(action.getInsertedIndex()).isEqualTo(0);

        undo.undo();
        assertThat(comping.getTakeLaneCount()).isEqualTo(0);

        undo.redo();
        assertThat(comping.getTakeLaneCount()).isEqualTo(1);
    }

    @Test
    void rerecordingStacksAdditionalTakesWithoutOverwriting() {
        TakeComping comping = new TakeComping();
        UndoManager undo = new UndoManager();
        AudioClip first = new AudioClip("take 1", 0.0, 4.0, null);
        AudioClip second = new AudioClip("take 2", 0.0, 4.0, null);
        AudioClip third = new AudioClip("take 3", 0.0, 4.0, null);
        undo.execute(new CreateTakeAction(comping, "Take 1", first));
        undo.execute(new CreateTakeAction(comping, "Take 2", second));
        undo.execute(new CreateTakeAction(comping, "Take 3", third));

        assertThat(comping.getTakeLaneCount()).isEqualTo(3);
        assertThat(comping.getTakeLane(0).getClips()).containsExactly(first);
        assertThat(comping.getTakeLane(1).getClips()).containsExactly(second);
        assertThat(comping.getTakeLane(2).getClips()).containsExactly(third);
    }

    @Test
    void setCompRegionExecutesAndUndoes() {
        TakeComping comping = new TakeComping();
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addTakeLane(new TakeLane("Take 2"));
        UndoManager undo = new UndoManager();

        // Pre-existing region on lane 1 covering [0, 4).
        comping.addCompRegion(new CompRegion(1, 0.0, 4.0));

        CompRegion swipe = new CompRegion(0, 1.0, 2.0);
        undo.execute(new SetCompRegionAction(comping, swipe));

        // Swipe deselects overlapping region from lane 1.
        assertThat(comping.getCompRegions()).contains(swipe);
        assertThat(comping.getCompRegions()).noneMatch(r -> r.takeIndex() == 1
                && r.startBeat() == 0.0 && r.durationBeats() == 4.0);

        undo.undo();
        assertThat(comping.getCompRegions())
                .containsExactly(new CompRegion(1, 0.0, 4.0));
    }

    @Test
    void rejectsNullArguments() {
        TakeComping comping = new TakeComping();
        AudioClip clip = new AudioClip("c", 0.0, 1.0, null);
        assertThatThrownBy(() -> new CreateTakeAction(null, "x", clip))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateTakeAction(comping, null, clip))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateTakeAction(comping, "x", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SetCompRegionAction(null, new CompRegion(0, 0, 1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SetCompRegionAction(comping, null))
                .isInstanceOf(NullPointerException.class);
    }
}
