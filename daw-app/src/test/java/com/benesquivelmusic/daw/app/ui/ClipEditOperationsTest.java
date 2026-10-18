package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.SetCompRegionAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the clip editing operations: Paste Over, Trim to Selection, and Crop.
 *
 * <p>These tests exercise the production {@link ClipEditOperations} utility
 * class that {@link TrackStripController} delegates to. No JavaFX toolkit is
 * required.</p>
 */
class ClipEditOperationsTest {

    // ── Paste Over ──────────────────────────────────────────────────────────

    @Test
    void pasteOverShouldPlaceClipAtPlayhead() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip original = new AudioClip("vocal", 2.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(original), 10.0);

        assertThat(track.getClips()).hasSize(1);
        AudioClip pasted = track.getClips().get(0);
        assertThat(pasted.getStartBeat()).isEqualTo(10.0);
        assertThat(pasted.getDurationBeats()).isEqualTo(4.0);
        assertThat(result.addedClips()).containsExactly(pasted);
        assertThat(result.removedClips()).isEmpty();
    }

    @Test
    void pasteOverShouldSplitAndRemoveOverlappingClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Existing clip from beat 4 to beat 12
        AudioClip existing = new AudioClip("drums", 4.0, 8.0, null);
        track.addClip(existing);

        // Paste a 4-beat clip at beat 6 (covers 6..10) — should split existing
        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(toPaste), 6.0);

        // Should have 3 clips: before (4..6), pasted (6..10), after (10..12)
        assertThat(track.getClips()).hasSize(3);
        List<AudioClip> sorted = new ArrayList<>(track.getClips());
        sorted.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));

        assertThat(sorted.get(0).getStartBeat()).isEqualTo(4.0);
        assertThat(sorted.get(0).getEndBeat()).isEqualTo(6.0);
        assertThat(sorted.get(1).getStartBeat()).isEqualTo(6.0);
        assertThat(sorted.get(1).getEndBeat()).isEqualTo(10.0);
        assertThat(sorted.get(2).getStartBeat()).isEqualTo(10.0);
        assertThat(sorted.get(2).getEndBeat()).isEqualTo(12.0);
        assertThat(result.removedClips()).containsExactly(existing);
    }

    @Test
    void pasteOverShouldCompletelyRemoveEnclosedClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Existing clip from beat 5 to beat 7 — completely inside paste region
        AudioClip small = new AudioClip("click", 5.0, 2.0, null);
        track.addClip(small);

        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(toPaste), 4.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0).getStartBeat()).isEqualTo(4.0);
        assertThat(result.removedClips()).containsExactly(small);
    }

    @Test
    void pasteOverWithNoOverlapShouldJustAddClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 0.0, 4.0, null);
        track.addClip(existing);

        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.pasteOver(track, List.of(toPaste), 8.0);

        assertThat(track.getClips()).hasSize(2);
    }

    @Test
    void pasteOverMultipleEntriesShouldNotInterfereWithEachOther() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 2.0, 8.0, null); // 2..10
        track.addClip(existing);

        AudioClip clip1 = new AudioClip("vocal", 0.0, 3.0, null);
        AudioClip clip2 = new AudioClip("guitar", 0.0, 3.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(clip1, clip2), 4.0);

        // Combined paste span is 4..7 (both clips are 3 beats starting at playhead 4)
        // The existing clip (2..10) should be split into before (2..4) and after (7..10)
        // Plus the two pasted clips (both at 4..7)
        assertThat(result.removedClips()).containsExactly(existing);

        List<AudioClip> sorted = new ArrayList<>(track.getClips());
        sorted.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));

        // before portion, then 2 pasted clips, then after portion
        assertThat(sorted.get(0).getStartBeat()).isEqualTo(2.0);
        assertThat(sorted.get(0).getEndBeat()).isEqualTo(4.0);
        // last clip should be the after-split portion
        assertThat(sorted.get(sorted.size() - 1).getStartBeat()).isEqualTo(7.0);
        assertThat(sorted.get(sorted.size() - 1).getEndBeat()).isEqualTo(10.0);
    }

    @Test
    void pasteOverUndoShouldRestoreOriginalState() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip existing = new AudioClip("drums", 4.0, 8.0, null);
        track.addClip(existing);

        AudioClip toPaste = new AudioClip("vocal", 0.0, 4.0, null);

        ClipEditOperations.Result result =
                ClipEditOperations.pasteOver(track, List.of(toPaste), 6.0);

        assertThat(track.getClips()).hasSize(3);

        result.undo(track);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(existing);
    }

    // ── Trim to Selection ───────────────────────────────────────────────────

    @Test
    void trimToSelectionShouldTrimClipToSelectionBoundaries() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null); // 2..12
        track.addClip(clip);

        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 4.0, 8.0);

        assertThat(result).isNotNull();
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0); // offset by 4-2=2
    }

    @Test
    void trimToSelectionShouldHandleSelectionLargerThanClip() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 4.0, null); // 4..8
        track.addClip(clip);

        // Clip is already fully inside selection — trimming should leave it unchanged
        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 2.0, 12.0);

        assertThat(result).isNotNull();
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void trimToSelectionShouldTrimOnlyTheStart() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 6.0, null); // 2..8
        track.addClip(clip);

        ClipEditOperations.trimToSelection(track, 4.0, 10.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void trimToSelectionShouldTrimOnlyTheEnd() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 8.0, null); // 4..12
        track.addClip(clip);

        ClipEditOperations.trimToSelection(track, 2.0, 8.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
    }

    @Test
    void trimToSelectionShouldReturnNullWhenNoClipsOverlap() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 10.0, 4.0, null); // 10..14
        track.addClip(clip);

        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 2.0, 6.0);

        assertThat(result).isNull();
        assertThat(clip.getStartBeat()).isEqualTo(10.0); // unchanged
    }

    @Test
    void trimToSelectionUndoShouldRestoreOriginalClipState() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        ClipEditOperations.TrimResult result =
                ClipEditOperations.trimToSelection(track, 4.0, 8.0);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);

        result.undo();

        assertThat(clip.getStartBeat()).isEqualTo(2.0);
        assertThat(clip.getDurationBeats()).isEqualTo(10.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    // ── Crop ────────────────────────────────────────────────────────────────

    @Test
    void cropShouldRemoveClipsCompletelyOutsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip inside = new AudioClip("inside", 4.0, 4.0, null);   // 4..8
        AudioClip outside = new AudioClip("outside", 12.0, 2.0, null); // 12..14
        track.addClip(inside);
        track.addClip(outside);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 3.0, 9.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0).getName()).isEqualTo("inside");
        assertThat(result.removedClips()).containsExactly(outside);
    }

    @Test
    void cropShouldSplitAndTrimClipsPartiallyInsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Clip spans 2..12 — selection is 4..8
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 4.0, 8.0);

        assertThat(track.getClips()).hasSize(1);
        AudioClip trimmed = track.getClips().get(0);
        assertThat(trimmed.getStartBeat()).isEqualTo(4.0);
        assertThat(trimmed.getEndBeat()).isEqualTo(8.0);
        assertThat(trimmed.getSourceOffsetBeats()).isEqualTo(2.0);
        assertThat(result.removedClips()).containsExactly(clip);
    }

    @Test
    void cropShouldKeepClipsFullyInsideSelection() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 4.0, 2.0, null); // 4..6
        track.addClip(clip);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 2.0, 10.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
        assertThat(result.removedClips()).isEmpty();
        assertThat(result.addedClips()).isEmpty();
    }

    @Test
    void cropWithNoClipsShouldBeNoOp() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 2.0, 10.0);

        assertThat(track.getClips()).isEmpty();
        assertThat(result.removedClips()).isEmpty();
        assertThat(result.addedClips()).isEmpty();
    }

    @Test
    void cropShouldHandleMultipleClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip c1 = new AudioClip("a", 0.0, 4.0, null);    // 0..4
        AudioClip c2 = new AudioClip("b", 4.0, 4.0, null);    // 4..8
        AudioClip c3 = new AudioClip("c", 8.0, 4.0, null);    // 8..12
        AudioClip c4 = new AudioClip("d", 12.0, 4.0, null);   // 12..16
        track.addClip(c1);
        track.addClip(c2);
        track.addClip(c3);
        track.addClip(c4);

        ClipEditOperations.crop(track, 3.0, 10.0);

        // c1 (0..4) partially overlaps → trimmed to 3..4
        // c2 (4..8) fully inside → kept
        // c3 (8..12) partially overlaps → trimmed to 8..10
        // c4 (12..16) fully outside → removed
        assertThat(track.getClips()).hasSize(3);

        List<AudioClip> sorted = new ArrayList<>(track.getClips());
        sorted.sort((a, b) -> Double.compare(a.getStartBeat(), b.getStartBeat()));

        assertThat(sorted.get(0).getStartBeat()).isEqualTo(3.0);
        assertThat(sorted.get(0).getEndBeat()).isEqualTo(4.0);
        assertThat(sorted.get(1).getStartBeat()).isEqualTo(4.0);
        assertThat(sorted.get(1).getEndBeat()).isEqualTo(8.0);
        assertThat(sorted.get(2).getStartBeat()).isEqualTo(8.0);
        assertThat(sorted.get(2).getEndBeat()).isEqualTo(10.0);
    }

    @Test
    void cropUndoShouldRestoreOriginalState() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("vocal", 2.0, 10.0, null);
        track.addClip(clip);

        ClipEditOperations.Result result =
                ClipEditOperations.crop(track, 4.0, 8.0);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isNotSameAs(clip);

        result.undo(track);

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
    }

    // ── Comp output (PR #984 review, F4) ────────────────────────────────────

    @Test
    void pasteOverTheCompLeavesRemnantsThatAreStillCompOutput() {
        Track track = compedTrack();
        TakeComping comping = track.getTakeComping();
        AudioClip comp = track.getClips().getFirst();

        ClipEditOperations.Result result = ClipEditOperations.pasteOver(
                track, List.of(new AudioClip("fill", 0.0, 2.0, null)), 3.0);

        List<AudioClip> sorted = byStart(track.getClips());
        assertThat(sorted).hasSize(3);
        AudioClip before = sorted.get(0);
        AudioClip pasted = sorted.get(1);
        AudioClip after = sorted.get(2);
        assertThat(result.removedClips()).containsExactly(comp);
        assertThat(comping.compOutputOn(track)).containsExactly(before, after);
        assertThat(pasted.getName()).isEqualTo("fill");
        assertThat(comping.isCompOutput(pasted)).as("the pasted clip is the user's").isFalse();

        assertThat(before.getStartBeat()).isEqualTo(0.0);
        assertThat(before.getEndBeat()).isEqualTo(3.0);
        assertThat(before.getSourceOffsetBeats()).isEqualTo(comp.getSourceOffsetBeats());
        assertThat(after.getStartBeat()).isEqualTo(5.0);
        assertThat(after.getEndBeat()).isEqualTo(8.0);
        assertThat(after.getSourceOffsetBeats()).isEqualTo(comp.getSourceOffsetBeats() + 5.0);
        assertThat(before.getAudioData()).isSameAs(comp.getAudioData());
        assertThat(after.getAudioData()).isSameAs(comp.getAudioData());

        result.undo(track);
        assertThat(track.getClips()).containsExactly(comp);
        assertThat(comping.isCompOutput(comp)).isTrue();
    }

    @Test
    void aPastedCopyOfCompOutputIsAUserClip() {
        Track track = compedTrack();
        TakeComping comping = track.getTakeComping();
        AudioClip comp = track.getClips().getFirst();

        ClipEditOperations.pasteOver(track, List.of(comp), 10.0);

        assertThat(track.getClips()).hasSize(2);
        AudioClip pasted = byStart(track.getClips()).get(1);
        assertThat(pasted).isNotSameAs(comp);
        assertThat(pasted.getStartBeat()).isEqualTo(10.0);
        assertThat(comping.isCompOutput(pasted)).isFalse();
        assertThat(comping.compOutputOn(track)).containsExactly(comp);
    }

    @Test
    void cropOfTheCompKeepsTheTrimmedPieceAsCompOutput() {
        Track track = compedTrack();
        TakeComping comping = track.getTakeComping();
        AudioClip comp = track.getClips().getFirst();

        ClipEditOperations.crop(track, 2.0, 6.0);

        assertThat(track.getClips()).hasSize(1);
        AudioClip trimmed = track.getClips().getFirst();
        assertThat(trimmed).isNotSameAs(comp);
        assertThat(comping.compOutputOn(track)).containsExactly(trimmed);
        assertThat(trimmed.getStartBeat()).isEqualTo(2.0);
        assertThat(trimmed.getEndBeat()).isEqualTo(6.0);
        assertThat(trimmed.getSourceOffsetBeats()).isEqualTo(comp.getSourceOffsetBeats() + 2.0);
    }

    @Test
    void aSwipeAfterAPasteOverTheCompReplacesBothRemnantsAndKeepsThePastedClip() {
        Track track = compedTrack();
        TakeComping comping = track.getTakeComping();
        AudioClip fill = new AudioClip("fill", 0.0, 2.0, null);
        ClipEditOperations.pasteOver(track, List.of(fill), 3.0);
        List<AudioClip> remnants = comping.compOutputOn(track);
        AudioClip pasted = byStart(track.getClips()).get(1);
        assertThat(remnants).hasSize(2);

        new UndoManager().execute(new SetCompRegionAction(track, new CompRegion(1, 0.0, 8.0)));

        assertThat(track.getClips()).doesNotContainAnyElementsOf(remnants);
        assertThat(track.getClips()).contains(pasted);
        assertThat(pasted.getStartBeat()).isEqualTo(3.0);
        assertThat(pasted.getEndBeat()).isEqualTo(5.0);
        List<AudioClip> comp = comping.compOutputOn(track);
        assertThat(comp).hasSize(1);
        assertThat(comp.getFirst().getStartBeat()).isEqualTo(0.0);
        assertThat(comp.getFirst().getEndBeat()).isEqualTo(8.0);
        assertThat(comp.getFirst().getAudioData())
                .isSameAs(comping.getTakeLane(1).getClips().getFirst().getAudioData());
        assertThat(track.getClips()).hasSize(2);
    }

    // ── Superseded comp output never re-enters its track (PR #984 review) ──

    /** A recompile of the main lane between the edit and its undo. */
    enum Recompile {
        /** A swipe of lane 1 over [2, 4), then its undo: lane 0 over [0, 8) again. */
        SWIPE_THEN_UNDO_SWIPE,
        /** The COMP tool's Alt-click on lane 1: not in the history. */
        AUDITION,
        /** The audition, then the main-lane click that ends it. */
        AUDITION_THEN_MAIN_LANE_CLICK
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Recompile.class)
    void undoingAPasteOverAfterARecompileNeverPlaysTheCompTwice(Recompile recompile) {
        Track track = compedTrack();
        UndoManager undo = new UndoManager();
        AudioClip fill = new AudioClip("fill", 0.0, 2.0, null);
        // As TrackStripController's Paste Over item builds it.
        undo.execute(new UndoableAction() {
            private ClipEditOperations.Result result;
            @Override public String description() { return "Paste Over"; }
            @Override public void execute() { result = ClipEditOperations.pasteOver(track, List.of(fill), 3.0); }
            @Override public void undo() { result.undo(track); }
        });
        assertComp(track, new double[][] {{0, 3}, {5, 8}}, new int[] {0, 0}, 1);

        recompile(recompile, track, undo);
        int lane = recompile == Recompile.AUDITION ? 1 : 0;
        assertComp(track, new double[][] {{0, 8}}, new int[] {lane}, 1);

        undo.undo(); // the paste: the comp clip it removed was replaced, and stays out
        assertComp(track, new double[][] {{0, 8}}, new int[] {lane}, 0);

        undo.redo(); // the paste again, over the comp the main lane holds now
        assertComp(track, new double[][] {{0, 3}, {5, 8}}, new int[] {lane, lane}, 1);
        if (recompile == Recompile.SWIPE_THEN_UNDO_SWIPE) {
            undo.redo(); // the swipe again; the pasted user clip is kept
            assertComp(track, new double[][] {{2, 4}}, new int[] {1}, 1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Recompile.class)
    void undoingACropAfterARecompileNeverPlaysTheCompTwice(Recompile recompile) {
        Track track = compedTrack();
        UndoManager undo = new UndoManager();
        // As TrackStripController's Crop item builds it.
        undo.execute(new UndoableAction() {
            private ClipEditOperations.Result result;
            @Override public String description() { return "Crop"; }
            @Override public void execute() { result = ClipEditOperations.crop(track, 2.0, 6.0); }
            @Override public void undo() { result.undo(track); }
        });
        assertComp(track, new double[][] {{2, 6}}, new int[] {0}, 0);

        recompile(recompile, track, undo);
        int lane = recompile == Recompile.AUDITION ? 1 : 0;
        assertComp(track, new double[][] {{0, 8}}, new int[] {lane}, 0);

        undo.undo(); // the crop: the comp clip it removed was replaced, and stays out
        assertComp(track, new double[][] {{0, 8}}, new int[] {lane}, 0);

        undo.redo(); // the crop again, of the comp the main lane holds now
        assertComp(track, new double[][] {{2, 6}}, new int[] {lane}, 0);
        if (recompile == Recompile.SWIPE_THEN_UNDO_SWIPE) {
            undo.redo(); // the swipe again
            assertComp(track, new double[][] {{2, 4}}, new int[] {1}, 0);
        }
    }

    private static void recompile(Recompile recompile, Track track, UndoManager undo) {
        TakeComping comping = track.getTakeComping();
        switch (recompile) {
            case SWIPE_THEN_UNDO_SWIPE -> {
                undo.execute(new SetCompRegionAction(track, new CompRegion(1, 2.0, 2.0)));
                undo.undo();
            }
            case AUDITION -> audition(comping, track);
            case AUDITION_THEN_MAIN_LANE_CLICK -> {
                audition(comping, track);
                // As CompToolHandler.clickMainLane ends it.
                if (comping.endAuditions()) {
                    new CompileCompAction(track).execute();
                }
            }
        }
    }

    /** The COMP tool's Alt-click on lane 1, as {@code CompToolHandler.altClickLane} does it. */
    private static void audition(TakeComping comping, Track track) {
        comping.getTakeLane(0).setSoloed(false);
        comping.getTakeLane(1).setSoloed(true);
        new CompileCompAction(track).execute();
    }

    /**
     * Asserts that the track's comp output, in timeline order, covers exactly
     * the given {@code [start, end)} spans from the given lanes — no span
     * plays two comp sources — and that the track holds {@code userClips}
     * other clips besides it.
     */
    private static void assertComp(Track track, double[][] spans, int[] lanes, int userClips) {
        TakeComping comping = track.getTakeComping();
        List<AudioClip> comp = byStart(comping.compOutputOn(track));
        assertThat(comp).as("comp output on the main lane").hasSize(spans.length);
        assertThat(comp).as("all of it output of the current pass").noneMatch(comping::isSupersededOutput);
        for (int i = 0; i < spans.length; i++) {
            AudioClip clip = comp.get(i);
            assertThat(clip.getStartBeat()).as("start of comp clip %d", i).isEqualTo(spans[i][0]);
            assertThat(clip.getEndBeat()).as("end of comp clip %d", i).isEqualTo(spans[i][1]);
            assertThat(clip.getAudioData()).as("source of comp clip %d", i)
                    .isSameAs(comping.getTakeLane(lanes[i]).getClips().getFirst().getAudioData());
        }
        assertThat(track.getClips()).as("clips on the main lane: the comp and %d user clips", userClips)
                .hasSize(spans.length + userClips);
    }

    /**
     * A track with two take lanes, each one 8-beat clip with its own audio
     * array, and lane 0 selected over beats 0-8 and compiled: the main lane
     * holds one comp clip.
     */
    private static Track compedTrack() {
        Track track = new Track("Vox", TrackType.AUDIO);
        for (int i = 1; i <= 2; i++) {
            TakeLane lane = new TakeLane("Take " + i);
            AudioClip take = new AudioClip("Take " + i, 0.0, 8.0, null);
            take.setAudioData(new float[][] { new float[16] });
            lane.addClip(take);
            track.getTakeComping().addTakeLane(lane);
        }
        track.getTakeComping().addCompRegion(new CompRegion(0, 0.0, 8.0));
        new CompileCompAction(track).execute();
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getTakeComping().isCompOutput(track.getClips().getFirst())).isTrue();
        return track;
    }

    private static List<AudioClip> byStart(List<AudioClip> clips) {
        List<AudioClip> sorted = new ArrayList<>(clips);
        sorted.sort(Comparator.comparingDouble(AudioClip::getStartBeat));
        return sorted;
    }
}
