package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
import com.benesquivelmusic.daw.core.audio.DuplicateClipsAction;
import com.benesquivelmusic.daw.core.audio.GlueClipsAction;
import com.benesquivelmusic.daw.core.audio.SplitClipAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * The comp's main-lane output (story 328, book §3.5/§9.1): one clip per
 * selected region, or the soloed lane whole, each referencing the lane's
 * audio and files — no RAM-only render — compiled onto the track through
 * {@link CompileCompAction}, which replaces exactly the comp's own previous
 * output and, on undo, recompiles from the live comping state rather than
 * restoring a snapshot (story 328 review: an audition between a swipe and
 * its undo leaves nothing orphaned).
 */
class TakeCompingCompileTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final double TEMPO_BPM = 120.0;
    private static final double SECONDS_PER_BEAT = 60.0 / TEMPO_BPM; // 0.5s/beat
    // 4-second section at 120 BPM = 8 beats
    private static final double TAKE_DURATION_BEATS = 4.0 / SECONDS_PER_BEAT;
    private static final int TAKE_SAMPLES = (int) Math.round(4.0 * SAMPLE_RATE);

    private TakeComping comping;
    private float[] take1;
    private float[] take2;
    private float[] take3;

    @BeforeEach
    void setUp() {
        comping = new TakeComping();

        // Three distinct, deterministic 4s mono signals.
        take1 = signal(TAKE_SAMPLES, 1.0f);
        take2 = signal(TAKE_SAMPLES, 2.0f);
        take3 = signal(TAKE_SAMPLES, 3.0f);

        comping.addTakeLane(takeLane("Take 1", take1));
        comping.addTakeLane(takeLane("Take 2", take2));
        comping.addTakeLane(takeLane("Take 3", take3));
    }

    @Test
    void shouldCompileOneClipPerRegionOntoTheMainLaneLeavingOtherClipsAlone() {
        Track track = new Track("Vox", TrackType.AUDIO);
        TakeComping trackComping = track.getTakeComping();
        trackComping.addTakeLane(takeLane("Take 1", take1));
        trackComping.addTakeLane(takeLane("Take 2", take2));
        trackComping.addTakeLane(takeLane("Take 3", take3));
        AudioClip preExisting = new AudioClip("old", 0.0, 8.0, null);
        preExisting.setAudioData(new float[][] { new float[TAKE_SAMPLES] });
        track.addClip(preExisting);

        trackComping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        trackComping.addCompRegion(new CompRegion(1, 4.0, 4.0));

        CompileCompAction action = new CompileCompAction(track);
        action.execute();

        assertThat(action.getCompiledClips()).hasSize(2);
        assertThat(track.getClips()).containsExactly(preExisting,
                action.getCompiledClips().get(0), action.getCompiledClips().get(1));
        AudioClip first = action.getCompiledClips().get(0);
        assertThat(first.getStartBeat()).isCloseTo(0.0, offset(1e-9));
        assertThat(first.getDurationBeats()).isCloseTo(4.0, offset(1e-9));
        assertThat(first.getAudioData()).isSameAs(trackComping.getTakeLane(0).getClips().getFirst().getAudioData());
        AudioClip second = action.getCompiledClips().get(1);
        assertThat(second.getStartBeat()).isCloseTo(4.0, offset(1e-9));
        assertThat(second.getSourceOffsetBeats()).isCloseTo(4.0, offset(1e-9));
        assertThat(second.getAudioData()).isSameAs(trackComping.getTakeLane(1).getClips().getFirst().getAudioData());
        assertThat(trackComping.compOutputOn(track)).containsExactlyElementsOf(action.getCompiledClips());
        // The take stack is preserved beneath the main lane.
        assertThat(trackComping.getTakeLaneCount()).isEqualTo(3);
        assertThat(trackComping.getCompRegions()).hasSize(2);

        // A swipe through the history replaces exactly the previous comp
        // output; its undo restores the selection and recompiles it.
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(2, 0.0, 8.0)));
        assertThat(track.getClips()).hasSize(2).first().isSameAs(preExisting);
        assertThat(trackComping.compOutputOn(track)).hasSize(1);
        assertThat(trackComping.compOutputOn(track).getFirst().getAudioData()).isSameAs(
                trackComping.getTakeLane(2).getClips().getFirst().getAudioData());

        undo.undo();
        assertThat(trackComping.getCompRegions())
                .containsExactly(new CompRegion(0, 0.0, 4.0), new CompRegion(1, 4.0, 4.0));
        assertThat(track.getClips()).hasSize(3).first().isSameAs(preExisting);
        assertThat(track.getClips().subList(1, 3)).containsExactlyElementsOf(trackComping.compOutputOn(track));
        assertThat(trackComping.compOutputOn(track).get(0).getAudioData()).isSameAs(first.getAudioData());
        assertThat(trackComping.compOutputOn(track).get(0).getStartBeat()).isCloseTo(0.0, offset(1e-9));
        assertThat(trackComping.compOutputOn(track).get(1).getAudioData()).isSameAs(second.getAudioData());
        assertThat(trackComping.compOutputOn(track).get(1).getSourceOffsetBeats()).isCloseTo(4.0, offset(1e-9));
        assertThat(trackComping.getTakeLaneCount()).isEqualTo(3);
    }

    @Test
    void anAuditionBetweenASwipeAndItsUndoLeavesNothingOrphanedOrDoubledOnTheMainLane() {
        // Story 328 review: the audition (Alt-click) recompiles outside the
        // history. The swipe's undo recompiles from the live state, so it
        // removes the audition's clip too, and the main lane is always
        // exactly what the comping occupies.
        Track track = new Track("Vox", TrackType.AUDIO);
        TakeComping trackComping = track.getTakeComping();
        trackComping.addTakeLane(takeLane("Take 1", take1));
        trackComping.addTakeLane(takeLane("Take 2", take2));
        AudioClip preExisting = new AudioClip("old", 0.0, 8.0, null);
        track.addClip(preExisting);
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 4.0)));
        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 4.0, 4.0)));
        assertThat(trackComping.compOutputOn(track)).hasSize(2);

        // The audition: lane 2 soloed, compiled whole, not in the history.
        trackComping.getTakeLane(1).setSoloed(true);
        new CompileCompAction(track).execute();
        List<AudioClip> audition = trackComping.compOutputOn(track);
        assertThat(audition).hasSize(1);
        assertThat(track.getClips()).containsExactly(preExisting, audition.getFirst());

        undo.undo(); // the second swipe
        assertThat(trackComping.getCompRegions()).containsExactly(new CompRegion(0, 0.0, 4.0));
        assertThat(track.getClips()).as("the audition's clip is gone; nothing is doubled")
                .doesNotContain(audition.getFirst())
                .containsExactly(preExisting, trackComping.compOutputOn(track).getFirst());
        // PR #984 review: an audition is not replayed by an undo — the undo
        // ends it and plays the restored selection.
        assertThat(trackComping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertThat(trackComping.compOutputOn(track)).hasSize(1);
        assertThat(trackComping.compOutputOn(track).getFirst().getAudioData())
                .isSameAs(trackComping.getTakeLane(0).getClips().getFirst().getAudioData());
        assertThat(trackComping.compOutputOn(track).getFirst().getDurationBeats()).isCloseTo(4.0, offset(1e-9));

        undo.undo(); // the first swipe: no selection left, no comp clip
        assertThat(trackComping.getCompRegions()).isEmpty();
        assertThat(track.getClips()).containsExactly(preExisting);
        assertThat(trackComping.compOutputOn(track)).isEmpty();
    }

    /** A track whose comping holds the fixture's three takes, each over [0, 8). */
    private Track trackWithThreeTakes() {
        Track track = new Track("Vox", TrackType.AUDIO);
        TakeComping trackComping = track.getTakeComping();
        trackComping.addTakeLane(takeLane("Take 1", take1));
        trackComping.addTakeLane(takeLane("Take 2", take2));
        trackComping.addTakeLane(takeLane("Take 3", take3));
        return track;
    }

    static float[][] audioOfLane(Track track, int laneIndex) {
        return track.getTakeComping().getTakeLane(laneIndex).getClips().getFirst().getAudioData();
    }

    /**
     * Asserts that the track's clips, in timeline order, cover exactly the
     * given {@code [start, end)} spans — no span plays two sources — and
     * that each plays the audio of the given lane and is output of the
     * comping's current pass: a clip an earlier compile replaced, which can
     * share a fresh clip's span and source, does not pass for it.
     */
    static void assertMainLane(Track track, double[][] spans, int[] lanes) {
        List<AudioClip> clips = new ArrayList<>(track.getClips());
        clips.sort(Comparator.comparingDouble(AudioClip::getStartBeat));
        assertThat(clips).as("clips on the main lane").hasSize(spans.length);
        TakeComping trackComping = track.getTakeComping();
        assertThat(clips).as("every main-lane clip is output of the current pass")
                .allMatch(clip -> trackComping.isCompOutput(clip) && !trackComping.isSupersededOutput(clip));
        double total = 0.0;
        for (int i = 0; i < spans.length; i++) {
            AudioClip clip = clips.get(i);
            assertThat(clip.getStartBeat()).as("start of clip %d", i).isCloseTo(spans[i][0], offset(1e-9));
            assertThat(clip.getEndBeat()).as("end of clip %d", i).isCloseTo(spans[i][1], offset(1e-9));
            assertThat(clip.getAudioData()).as("source of clip %d", i).isSameAs(audioOfLane(track, lanes[i]));
            if (i > 0) {
                assertThat(clip.getStartBeat()).as("clip %d starts after clip %d ends", i, i - 1)
                        .isGreaterThanOrEqualTo(clips.get(i - 1).getEndBeat() - 1e-9);
            }
            total += clip.getDurationBeats();
        }
        double expectedTotal = 0.0;
        for (double[] span : spans) {
            expectedTotal += span[1] - span[0];
        }
        assertThat(total).as("summed comp output").isCloseTo(expectedTotal, offset(1e-9));
    }

    @Test
    void aSwipeAfterAScissorsSplitOfTheCompReplacesBothHalves() {
        // PR #984 review: the split's second half was not known to the comp,
        // so the next swipe removed only the first half and the second went
        // on playing under the new comp output.
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 4.0)));
        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 4.0, 4.0)));
        AudioClip laneZeroComp = track.getClips().stream()
                .filter(clip -> clip.getStartBeat() == 0.0).findFirst().orElseThrow();

        undo.execute(new SplitClipAction(track, laneZeroComp, 2.0));
        assertMainLane(track, new double[][] {{0, 2}, {2, 4}, {4, 8}}, new int[] {0, 0, 1});
        assertThat(trackComping.compOutputOn(track)).as("both halves are comp output").hasSize(3);

        undo.execute(new SetCompRegionAction(track, new CompRegion(2, 3.0, 2.0)));
        assertMainLane(track, new double[][] {{3, 5}}, new int[] {2});

        undo.undo(); // the swipe: the previous selection, freshly compiled
        assertMainLane(track, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
        undo.undo(); // the split: its clip left the track with the recompile
        assertMainLane(track, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
        undo.redo(); // the split again: nothing to split, no stray half
        assertMainLane(track, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
        undo.redo(); // the swipe again
        assertMainLane(track, new double[][] {{3, 5}}, new int[] {2});
        assertThat(trackComping.compOutputOn(track)).containsExactlyElementsOf(track.getClips());
    }

    @Test
    void anAuditionAfterASplitDoesNotLeaveTheSecondHalfBehind() {
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 8.0)));
        undo.execute(new SplitClipAction(track, track.getClips().getFirst(), 4.0));
        assertMainLane(track, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 0});

        // Alt-click on lane 2, as CompToolHandler.altClickLane does it.
        for (int i = 0; i < trackComping.getTakeLaneCount(); i++) {
            trackComping.getTakeLane(i).setSoloed(i == 1);
        }
        new CompileCompAction(track).execute();

        assertMainLane(track, new double[][] {{0, 8}}, new int[] {1});
    }

    @Test
    void aDuplicateOfTheCompSurvivesARecompile() {
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 4.0)));
        AudioClip comp = track.getClips().getFirst();
        undo.execute(new DuplicateClipsAction(List.of(Map.entry(track, comp))));
        AudioClip copy = track.getClips().getLast();
        assertThat(copy.getStartBeat()).as("fixture: the duplicate follows the comp").isEqualTo(4.0);
        assertThat(trackComping.isCompOutput(copy)).as("a duplicate is the user's clip").isFalse();

        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 0.0, 2.0)));

        assertThat(track.getClips()).as("the comp was replaced, the duplicate kept").contains(copy)
                .doesNotContain(comp).hasSize(2);
        assertThat(trackComping.compOutputOn(track)).singleElement()
                .matches(clip -> clip.getAudioData() == audioOfLane(track, 1));
    }

    @Test
    void glueOfTwoCompHalvesIsCompOutput() {
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 8.0)));
        AudioClip comp = track.getClips().getFirst();
        undo.execute(new SplitClipAction(track, comp, 4.0));
        AudioClip secondHalf = track.getClips().getLast();
        undo.execute(new GlueClipsAction(track, comp, secondHalf));
        AudioClip glued = track.getClips().getFirst();
        assertThat(trackComping.isCompOutput(glued)).isTrue();

        undo.execute(new SetCompRegionAction(track, new CompRegion(2, 0.0, 8.0)));

        assertMainLane(track, new double[][] {{0, 8}}, new int[] {2});
        assertThat(track.getClips()).doesNotContain(glued);
    }

    @Test
    void glueOfACompHalfAndAUserClipIsAUserClip() {
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 4.0)));
        AudioClip comp = track.getClips().getFirst();
        // A user clip of the same source right after it, so the two can be glued.
        AudioClip user = comp.duplicate();
        user.setStartBeat(4.0);
        user.setSourceOffsetBeats(4.0);
        track.addClip(user);
        undo.execute(new GlueClipsAction(track, comp, user));
        AudioClip glued = track.getClips().getFirst();
        assertThat(trackComping.isCompOutput(glued)).isFalse();

        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 0.0, 2.0)));

        // The accepted cost until future story 353: the glue of comp output
        // with a user clip is a user clip, so the swipe keeps it and layers
        // its compile under it — over [0, 2) take 1 and take 2 both play.
        assertThat(track.getClips())
                .as("the user's glue over [0, 8) is kept and the new comp over [0, 2) is layered under it")
                .contains(glued).hasSize(2);
        assertThat(trackComping.compOutputOn(track)).singleElement()
                .matches(clip -> clip.getAudioData() == audioOfLane(track, 1));
    }

    @Test
    void aCompClipMovedToAnotherTrackIsNotRemovedByARecompile() {
        Track track = trackWithThreeTakes();
        Track other = new Track("Doubles", TrackType.AUDIO);
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 4.0)));
        AudioClip comp = track.getClips().getFirst();
        undo.execute(new CrossTrackMoveAction(track, other, comp, 0.0));
        assertThat(track.getClips()).isEmpty();
        assertThat(trackComping.isCompOutput(comp))
                .as("on the other track the clip still carries the source comping's owner").isTrue();

        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 0.0, 4.0)));

        assertThat(other.getClips()).as("the moved clip stays on the other track").containsExactly(comp);
        assertThat(trackComping.isSupersededOutput(comp))
                .as("the kept owner is superseded by the source track's recompile, yet inert on the other track")
                .isTrue();
        assertMainLane(track, new double[][] {{0, 4}}, new int[] {1});
        assertThat(other.getTakeComping().compOutputOn(other)).as("the other track's comping owns nothing").isEmpty();
    }

    @Test
    void aRecompileReplacesOutputOfEveryPassNotOnlyTheCurrentOne() {
        // Probe F4 (PR #984 review): occupancy matches any pass of the
        // comping. Set up in-package, since no production path leaves an
        // earlier pass's clip on the track: compile without replacing.
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        new SetCompRegionAction(track, new CompRegion(0, 0.0, 8.0)).execute();
        AudioClip earlierPass = track.getClips().getFirst();
        trackComping.compile();
        assertThat(trackComping.isSupersededOutput(earlierPass)).as("premise: an earlier pass's clip").isTrue();
        assertThat(track.getClips()).as("premise: still on the track").containsExactly(earlierPass);

        new CompileCompAction(track).execute();

        assertThat(track.getClips()).doesNotContain(earlierPass);
        assertMainLane(track, new double[][] {{0, 8}}, new int[] {0});
    }

    @Test
    void aUserClipOnTheTrackIsNeverRemovedByARecompile() {
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        AudioClip user = new AudioClip("Import", 2.0, 4.0, "/audio/import.wav");
        track.addClip(user);
        UndoManager undo = new UndoManager();

        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 8.0)));
        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 0.0, 8.0)));
        new CompileCompAction(track).execute();
        undo.undo();
        undo.undo();

        assertThat(track.getClips()).containsExactly(user);
        assertThat(trackComping.isCompOutput(user)).isFalse();
    }

    @Test
    void swipeUndoAndRedoWhileAuditioningPlayTheSelection() {
        // PR #984 review: an audition is a monitoring state, not an edit. A
        // swipe, its undo and its redo end it and present the selection.
        Track track = trackWithThreeTakes();
        TakeComping trackComping = track.getTakeComping();
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(0, 0.0, 4.0)));

        trackComping.getTakeLane(2).setSoloed(true);
        new CompileCompAction(track).execute();
        assertMainLane(track, new double[][] {{0, 8}}, new int[] {2});
        undo.execute(new SetCompRegionAction(track, new CompRegion(1, 4.0, 4.0)));
        assertThat(trackComping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertMainLane(track, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});

        trackComping.getTakeLane(2).setSoloed(true);
        new CompileCompAction(track).execute();
        undo.undo();
        assertThat(trackComping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertMainLane(track, new double[][] {{0, 4}}, new int[] {0});

        trackComping.getTakeLane(2).setSoloed(true);
        new CompileCompAction(track).execute();
        undo.redo();
        assertThat(trackComping.getTakeLanes()).noneMatch(TakeLane::isSoloed);
        assertMainLane(track, new double[][] {{0, 4}, {4, 8}}, new int[] {0, 1});
    }

    @Test
    void shouldCompileTheSoloedLaneWholeInsteadOfTheSelection() {
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        comping.getTakeLane(2).setSoloed(true);

        List<AudioClip> compiled = comping.compile();

        assertThat(compiled).hasSize(1);
        assertThat(compiled.getFirst().getStartBeat()).isEqualTo(0.0);
        assertThat(compiled.getFirst().getDurationBeats()).isCloseTo(TAKE_DURATION_BEATS, offset(1e-9));
        assertThat(compiled.getFirst().getAudioData()).isSameAs(comping.getTakeLane(2).getClips().getFirst().getAudioData());

        comping.getTakeLane(2).setSoloed(false);
        assertThat(comping.compile().getFirst().getAudioData())
                .isSameAs(comping.getTakeLane(0).getClips().getFirst().getAudioData());
    }

    @Test
    void shouldCompileOneClipPerRegionInSelectionOrderSpanningTheSection() {
        List<CompRegion> regions = List.of(
                new CompRegion(0, 0.0, 4.0),
                new CompRegion(1, 4.0, 2.0),
                new CompRegion(2, 6.0, 2.0));
        for (CompRegion r : regions) {
            comping.addCompRegion(r);
        }
        List<AudioClip> compiled = comping.compile();
        assertThat(compiled).hasSize(3);
        assertThat(compiled.getFirst().getStartBeat()).isCloseTo(0.0, offset(1e-9));
        assertThat(compiled.getLast().getStartBeat() + compiled.getLast().getDurationBeats())
                .isCloseTo(8.0, offset(1e-9));
        assertThat(compiled.get(1).getAudioData()).isSameAs(comping.getTakeLane(1).getClips().getFirst().getAudioData());
        assertThat(compiled.get(1).getSourceOffsetBeats()).isCloseTo(4.0, offset(1e-9));
    }

    @Test
    void shouldCompileNothingWhenNoRegionIsSelected() {
        assertThat(comping.compile()).isEmpty();
    }

    private static TakeLane takeLane(String name, float[] samples) {
        TakeLane lane = new TakeLane(name);
        AudioClip clip = new AudioClip(name + " clip", 0.0, TAKE_DURATION_BEATS, null);
        clip.setAudioData(new float[][] { samples });
        lane.addClip(clip);
        return lane;
    }

    private static float[] signal(int n, float marker) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            // Distinct, deterministic per-take signal: sample = marker * (i+1)/n
            out[i] = marker * ((float) (i + 1) / (float) n);
        }
        return out;
    }
}
