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
 * Headless tests that exercise the multi-take comping pipeline end-to-end:
 * record three takes of a 4-second section, select the first 2&nbsp;s of
 * take&nbsp;1, the 2-3&nbsp;s slice of take&nbsp;2, and the 3-4&nbsp;s slice
 * of take&nbsp;3, render the composite, and assert it matches the analytical
 * stitch.
 */
class CompManagerTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final double TEMPO_BPM = 120.0;
    private static final double SECONDS_PER_BEAT = 60.0 / TEMPO_BPM; // 0.5s/beat
    // 4-second section at 120 BPM = 8 beats
    private static final double TAKE_DURATION_BEATS = 4.0 / SECONDS_PER_BEAT;
    private static final int TAKE_SAMPLES = (int) Math.round(4.0 * SAMPLE_RATE);

    private TakeComping comping;
    private CompManager mgr;
    private float[] take1;
    private float[] take2;
    private float[] take3;

    @BeforeEach
    void setUp() {
        comping = new TakeComping();
        mgr = new CompManager(comping);
        // Disable crossfades for analytically-exact comparisons.
        mgr.setCrossfadeMs(0.0);

        // Three distinct, deterministic 4s mono signals.
        take1 = signal(TAKE_SAMPLES, 1.0f);
        take2 = signal(TAKE_SAMPLES, 2.0f);
        take3 = signal(TAKE_SAMPLES, 3.0f);

        comping.addTakeLane(takeLane("Take 1", take1));
        comping.addTakeLane(takeLane("Take 2", take2));
        comping.addTakeLane(takeLane("Take 3", take3));
    }

    @Test
    void shouldStitchThreeTakesIntoExpectedCompositeWaveform() {
        // 0-2 s of take 1 = 0..4 beats
        // 2-3 s of take 2 = 4..6 beats
        // 3-4 s of take 3 = 6..8 beats
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 2.0));
        comping.addCompRegion(new CompRegion(2, 6.0, 2.0));

        float[][] composite = mgr.renderComposite(SAMPLE_RATE, TEMPO_BPM);

        assertThat(composite).hasNumberOfRows(1);
        assertThat(composite[0]).hasSize(TAKE_SAMPLES);

        // First 2 s should equal the first 2 s of take 1.
        int twoSeconds = (int) Math.round(2.0 * SAMPLE_RATE);
        int threeSeconds = (int) Math.round(3.0 * SAMPLE_RATE);
        for (int i = 0; i < twoSeconds; i++) {
            assertThat(composite[0][i]).isEqualTo(take1[i]);
        }
        // 2-3 s should equal take 2's samples in [2s, 3s).
        for (int i = twoSeconds; i < threeSeconds; i++) {
            assertThat(composite[0][i]).isEqualTo(take2[i]);
        }
        // 3-4 s should equal take 3's samples in [3s, 4s).
        for (int i = threeSeconds; i < TAKE_SAMPLES; i++) {
            assertThat(composite[0][i]).isEqualTo(take3[i]);
        }
    }

    @Test
    void shouldCompileToSingleAudioClipReplacingMainLane() {
        Track track = new Track("Vox", TrackType.AUDIO);
        AudioClip preExisting = new AudioClip("old", 0.0, 8.0, null);
        // Attach silent audio so getAudioData/getEndBeat are sane.
        preExisting.setAudioData(new float[][] { new float[TAKE_SAMPLES] });
        track.addClip(preExisting);

        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 4.0));

        UndoManager undo = new UndoManager();
        CompileCompAction action = new CompileCompAction(track, mgr, SAMPLE_RATE, TEMPO_BPM);
        undo.execute(action);

        // Pre-existing clip is replaced; new compiled clip is on the lane.
        assertThat(track.getClips()).hasSize(1);
        AudioClip compiled = track.getClips().getFirst();
        assertThat(compiled.getStartBeat()).isCloseTo(0.0, offset(1e-9));
        assertThat(compiled.getDurationBeats()).isCloseTo(8.0, offset(1e-9));
        assertThat(compiled.getAudioData()).isNotNull();
        // The take stack is preserved beneath the main lane.
        assertThat(comping.getTakeLaneCount()).isEqualTo(3);
        assertThat(comping.getCompRegions()).hasSize(2);

        // Undo restores the original clips and leaves the take stack intact.
        undo.undo();
        assertThat(track.getClips()).containsExactly(preExisting);
        assertThat(comping.getTakeLaneCount()).isEqualTo(3);
    }

    @Test
    void shouldApplyEqualPowerCrossfadeAtTouchingBoundary() {
        mgr.setCrossfadeMs(2.0); // 96 samples at 48 kHz
        // Two adjacent regions that touch at beat 4 (= 2 s).
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 4.0));

        float[][] composite = mgr.renderComposite(SAMPLE_RATE, TEMPO_BPM);
        int xfade = (int) Math.round(0.002 * SAMPLE_RATE);
        int boundary = (int) Math.round(2.0 * SAMPLE_RATE);

        // Mid-crossfade sample: combine take1's sample (already in buffer)
        // with take2's sample at the equivalent source position via equal-power
        // (cos/sin) gains.
        int midOffset = xfade / 2;
        int dstIdx = boundary - xfade + midOffset;
        int srcStart2 = boundary; // take 2 starts at beat 4 = 2 s
        double t = (midOffset + 1) / (double) xfade;
        double angle = t * Math.PI / 2.0;
        float prevSample = take1[dstIdx];
        float incomingSample = take2[srcStart2 - xfade + midOffset];
        float expected = (float) (prevSample * Math.cos(angle)
                + incomingSample * Math.sin(angle));
        assertThat(composite[0][dstIdx]).isEqualTo(expected);
        // Outside the crossfade: pure regions.
        assertThat(composite[0][0]).isEqualTo(take1[0]);
        assertThat(composite[0][composite[0].length - 1]).isEqualTo(take2[take2.length - 1]);
    }

    @Test
    void shouldReturnEmptyCompositeWhenNoRegions() {
        float[][] composite = mgr.renderComposite(SAMPLE_RATE, TEMPO_BPM);
        assertThat(composite).hasDimensions(0, 0);
        assertThat(mgr.compileToClip(SAMPLE_RATE, TEMPO_BPM)).isNull();
    }

    @Test
    void shouldRejectInvalidArguments() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mgr.renderComposite(0, TEMPO_BPM));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mgr.renderComposite(SAMPLE_RATE, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mgr.setCrossfadeMs(-1));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new CompManager(null));
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

    @Test
    void shouldSupportListsOfRegionsAndCompileViaAction() {
        List<CompRegion> regions = List.of(
                new CompRegion(0, 0.0, 4.0),
                new CompRegion(1, 4.0, 2.0),
                new CompRegion(2, 6.0, 2.0));
        for (CompRegion r : regions) {
            comping.addCompRegion(r);
        }
        AudioClip compiled = mgr.compileToClip(SAMPLE_RATE, TEMPO_BPM);
        assertThat(compiled).isNotNull();
        assertThat(compiled.getDurationBeats()).isCloseTo(8.0, offset(1e-9));
    }
}
