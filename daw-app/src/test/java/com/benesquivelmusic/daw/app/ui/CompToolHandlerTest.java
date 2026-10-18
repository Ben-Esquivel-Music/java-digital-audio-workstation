package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.SplitClipAction;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompToolHandlerTest {

    private Track track;
    private TakeComping comping;
    private UndoManager undo;
    private CompToolHandler handler;

    @BeforeEach
    void setUp() {
        track = new Track("Vox", TrackType.AUDIO);
        comping = track.getTakeComping();
        comping.addTakeLane(laneWithClip("Take 1"));
        comping.addTakeLane(laneWithClip("Take 2"));
        comping.addTakeLane(laneWithClip("Take 3"));
        undo = new UndoManager();
        handler = new CompToolHandler(track, undo);
    }

    @Test
    void swipeRecompilesTheMainLaneAtOnceAndUndoRestoresThePreviousCompClip() {
        // Story 328 (book §5.6): "the comped clip plays the selection" — a
        // swipe on lane 1 over [2, 4) replaces the compiled clip on the
        // track's main lane; the lanes are untouched; undo restores the
        // previous comp clip.
        comping.addCompRegion(new CompRegion(0, 0.0, 8.0));
        new CompileCompAction(track).execute();
        AudioClip before = track.getClips().getFirst();
        assertThat(before.getAudioData()).isSameAs(audioOfLane(0));

        handler.beginSwipe(1, 2.0);
        assertThat(handler.endSwipe(4.0)).isEqualTo(new CompRegion(1, 2.0, 2.0));

        assertThat(track.getClips()).as("the previous comp clip was replaced").doesNotContain(before);
        assertThat(track.getClips()).as("the swipe deselected lane 0's overlapping region, so only the swipe plays")
                .hasSize(1);
        AudioClip after = track.getClips().getFirst();
        assertThat(after.getStartBeat()).isEqualTo(2.0);
        assertThat(after.getDurationBeats()).isEqualTo(2.0);
        assertThat(after.getSourceOffsetBeats()).isEqualTo(2.0);
        assertThat(after.getAudioData()).isSameAs(audioOfLane(1));
        assertThat(comping.getTakeLanes()).allSatisfy(lane -> assertThat(lane.getClips()).hasSize(1));

        undo.undo();
        // Story 328 review: the undo recompiles the restored selection from
        // the live comping state — the previous comp, freshly sliced.
        assertThat(comping.getCompRegions()).containsExactly(new CompRegion(0, 0.0, 8.0));
        assertThat(track.getClips()).hasSize(1);
        AudioClip restored = track.getClips().getFirst();
        assertThat(restored.getStartBeat()).isEqualTo(before.getStartBeat());
        assertThat(restored.getDurationBeats()).isEqualTo(before.getDurationBeats());
        assertThat(restored.getSourceOffsetBeats()).isEqualTo(before.getSourceOffsetBeats());
        assertThat(restored.getAudioData()).isSameAs(audioOfLane(0));
        assertThat(comping.compOutputOn(track)).containsExactly(restored);
    }

    @Test
    void anAuditionBetweenASwipeAndItsUndoLeavesNoOrphanedClipOnTheMainLane() {
        // Story 328 review: Alt-click recompiles outside the history; the
        // swipe's undo recompiles from the live state, so the audition's
        // clip is removed with everything else the comp occupied.
        handler.beginSwipe(0, 0.0);
        handler.endSwipe(4.0);
        handler.beginSwipe(1, 4.0);
        handler.endSwipe(8.0);
        assertThat(track.getClips()).hasSize(2);

        handler.altClickLane(1);
        AudioClip audition = track.getClips().getFirst();
        assertThat(track.getClips()).hasSize(1);
        assertThat(audition.getAudioData()).isSameAs(audioOfLane(1));

        undo.undo();
        assertThat(track.getClips()).as("the audition's clip is gone, nothing is doubled")
                .doesNotContain(audition).hasSize(1)
                .containsExactlyElementsOf(comping.compOutputOn(track));
        assertThat(comping.getCompRegions()).containsExactly(new CompRegion(0, 0.0, 4.0));

        handler.clickMainLane();
        assertThat(track.getClips()).hasSize(1).containsExactlyElementsOf(comping.compOutputOn(track));
        assertThat(track.getClips().getFirst().getAudioData()).isSameAs(audioOfLane(0));
        assertThat(track.getClips().getFirst().getDurationBeats()).isEqualTo(4.0);
    }

    @Test
    void altClickPlaysTheSoloedLaneWholeAndMainLaneClickReturnsToTheComp() {
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        new CompileCompAction(track).execute();
        AudioClip comp = track.getClips().getFirst();

        handler.altClickLane(2);
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getAudioData()).isSameAs(audioOfLane(2));
        assertThat(track.getClips().getFirst().getDurationBeats()).isEqualTo(8.0);
        assertThat(undo.canUndo()).as("an audition is not an edit").isFalse();

        handler.clickMainLane();
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getAudioData()).isSameAs(audioOfLane(0));
        assertThat(track.getClips().getFirst().getDurationBeats()).isEqualTo(comp.getDurationBeats());
    }

    private float[][] audioOfLane(int laneIndex) {
        return comping.getTakeLane(laneIndex).getClips().getFirst().getAudioData();
    }

    @Test
    void swipeOnTakeLaneCommitsUndoableCompRegion() {
        handler.beginSwipe(0, 0.0);
        assertThat(handler.isSwipeActive()).isTrue();
        CompRegion region = handler.endSwipe(2.0);

        assertThat(region).isEqualTo(new CompRegion(0, 0.0, 2.0));
        assertThat(comping.getCompRegions()).contains(region);
        assertThat(handler.isSwipeActive()).isFalse();

        undo.undo();
        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void emptySwipeIsCancelled() {
        handler.beginSwipe(0, 1.0);
        assertThat(handler.endSwipe(1.0)).isNull();
        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void backwardSwipeIsNormalizedToPositiveDuration() {
        handler.beginSwipe(0, 3.0);
        CompRegion region = handler.endSwipe(1.0);
        assertThat(region).isEqualTo(new CompRegion(0, 1.0, 2.0));
        assertThat(comping.getCompRegions()).contains(region);
    }

    @Test
    void altClickSolosOneLaneAndUnsolosOthers() {
        comping.getTakeLane(0).setSoloed(true);
        handler.altClickLane(2);

        assertThat(comping.getTakeLane(0).isSoloed()).isFalse();
        assertThat(comping.getTakeLane(1).isSoloed()).isFalse();
        assertThat(comping.getTakeLane(2).isSoloed()).isTrue();
    }

    @Test
    void clickMainLaneClearsAllSolos() {
        handler.altClickLane(1);
        handler.clickMainLane();
        assertThat(comping.getTakeLanes()).allMatch(l -> !l.isSoloed());
    }

    @Test
    void aMainLaneClickRecompilesOnlyToEndAnAudition() {
        // PR #984 review: with no audition playing, a main-lane click must
        // not recompile — that would replace the user's edit of the comp
        // output with no undo entry and make the edited clips superseded.
        handler.beginSwipe(0, 0.0);
        handler.endSwipe(8.0);
        undo.execute(new SplitClipAction(track, track.getClips().getFirst(), 4.0));
        List<AudioClip> edited = List.copyOf(track.getClips());
        List<List<Double>> bounds = edited.stream()
                .map(clip -> List.of(clip.getStartBeat(), clip.getEndBeat())).toList();
        int undoEntries = undo.undoSize();

        handler.clickMainLane();

        assertThat(track.getClips()).as("the same clips: no compile ran").containsExactlyElementsOf(edited);
        assertThat(track.getClips().stream().map(clip -> List.of(clip.getStartBeat(), clip.getEndBeat())))
                .as("with their bounds").containsExactlyElementsOf(bounds);
        assertThat(edited).as("no new pass superseded them").noneMatch(comping::isSupersededOutput);
        assertThat(undo.undoSize()).as("no undo entry").isEqualTo(undoEntries);

        handler.altClickLane(1);
        handler.clickMainLane();

        assertThat(comping.getTakeLanes()).as("the audition ended").noneMatch(TakeLane::isSoloed);
        assertThat(track.getClips()).as("the recompile replaced the edited clips").doesNotContainAnyElementsOf(edited)
                .singleElement().matches(clip -> clip.getStartBeat() == 0.0 && clip.getEndBeat() == 8.0
                        && clip.getAudioData() == audioOfLane(0), "lane 0 over [0, 8)");
        assertThat(undo.undoSize()).as("an audition is not an undo entry").isEqualTo(undoEntries);
    }

    @Test
    void rejectsOutOfRangeArguments() {
        assertThatThrownBy(() -> handler.beginSwipe(-1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> handler.beginSwipe(99, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> handler.beginSwipe(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.altClickLane(99))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void endSwipeRejectsNegativeEndBeat() {
        handler.beginSwipe(0, 1.0);
        assertThatThrownBy(() -> handler.endSwipe(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TakeLane laneWithClip(String name) {
        TakeLane lane = new TakeLane(name);
        AudioClip clip = new AudioClip(name, 0.0, 8.0, null);
        clip.setAudioData(new float[][] { new float[32] });
        lane.addClip(clip);
        return lane;
    }
}
