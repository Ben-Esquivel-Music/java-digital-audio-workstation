package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        Track track = new Track("Vox", TrackType.AUDIO);
        TakeComping comping = track.getTakeComping();
        comping.addTakeLane(laneWithClip("Take 1"));
        comping.addTakeLane(laneWithClip("Take 2"));
        UndoManager undo = new UndoManager();

        // Pre-existing region on lane 1 covering [0, 4), compiled onto the main lane.
        comping.addCompRegion(new CompRegion(1, 0.0, 4.0));
        new CompileCompAction(track).execute();
        List<AudioClip> before = List.copyOf(track.getClips());
        assertThat(before).hasSize(1);

        CompRegion swipe = new CompRegion(0, 1.0, 2.0);
        undo.execute(new SetCompRegionAction(track, swipe));

        // Swipe deselects overlapping region from lane 1.
        assertThat(comping.getCompRegions()).contains(swipe);
        assertThat(comping.getCompRegions()).noneMatch(r -> r.takeIndex() == 1
                && r.startBeat() == 0.0 && r.durationBeats() == 4.0);
        // Story 328 (book §5.6): the swipe is audible at once — the main lane
        // now plays the swipe's slice of lane 0 and nothing of lane 1.
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getStartBeat()).isEqualTo(1.0);
        assertThat(track.getClips().getFirst().getDurationBeats()).isEqualTo(2.0);
        assertThat(track.getClips().getFirst().getAudioData())
                .isSameAs(comping.getTakeLane(0).getClips().getFirst().getAudioData());

        undo.undo();
        assertThat(comping.getCompRegions())
                .containsExactly(new CompRegion(1, 0.0, 4.0));
        // Story 328 review: the undo recompiles the restored selection from
        // the live comping state — the same comp clip, freshly sliced — so an
        // audition between the swipe and its undo cannot orphan anything.
        assertThat(track.getClips()).as("undo restores the previous comp").hasSize(1);
        AudioClip restored = track.getClips().getFirst();
        assertThat(restored.getStartBeat()).isEqualTo(before.getFirst().getStartBeat());
        assertThat(restored.getDurationBeats()).isEqualTo(before.getFirst().getDurationBeats());
        assertThat(restored.getSourceOffsetBeats()).isEqualTo(before.getFirst().getSourceOffsetBeats());
        assertThat(restored.getAudioData()).isSameAs(before.getFirst().getAudioData());
        assertThat(comping.compOutputOn(track)).containsExactly(restored);
    }

    private static TakeLane laneWithClip(String name) {
        TakeLane lane = new TakeLane(name);
        AudioClip clip = new AudioClip(name, 0.0, 8.0, null);
        clip.setAudioData(new float[][] { new float[16] });
        lane.addClip(clip);
        return lane;
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
        assertThatThrownBy(() -> new SetCompRegionAction(new Track("Vox", TrackType.AUDIO), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompileCompAction(null))
                .isInstanceOf(NullPointerException.class);
    }
}
