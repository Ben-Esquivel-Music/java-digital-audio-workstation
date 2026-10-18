package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.SetCompRegionAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link TrackStripController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 *
 * <p>Story 322 deleted {@code midiInstrumentIcon} together with the MIDI
 * instrument-hint icons it fed (the insert row now renders the channel's real
 * rack — see {@code InsertIndicatorTruthTest}); its keyword tests went with it.</p>
 */
class TrackStripControllerTest {

    // ── computeDropTargetIndex ───────────────────────────────────────────────

    @Test
    void shouldReturnNegativeOneWhenDropOnSamePosition() {
        // Dragging track 1 and dropping above track 2 results in no-op (same position)
        assertThat(TrackStripController.computeDropTargetIndex(1, 1, true)).isEqualTo(-1);
    }

    @Test
    void shouldMoveTrackDownWhenDropBelow() {
        // 3 tracks [0,1,2]: drag 0, drop below track 2 → target = 2
        assertThat(TrackStripController.computeDropTargetIndex(0, 2, false)).isEqualTo(2);
    }

    @Test
    void shouldMoveTrackUpWhenDropAbove() {
        // 3 tracks [0,1,2]: drag 2, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(2, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldHandleAdjacentMoveDown() {
        // 3 tracks [0,1,2]: drag 0, drop below track 1 → target = 1
        assertThat(TrackStripController.computeDropTargetIndex(0, 1, false)).isEqualTo(1);
    }

    @Test
    void shouldHandleAdjacentMoveUp() {
        // 3 tracks [0,1,2]: drag 1, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(1, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldReturnNegativeOneWhenDropBelowSelf() {
        // Dragging track 1 and dropping below track 0 → stays at index 1 → no-op
        assertThat(TrackStripController.computeDropTargetIndex(1, 0, false)).isEqualTo(-1);
    }

    @Test
    void shouldHandleDropAboveTopHalfAdjacentBelow() {
        // 3 tracks [0,1,2]: drag 0, drop above track 1 → stays at 0 → no-op
        assertThat(TrackStripController.computeDropTargetIndex(0, 1, true)).isEqualTo(-1);
    }

    @Test
    void shouldHandleMoveToEnd() {
        // 5 tracks [0..4]: drag 0, drop below track 4 → target = 4
        assertThat(TrackStripController.computeDropTargetIndex(0, 4, false)).isEqualTo(4);
    }

    @Test
    void shouldHandleMoveToStart() {
        // 5 tracks [0..4]: drag 4, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(4, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldHandleMoveFromMiddleDown() {
        // 5 tracks [0..4]: drag 2, drop below track 3 → target = 3
        assertThat(TrackStripController.computeDropTargetIndex(2, 3, false)).isEqualTo(3);
    }

    @Test
    void shouldHandleMoveFromMiddleUp() {
        // 5 tracks [0..4]: drag 2, drop above track 1 → target = 1
        assertThat(TrackStripController.computeDropTargetIndex(2, 1, true)).isEqualTo(1);
    }

    // ── abbreviateInsertName (story 322 insert indicators) ───────────────────

    @Test
    void abbreviatesInsertNamesToThreeUpperCaseCharacters() {
        assertThat(TrackStripController.abbreviateInsertName("Compressor")).isEqualTo("COM");
        assertThat(TrackStripController.abbreviateInsertName("EQ")).isEqualTo("EQ");
        assertThat(TrackStripController.abbreviateInsertName("  reverb ")).isEqualTo("REV");
        assertThat(TrackStripController.abbreviateInsertName("   ")).isEqualTo("?");
    }

    // ── splitAtPlayheadAction (PR #984 review) ───────────────────────────────

    @Test
    void splitAtPlayheadIsDescribedByTheTrackAndNeedsAStraddlingClip() {
        Track track = compedTrack();

        assertThat(TrackStripController.splitAtPlayheadAction(track, 4.0).orElseThrow().description())
                .isEqualTo("Split at Playhead: Vox");
        assertThat(TrackStripController.splitAtPlayheadAction(track, 8.0))
                .as("a playhead at a clip edge splits nothing").isEmpty();
    }

    @Test
    void splitAtPlayheadGivesBothHalvesOfACompClipItsOwner() {
        Track track = compedTrack();
        TakeComping comping = track.getTakeComping();

        new UndoManager().execute(TrackStripController.splitAtPlayheadAction(track, 3.0).orElseThrow());

        assertThat(track.getClips()).hasSize(2).allMatch(comping::isCompOutput);
        assertThat(comping.compOutputOn(track)).containsExactlyElementsOf(track.getClips());
    }

    @Test
    void undoingSplitAtPlayheadRestoresTheDurationAndFadeOut() {
        Track track = compedTrack();
        AudioClip comp = track.getClips().getFirst();
        comp.setFadeOutBeats(0.5);
        UndoManager undo = new UndoManager();
        undo.execute(TrackStripController.splitAtPlayheadAction(track, 3.0).orElseThrow());
        assertThat(comp.getDurationBeats()).as("premise: split").isEqualTo(3.0);

        undo.undo();

        assertThat(track.getClips()).containsExactly(comp);
        assertThat(comp.getDurationBeats()).isEqualTo(8.0);
        assertThat(comp.getFadeOutBeats()).isEqualTo(0.5);
    }

    @Test
    void redoingSplitAtPlayheadAfterARecompileSplitsOnlyTheUserClip() {
        Track track = compedTrack();
        TakeComping comping = track.getTakeComping();
        AudioClip comp = track.getClips().getFirst();
        AudioClip user = new AudioClip("Import", 2.0, 4.0, "/audio/import.wav");
        track.addClip(user);
        UndoManager undo = new UndoManager();
        undo.execute(TrackStripController.splitAtPlayheadAction(track, 4.0).orElseThrow());
        assertThat(track.getClips()).as("premise: both clips split").hasSize(4);

        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 2.0, 2.0)));
        undo.undo(); // the swipe: lane 0 over [0, 8), freshly compiled
        undo.undo(); // the split: the comp clip it split left the track
        undo.redo(); // the split again

        List<AudioClip> output = comping.compOutputOn(track);
        assertThat(output).as("the fresh comp, unsplit: no half of the replaced clip").singleElement()
                .matches(clip -> clip != comp && clip.getStartBeat() == 0.0 && clip.getEndBeat() == 8.0);
        assertThat(comp.getDurationBeats()).as("the replaced comp clip is left as it was").isEqualTo(8.0);
        List<AudioClip> userClips = new ArrayList<>(track.getClips());
        userClips.removeAll(output);
        userClips.sort(Comparator.comparingDouble(AudioClip::getStartBeat));
        assertThat(userClips).as("the user clip split again").hasSize(2).first().isSameAs(user);
        assertThat(user.getEndBeat()).isEqualTo(4.0);
        assertThat(userClips.getLast().getStartBeat()).isEqualTo(4.0);
        assertThat(userClips.getLast().getEndBeat()).isEqualTo(6.0);
    }

    /** A track with two 8-beat take lanes and lane 0 selected over [0, 8) and compiled. */
    private static Track compedTrack() {
        Track track = new Track("Vox", TrackType.AUDIO);
        for (int i = 1; i <= 2; i++) {
            TakeLane lane = new TakeLane("Take " + i);
            AudioClip take = new AudioClip("Take " + i, 0.0, 8.0, null);
            take.setAudioData(new float[][] {new float[16]});
            lane.addClip(take);
            track.getTakeComping().addTakeLane(lane);
        }
        new SetCompRegionAction(track, new CompRegion(0, 0.0, 8.0)).execute();
        assertThat(track.getClips()).as("premise: one comp clip").hasSize(1);
        return track;
    }
}
