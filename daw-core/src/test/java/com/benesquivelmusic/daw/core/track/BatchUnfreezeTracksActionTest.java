package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchUnfreezeTracksActionTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;

    @Test
    void shouldHaveCorrectDescription() {
        BatchUnfreezeTracksAction action = new BatchUnfreezeTracksAction(
                List.of(), t -> null, SAMPLE_RATE, TEMPO, CHANNELS);
        assertThat(action.description()).isEqualTo("Batch Unfreeze Tracks");
    }

    @Test
    void shouldUnfreezeAllFrozenTracksOnExecute() {
        Track t1 = createTrackWithAudio("T1");
        Track t2 = createTrackWithAudio("T2");
        MixerChannel c1 = new MixerChannel("C1");
        MixerChannel c2 = new MixerChannel("C2");
        TrackFreezeService.freeze(t1, c1, SAMPLE_RATE, TEMPO, CHANNELS);
        TrackFreezeService.freeze(t2, c2, SAMPLE_RATE, TEMPO, CHANNELS);

        BatchUnfreezeTracksAction action = new BatchUnfreezeTracksAction(
                List.of(t1, t2), t -> t == t1 ? c1 : c2,
                SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();

        assertThat(t1.isFrozen()).isFalse();
        assertThat(t2.isFrozen()).isFalse();
    }

    @Test
    void shouldRefreezeOnlyTracksUnfrozenByThisActionOnUndo() {
        Track frozen = createTrackWithAudio("frozen");
        Track unfrozen = createTrackWithAudio("unfrozen");
        MixerChannel c1 = new MixerChannel("C1");
        MixerChannel c2 = new MixerChannel("C2");
        // Pre-state: only `frozen` is actually frozen.
        TrackFreezeService.freeze(frozen, c1, SAMPLE_RATE, TEMPO, CHANNELS);

        BatchUnfreezeTracksAction action = new BatchUnfreezeTracksAction(
                List.of(frozen, unfrozen), t -> t == frozen ? c1 : c2,
                SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();
        action.undo();

        assertThat(frozen.isFrozen()).isTrue();   // re-frozen by undo
        assertThat(unfrozen.isFrozen()).isFalse(); // never touched
    }

    @Test
    void shouldRoundTripThroughUndoManager() {
        Track t1 = createTrackWithAudio("T1");
        MixerChannel c1 = new MixerChannel("C1");
        TrackFreezeService.freeze(t1, c1, SAMPLE_RATE, TEMPO, CHANNELS);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new BatchUnfreezeTracksAction(
                List.of(t1), t -> c1, SAMPLE_RATE, TEMPO, CHANNELS));
        assertThat(t1.isFrozen()).isFalse();
        undoManager.undo();
        assertThat(t1.isFrozen()).isTrue();
        undoManager.redo();
        assertThat(t1.isFrozen()).isFalse();
    }

    @Test
    void shouldHandleEmptyTrackList() {
        BatchUnfreezeTracksAction action = new BatchUnfreezeTracksAction(
                List.of(), t -> null, SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();
        action.undo();
        // No exception expected
    }

    private Track createTrackWithAudio(String name) {
        Track track = new Track(name, TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip1", 0.0, 1.0, null);
        int frames = (int) Math.round(1.0 * 60.0 / TEMPO * SAMPLE_RATE);
        float[][] data = new float[CHANNELS][frames];
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < frames; i++) {
                data[ch][i] = 0.25f;
            }
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return track;
    }
}
