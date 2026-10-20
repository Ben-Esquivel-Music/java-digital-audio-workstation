package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.GlueClipsAction;
import com.benesquivelmusic.daw.core.audio.GroupMoveClipsAction;
import com.benesquivelmusic.daw.core.audio.RemoveClipAction;
import com.benesquivelmusic.daw.core.audio.SplitClipAction;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.edit.RippleEditService;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.comping.TakeCompingCompileTest.assertMainLane;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Output a later compile replaced never re-enters its own track (Recording
 * Reliability book §3.5; PR #984 review): an undo that puts back a clip
 * snapshot after a recompile — a swipe and its undo, an audition, an
 * audition the main-lane click ended — must not leave the comp playing
 * twice. After every recompile, undo and redo that follows the edit, the
 * main lane is exactly what the comping state says it is, and an undo that
 * is refused announces no clip ({@code ClipEvent.Added}). A comp clip moved
 * to another track keeps its owner, inert there; moved back, or put back by
 * an undo, with no recompile in between, it is comp output again. A redo of
 * a move replays the owner decision the move's first execute made, so a
 * recompile between the move home and its undo never lets a redo land the
 * replaced clip on its track.
 */
class SupersededCompOutputTest {

    private static final double TAKE_BEATS = 8.0;
    private static final int TAKE_SAMPLES = 192_000;

    /** A clip edit of the comp clip over [0, 8), executed through the history. */
    enum Edit {
        ERASE, CUT, RIPPLE_DELETE, CROSS_TRACK_MOVE, GROUP_CROSS_TRACK_MOVE, SPLIT, GLUE;

        boolean movesToTheOtherTrack() {
            return this == CROSS_TRACK_MOVE || this == GROUP_CROSS_TRACK_MOVE;
        }
    }

    /** A recompile of the main lane between the edit and its undo. */
    enum Recompile {
        /** A swipe of lane 1 over [2, 4), then its undo: lane 0 over [0, 8) again. */
        SWIPE_THEN_UNDO_SWIPE,
        /** The COMP tool's Alt-click on lane 1: not in the history. */
        AUDITION,
        /** The audition, then the main-lane click that ends it. */
        AUDITION_THEN_MAIN_LANE_CLICK
    }

    static Stream<Arguments> editsAndRecompiles() {
        return Arrays.stream(Edit.values()).flatMap(edit ->
                Arrays.stream(Recompile.values()).map(recompile -> Arguments.of(edit, recompile)));
    }

    @ParameterizedTest(name = "{0} then {1}")
    @MethodSource("editsAndRecompiles")
    void undoingAClipEditAfterARecompileNeverPlaysTheCompTwice(Edit edit, Recompile recompile) {
        Track track = trackWithThreeTakes();
        Track other = new Track("Doubles", TrackType.AUDIO);
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, TAKE_BEATS)));
        AudioClip comp = track.getClips().getFirst();

        try (ClipEventCapture events = new ClipEventCapture()) {
            perform(edit, track, other, comp, undo);
            recompile(recompile, track, undo);
            double[][] whole = {{0, TAKE_BEATS}};
            int[] playing = {recompile == Recompile.AUDITION ? 1 : 0};
            assertMainLane(track, whole, playing);

            events.drainAndClear();
            undo.undo(); // the edit
            List<ClipEvent> undoEvents = events.drainAndClear();
            assertMainLane(track, whole, playing);
            assertThat(track.getTakeComping().compOutputOn(track)).containsExactlyElementsOf(track.getClips());
            assertThat(addedClipIds(undoEvents)).as("the refused re-add announces nothing")
                    .doesNotContain(UUID.fromString(comp.getId()))
                    .allMatch(id -> idsOf(track.getClips()).contains(id), "names a clip on the track");
            if (edit.movesToTheOtherTrack()) {
                assertThat(other.getClips()).as("the move's undo took the clip off the other track").isEmpty();
            }

            undo.redo(); // the edit again: its comp clip was replaced, so it finds nothing to edit
            List<ClipEvent> redoEvents = events.drainAndClear();
            assertMainLane(track, whole, playing);
            if (edit.movesToTheOtherTrack()) {
                assertThat(other.getClips()).as("the move's redo puts the clip back where the user moved it")
                        .containsExactly(comp);
                assertThat(other.getTakeComping().compOutputOn(other))
                        .as("on the other track no comping owns it: it behaves as a user clip").isEmpty();
                assertThat(redoEvents).as("the redo announces no removal from a track that did not hold the clip")
                        .noneMatch(event -> event instanceof ClipEvent.Removed removed
                                && removed.trackId().equals(UUID.fromString(track.getId())));
            }
        }
        if (recompile == Recompile.SWIPE_THEN_UNDO_SWIPE) {
            undo.redo(); // the swipe again
            assertMainLane(track, new double[][] {{2, 4}}, new int[] {1});
        }
    }

    /** How a test moves a clip between tracks. */
    enum MoveForm {
        CROSS_TRACK_MOVE, GROUP_MOVE;

        UndoableAction move(Track from, Track to, AudioClip clip) {
            return switch (this) {
                case CROSS_TRACK_MOVE -> new CrossTrackMoveAction(from, to, clip, clip.getStartBeat());
                case GROUP_MOVE -> new GroupMoveClipsAction(List.of(Map.entry(from, clip)), 0.0, 1, List.of(from, to));
            };
        }
    }

    @ParameterizedTest
    @EnumSource(MoveForm.class)
    void aCompClipMovedToAnotherTrackAndBackIsCompOutputAgainAndTheNextSwipeReplacesIt(MoveForm form) {
        // PR #984 review: a move there and back with no recompile in between
        // is a net no-op; it must not leave a user clip a swipe plays under.
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        TakeComping comping = trackA.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(0, 0.0, 4.0)));
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(1, 4.0, 4.0)));
        AudioClip comp = clipStartingAt(trackA, 0.0);

        undo.execute(form.move(trackA, trackB, comp));
        assertThat(trackB.getClips()).as("premise: moved").containsExactly(comp);
        assertThat(trackB.getTakeComping().compOutputOn(trackB)).as("a user clip on track B").isEmpty();
        undo.execute(form.move(trackB, trackA, comp));

        assertThat(trackB.getClips()).isEmpty();
        assertThat(comping.isCompOutput(comp) && !comping.isSupersededOutput(comp))
                .as("back on track A it is output of the current pass").isTrue();
        assertThat(comping.compOutputOn(trackA)).contains(comp).hasSize(2);

        undo.execute(new SetCompRegionAction(trackA, new CompRegion(2, 4.0, 4.0)));

        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 2});
        assertThat(trackA.getClips()).as("the swipe replaced the moved-back clip").doesNotContain(comp);
    }

    @ParameterizedTest
    @EnumSource(MoveForm.class)
    void undoingACompClipMoveWithNoRecompileInBetweenPutsItBackAsCompOutput(MoveForm form) {
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        TakeComping comping = trackA.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(0, 0.0, 4.0)));
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(1, 4.0, 4.0)));
        AudioClip comp = clipStartingAt(trackA, 0.0);
        undo.execute(form.move(trackA, trackB, comp));

        undo.undo(); // the move
        assertThat(trackB.getClips()).isEmpty();
        assertThat(trackA.getClips()).as("the restored owner is accepted").contains(comp);
        assertThat(comping.compOutputOn(trackA)).contains(comp);
        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});

        undo.redo(); // the move again
        assertThat(trackB.getClips()).containsExactly(comp);
        assertThat(trackA.getClips()).doesNotContain(comp).hasSize(1);

        undo.undo(); // the move, again
        assertThat(trackA.getClips()).contains(comp);
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(2, 0.0, 4.0)));
        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {2, 1});
        assertThat(trackA.getClips()).as("the swipe replaced the restored clip, not layered under it")
                .doesNotContain(comp);
    }

    @Test
    void addClipRefusesOutputOfAnEarlierCompile() {
        Track track = trackWithThreeTakes();
        new SetCompRegionAction(track, new CompRegion(0, 0.0, TAKE_BEATS)).execute();
        AudioClip earlier = track.getClips().getFirst();
        new SetCompRegionAction(track, new CompRegion(1, 0.0, TAKE_BEATS)).execute();
        assertThat(track.getTakeComping().isSupersededOutput(earlier)).isTrue();

        assertThat(track.addClip(earlier)).isFalse();
        assertThat(track.getClips()).doesNotContain(earlier).hasSize(1);
    }

    @Test
    void addClipAcceptsOutputOfTheCurrentCompile() {
        Track track = trackWithThreeTakes();
        new SetCompRegionAction(track, new CompRegion(0, 0.0, TAKE_BEATS)).execute();
        AudioClip current = track.getClips().getFirst();
        track.removeClip(current);
        assertThat(track.getTakeComping().isSupersededOutput(current)).isFalse();

        assertThat(track.addClip(current)).isTrue();
        assertThat(track.getClips()).containsExactly(current);
    }

    @Test
    void addClipAcceptsAUserClip() {
        Track track = trackWithThreeTakes();
        new SetCompRegionAction(track, new CompRegion(0, 0.0, TAKE_BEATS)).execute();
        AudioClip user = track.getClips().getFirst().duplicate();
        new SetCompRegionAction(track, new CompRegion(1, 0.0, TAKE_BEATS)).execute();

        assertThat(track.addClip(user)).isTrue();
        assertThat(track.getClips()).contains(user);
    }

    @Test
    void addClipAcceptsAnotherCompingsOutput() {
        Track track = trackWithThreeTakes();
        Track source = trackWithThreeTakes();
        new SetCompRegionAction(source, new CompRegion(0, 0.0, TAKE_BEATS)).execute();
        AudioClip foreign = source.getClips().getFirst();
        new SetCompRegionAction(source, new CompRegion(1, 0.0, TAKE_BEATS)).execute();
        new SetCompRegionAction(track, new CompRegion(2, 0.0, TAKE_BEATS)).execute();
        assertThat(source.getTakeComping().isSupersededOutput(foreign)).as("premise").isTrue();

        assertThat(track.addClip(foreign)).as("only its own comping's output is judged").isTrue();
        assertThat(track.getClips()).contains(foreign);
    }

    @ParameterizedTest
    @EnumSource(MoveForm.class)
    void aStaleCompClipMovedBackToItsTrackLandsThereAsAUserClip(MoveForm form) {
        // Comp clip moved A -> B, A recompiled: the clip on B is superseded
        // output of A's comping, so a deliberate new move back to A clears
        // its owner and lands it as a user clip instead of being refused.
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(0, 0.0, 4.0)));
        AudioClip comp = trackA.getClips().getFirst();
        undo.execute(form.move(trackA, trackB, comp));
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(1, 0.0, 4.0)));

        undo.execute(form.move(trackB, trackA, comp));

        assertThat(trackB.getClips()).isEmpty();
        assertThat(trackA.getClips()).as("the moved clip and the comp of lane 1").contains(comp).hasSize(2);
        assertThat(comp.compOutputOwner()).as("a user clip on track A").isEmpty();
        assertThat(trackA.getTakeComping().compOutputOn(trackA)).doesNotContain(comp).hasSize(1);
    }

    /** Each move form with each recompile that leaves track A's selection as it was. */
    static Stream<Arguments> moveFormsAndRecompilesThatKeepTheSelection() {
        return Arrays.stream(MoveForm.values()).flatMap(form ->
                Stream.of(Recompile.SWIPE_THEN_UNDO_SWIPE, Recompile.AUDITION_THEN_MAIN_LANE_CLICK)
                        .map(recompile -> Arguments.of(form, recompile)));
    }

    @ParameterizedTest(name = "{0} then {1}")
    @MethodSource("moveFormsAndRecompilesThatKeepTheSelection")
    void redoingBothMovesOfARoundTripAfterARecompileReplaysThemAndNeverPlaysTheCompTwice(
            MoveForm form, Recompile recompile) {
        // PR #984 review: the redo of the move home re-decided the owner rule
        // against the recompiled comp and landed the replaced clip as a user
        // clip over the fresh lane-0 slice: the range played twice.
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        UndoManager undo = new UndoManager();
        AudioClip comp = movedThereAndBackThenRecompiled(form, recompile, trackA, trackB, undo);

        undo.undo(); // the move home
        undo.undo(); // the move away: refused on A, whose comp already plays the range
        undo.redo(); // the move away again
        undo.redo(); // the move home again: it kept the owner, so the replaced clip is refused

        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
        assertThat(trackA.getClips()).as("the replaced clip is not back on track A").doesNotContain(comp);
        assertThat(trackB.getClips()).as("as after the original move home and the recompile").isEmpty();
    }

    @ParameterizedTest(name = "{0} then {1}")
    @MethodSource("moveFormsAndRecompilesThatKeepTheSelection")
    void redoingTheMoveHomeAfterARecompileThereReplaysItAndNeverPlaysTheCompTwice(
            MoveForm form, Recompile recompile) {
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        UndoManager undo = new UndoManager();
        AudioClip comp = movedThereAndBackThenRecompiled(form, recompile, trackA, trackB, undo);

        undo.undo(); // the move home: the clip goes back to track B
        assertThat(trackB.getClips()).as("premise: the undo put the clip back on track B").containsExactly(comp);
        undo.redo(); // the move home again

        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
        assertThat(trackA.getClips()).as("the replaced clip is not back on track A").doesNotContain(comp);
        assertThat(trackB.getClips()).as("as after the original move home and the recompile").isEmpty();
    }

    @ParameterizedTest(name = "{0} then {1}")
    @MethodSource("moveFormsAndRecompilesThatKeepTheSelection")
    void undoingTheMoveHomeAfterARecompileTookTheClipOffAnnouncesNoRemovalFromThatTrack(
            MoveForm form, Recompile recompile) {
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        UndoManager undo = new UndoManager();
        AudioClip comp = movedThereAndBackThenRecompiled(form, recompile, trackA, trackB, undo);

        List<ClipEvent> undoEvents;
        try (ClipEventCapture events = new ClipEventCapture()) {
            undo.undo(); // the move home: track A no longer holds the clip
            undoEvents = events.drainAndClear();
        }

        UUID clipId = UUID.fromString(comp.getId());
        assertThat(undoEvents).as("no removal from track A, which did not hold the clip")
                .noneMatch(event -> event instanceof ClipEvent.Removed removed
                        && removed.trackId().equals(UUID.fromString(trackA.getId())));
        assertThat(undoEvents).as("the clip's return to track B is announced")
                .anyMatch(event -> event instanceof ClipEvent.Added added
                        && added.trackId().equals(UUID.fromString(trackB.getId()))
                        && added.clipId().equals(clipId));
    }

    @ParameterizedTest
    @EnumSource(value = Recompile.class, names = {"SWIPE_THEN_UNDO_SWIPE", "AUDITION_THEN_MAIN_LANE_CLICK"})
    void aGroupMoveHomeDecidesEachClipsOwnerOnItsOwnAndARedoReplaysEachDecision(Recompile recompile) {
        // PR #984 review (probe H3): every other group move of comp output in
        // these tests holds one entry, so a flag read from the wrong entry
        // went unnoticed. Here the two entries decide differently, and each
        // clip must follow its own decision, at the first execute and at a redo.
        Track trackA = trackWithThreeTakes();
        Track trackB = new Track("Doubles", TrackType.AUDIO);
        TakeComping comping = trackA.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(0, 0.0, 4.0)));
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(1, 4.0, 4.0)));
        AudioClip stale = clipStartingAt(trackA, 0.0);
        undo.execute(new CrossTrackMoveAction(trackA, trackB, stale, 0.0));
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(2, 0.0, 4.0)));
        AudioClip current = clipStartingAt(trackA, 4.0);
        undo.execute(new CrossTrackMoveAction(trackA, trackB, current, 4.0));
        assertThat(comping.isSupersededOutput(stale))
                .as("premise: the lane-2 swipe replaced the lane-0 clip while it was on track B").isTrue();
        assertThat(comping.isCompOutput(current) && !comping.isSupersededOutput(current))
                .as("premise: the lane-1 clip left track A as output of the current pass").isTrue();
        assertThat(trackB.getClips()).as("premise: both on track B").containsExactlyInAnyOrder(stale, current);

        undo.execute(new GroupMoveClipsAction(List.of(Map.entry(trackB, stale), Map.entry(trackB, current)),
                0.0, -1, List.of(trackA, trackB)));

        assertThat(trackB.getClips()).as("both clips left track B").isEmpty();
        assertThat(trackA.getClips()).as("both clips landed on track A").contains(stale, current).hasSize(3);
        assertThat(stale.getStartBeat()).isEqualTo(0.0);
        assertThat(current.getStartBeat()).isEqualTo(4.0);
        assertThat(stale.compOutputOwner()).as("the stale clip cleared its owner: a user clip").isEmpty();
        assertThat(comping.isCompOutput(current) && !comping.isSupersededOutput(current))
                .as("the current clip kept its owner: comp output again").isTrue();
        assertCompOutput(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {2, 1});
        assertNoTakePlaysASpanTwice(trackA);

        recompile(recompile, trackA, undo);

        assertThat(trackA.getClips()).as("the recompile replaced the comp clip and left the user clip")
                .contains(stale).doesNotContain(current).hasSize(3);
        assertCompOutput(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {2, 1});
        assertNoTakePlaysASpanTwice(trackA);

        undo.undo(); // the group move: both clips go back to track B with their saved owners
        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {2, 1});
        assertThat(trackB.getClips()).as("the undo put both clips back on track B")
                .containsExactlyInAnyOrder(stale, current);
        assertThat(comping.isSupersededOutput(stale)).as("the stale clip's owner is restored").isTrue();
        assertThat(comping.isSupersededOutput(current))
                .as("the current clip's owner is restored, and the recompile has replaced it").isTrue();

        undo.redo(); // the group move again: each entry replays its own decision

        assertThat(trackB.getClips()).as("both clips left track B again").isEmpty();
        assertThat(trackA.getClips()).as("the stale clip lands again; the replaced one, owner kept, is refused")
                .contains(stale).doesNotContain(current).hasSize(3);
        assertThat(stale.getStartBeat()).isEqualTo(0.0);
        assertThat(stale.compOutputOwner()).as("the stale clip is a user clip again").isEmpty();
        assertThat(comping.isSupersededOutput(current)).as("the refused clip kept its owner").isTrue();
        assertCompOutput(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {2, 1});
        assertNoTakePlaysASpanTwice(trackA);
    }

    /**
     * As {@link TakeCompingCompileTest#assertMainLane}, over the track's comp
     * output only ({@link TakeComping#compOutputOn}): the comp covers exactly
     * the given spans with the given lanes, all output of the current pass.
     * User clips beside it are the caller's to assert.
     */
    private static void assertCompOutput(Track track, double[][] spans, int[] lanes) {
        TakeComping comping = track.getTakeComping();
        List<AudioClip> output = new ArrayList<>(comping.compOutputOn(track));
        output.sort(Comparator.comparingDouble(AudioClip::getStartBeat));
        assertThat(output).as("comp output on the track").hasSize(spans.length);
        for (int i = 0; i < spans.length; i++) {
            AudioClip clip = output.get(i);
            assertThat(comping.isSupersededOutput(clip)).as("comp clip %d is current output", i).isFalse();
            assertThat(clip.getStartBeat()).as("start of comp clip %d", i).isEqualTo(spans[i][0]);
            assertThat(clip.getEndBeat()).as("end of comp clip %d", i).isEqualTo(spans[i][1]);
            assertThat(clip.getAudioData()).as("source of comp clip %d", i)
                    .isSameAs(comping.getTakeLane(lanes[i]).getClips().getFirst().getAudioData());
        }
    }

    /** No two clips on the track play the same take's audio over overlapping beats. */
    private static void assertNoTakePlaysASpanTwice(Track track) {
        List<AudioClip> clips = track.getClips();
        for (int i = 0; i < clips.size(); i++) {
            for (int j = i + 1; j < clips.size(); j++) {
                AudioClip first = clips.get(i);
                AudioClip second = clips.get(j);
                boolean sameTake = first.getAudioData() == second.getAudioData();
                boolean overlapping = first.getStartBeat() < second.getEndBeat()
                        && second.getStartBeat() < first.getEndBeat();
                assertThat(sameTake && overlapping)
                        .as("%s and %s play the same take over overlapping beats", first.getName(), second.getName())
                        .isFalse();
            }
        }
    }

    /**
     * Track A comps lane 0 over [0, 4) and lane 1 over [4, 8); its lane-0
     * comp clip moves to track B and back with no recompile in between, so
     * it is A's comp output again; then {@code recompile} replaces it with a
     * fresh lane-0 slice under the same selection. Returns the moved clip.
     */
    private static AudioClip movedThereAndBackThenRecompiled(MoveForm form, Recompile recompile,
                                                             Track trackA, Track trackB, UndoManager undo) {
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(0, 0.0, 4.0)));
        undo.execute(new SetCompRegionAction(trackA, new CompRegion(1, 4.0, 4.0)));
        AudioClip comp = clipStartingAt(trackA, 0.0);
        undo.execute(form.move(trackA, trackB, comp));
        undo.execute(form.move(trackB, trackA, comp));
        assertThat(trackA.getTakeComping().isCompOutput(comp)
                && !trackA.getTakeComping().isSupersededOutput(comp))
                .as("premise: back home with no recompile, it is current comp output").isTrue();

        recompile(recompile, trackA, undo);

        assertMainLane(trackA, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
        assertThat(trackA.getClips()).as("premise: the recompile replaced the moved clip").doesNotContain(comp);
        return comp;
    }

    private static void perform(Edit edit, Track track, Track other, AudioClip comp, UndoManager undo) {
        switch (edit) {
            case ERASE -> undo.execute(new RemoveClipAction(track, comp));
            case CUT -> undo.execute(new CutClipsAction(List.of(Map.entry(track, comp))));
            case RIPPLE_DELETE -> undo.execute(RippleEditService.buildRippleDelete(
                    List.of(Map.entry(track, comp)), RippleMode.PER_TRACK, List.of(track),
                    OptionalDouble.empty(), OptionalDouble.empty()));
            case CROSS_TRACK_MOVE -> undo.execute(new CrossTrackMoveAction(track, other, comp, 0.0));
            case GROUP_CROSS_TRACK_MOVE -> undo.execute(new GroupMoveClipsAction(
                    List.of(Map.entry(track, comp)), 0.0, 1, List.of(track, other)));
            case SPLIT -> undo.execute(new SplitClipAction(track, comp, 4.0));
            case GLUE -> {
                undo.execute(new SplitClipAction(track, comp, 4.0));
                undo.execute(new GlueClipsAction(track, comp, track.getClips().getLast()));
            }
        }
        if (edit.movesToTheOtherTrack()) {
            assertThat(other.getClips()).as("premise: moved").containsExactly(comp);
        }
    }

    private static void recompile(Recompile recompile, Track track, UndoManager undo) {
        TakeComping comping = track.getTakeComping();
        switch (recompile) {
            case SWIPE_THEN_UNDO_SWIPE -> {
                undo.execute(new SetCompRegionAction(track, new CompRegion(1, 2.0, 2.0)));
                undo.undo();
            }
            case AUDITION -> audition(track, 1);
            case AUDITION_THEN_MAIN_LANE_CLICK -> {
                audition(track, 1);
                // As CompToolHandler.clickMainLane ends it.
                if (comping.endAuditions()) {
                    new CompileCompAction(track).execute();
                }
            }
        }
    }

    /** The COMP tool's Alt-click, as {@code CompToolHandler.altClickLane} does it. */
    private static void audition(Track track, int laneIndex) {
        TakeComping comping = track.getTakeComping();
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            comping.getTakeLane(i).setSoloed(i == laneIndex);
        }
        new CompileCompAction(track).execute();
    }

    private static AudioClip clipStartingAt(Track track, double startBeat) {
        return track.getClips().stream().filter(clip -> clip.getStartBeat() == startBeat).findFirst().orElseThrow();
    }

    private static List<UUID> idsOf(List<AudioClip> clips) {
        return clips.stream().map(clip -> UUID.fromString(clip.getId())).toList();
    }

    private static List<UUID> addedClipIds(List<ClipEvent> events) {
        List<UUID> added = new ArrayList<>();
        for (ClipEvent event : events) {
            if (event instanceof ClipEvent.Added a) {
                added.add(a.clipId());
            }
        }
        return added;
    }

    /**
     * Installs a {@link DefaultEventBus} as the publisher's default and
     * collects every {@link ClipEvent} through one subscription. The bus
     * delivers on that subscription's own worker, in publish order, so
     * {@link #drainAndClear()} publishes a marker event no production code
     * publishes and waits for it: once the marker is delivered, everything
     * published before it has been, which makes "nothing was announced"
     * assertable. Restores the default bus on close.
     */
    private static final class ClipEventCapture implements AutoCloseable {

        private final DefaultEventBus bus = DefaultEventBus.builder().build();
        private final List<ClipEvent> events = new CopyOnWriteArrayList<>();
        private volatile ClipEvent marker;
        private volatile CountDownLatch markerDelivered;

        ClipEventCapture() {
            bus.on(ClipEvent.class, event -> {
                if (event == marker) {
                    markerDelivered.countDown();
                } else {
                    events.add(event);
                }
            });
            EventBusPublisher.setDefault(bus);
        }

        /** Waits until every event published so far is delivered, then returns and forgets them. */
        List<ClipEvent> drainAndClear() {
            markerDelivered = new CountDownLatch(1);
            marker = new ClipEvent.Renamed(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
            bus.publish(marker);
            try {
                assertThat(markerDelivered.await(5, TimeUnit.SECONDS)).as("the bus drained").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while draining the bus", e);
            }
            List<ClipEvent> delivered = List.copyOf(events);
            events.clear();
            return delivered;
        }

        @Override
        public void close() {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    /** A track whose comping holds three takes over [0, 8), each with its own audio. */
    private static Track trackWithThreeTakes() {
        Track track = new Track("Vox", TrackType.AUDIO);
        for (int take = 1; take <= 3; take++) {
            TakeLane lane = new TakeLane("Take " + take);
            AudioClip clip = new AudioClip("Take " + take + " clip", 0.0, TAKE_BEATS, null);
            float[] samples = new float[TAKE_SAMPLES];
            Arrays.fill(samples, take);
            clip.setAudioData(new float[][] {samples});
            lane.addClip(clip);
            track.getTakeComping().addTakeLane(lane);
        }
        return track;
    }
}
