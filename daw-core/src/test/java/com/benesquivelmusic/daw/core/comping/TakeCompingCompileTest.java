package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThat(trackComping.getCompClips()).containsExactlyElementsOf(action.getCompiledClips());
        // The take stack is preserved beneath the main lane.
        assertThat(trackComping.getTakeLaneCount()).isEqualTo(3);
        assertThat(trackComping.getCompRegions()).hasSize(2);

        // A swipe through the history replaces exactly the previous comp
        // output; its undo restores the selection and recompiles it.
        UndoManager undo = new UndoManager();
        undo.execute(new SetCompRegionAction(track, new CompRegion(2, 0.0, 8.0)));
        assertThat(track.getClips()).hasSize(2).first().isSameAs(preExisting);
        assertThat(trackComping.getCompClips()).hasSize(1);
        assertThat(trackComping.getCompClips().getFirst().getAudioData()).isSameAs(
                trackComping.getTakeLane(2).getClips().getFirst().getAudioData());

        undo.undo();
        assertThat(trackComping.getCompRegions())
                .containsExactly(new CompRegion(0, 0.0, 4.0), new CompRegion(1, 4.0, 4.0));
        assertThat(track.getClips()).hasSize(3).first().isSameAs(preExisting);
        assertThat(track.getClips().subList(1, 3)).containsExactlyElementsOf(trackComping.getCompClips());
        assertThat(trackComping.getCompClips().get(0).getAudioData()).isSameAs(first.getAudioData());
        assertThat(trackComping.getCompClips().get(0).getStartBeat()).isCloseTo(0.0, offset(1e-9));
        assertThat(trackComping.getCompClips().get(1).getAudioData()).isSameAs(second.getAudioData());
        assertThat(trackComping.getCompClips().get(1).getSourceOffsetBeats()).isCloseTo(4.0, offset(1e-9));
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
        assertThat(trackComping.getCompClips()).hasSize(2);

        // The audition: lane 2 soloed, compiled whole, not in the history.
        trackComping.getTakeLane(1).setSoloed(true);
        new CompileCompAction(track).execute();
        List<AudioClip> audition = trackComping.getCompClips();
        assertThat(audition).hasSize(1);
        assertThat(track.getClips()).containsExactly(preExisting, audition.getFirst());

        undo.undo(); // the second swipe
        assertThat(trackComping.getCompRegions()).containsExactly(new CompRegion(0, 0.0, 4.0));
        assertThat(track.getClips()).as("the audition's clip is gone; nothing is doubled")
                .doesNotContain(audition.getFirst())
                .containsExactly(preExisting, trackComping.getCompClips().getFirst());
        assertThat(trackComping.getCompClips()).as("the lane is still soloed, so the audition still plays")
                .hasSize(1);
        assertThat(trackComping.getCompClips().getFirst().getAudioData()).isSameAs(take2Of(trackComping));

        // Back to the composite: the restored selection plays.
        trackComping.getTakeLane(1).setSoloed(false);
        new CompileCompAction(track).execute();
        assertThat(track.getClips()).containsExactly(preExisting, trackComping.getCompClips().getFirst());
        assertThat(trackComping.getCompClips().getFirst().getAudioData())
                .isSameAs(trackComping.getTakeLane(0).getClips().getFirst().getAudioData());
        assertThat(trackComping.getCompClips().getFirst().getDurationBeats()).isCloseTo(4.0, offset(1e-9));

        undo.undo(); // the first swipe: no selection left, no comp clip
        assertThat(trackComping.getCompRegions()).isEmpty();
        assertThat(track.getClips()).containsExactly(preExisting);
        assertThat(trackComping.getCompClips()).isEmpty();
    }

    private static float[][] take2Of(TakeComping trackComping) {
        return trackComping.getTakeLane(1).getClips().getFirst().getAudioData();
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
