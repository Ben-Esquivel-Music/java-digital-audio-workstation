package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchFreezeTracksActionTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;

    @Test
    void shouldHaveCorrectDescription() {
        BatchFreezeTracksAction action = new BatchFreezeTracksAction(
                List.of(), t -> null, SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(action.description()).isEqualTo("Batch Freeze Tracks");
    }

    @Test
    void shouldFreezeAllTracksOnExecute() {
        Track track1 = createTrackWithAudio("Track 1");
        Track track2 = createTrackWithAudio("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");

        BatchFreezeTracksAction action = new BatchFreezeTracksAction(
                List.of(track1, track2),
                t -> t == track1 ? channel1 : channel2,
                SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isTrue();
    }

    @Test
    void shouldUnfreezeAllTracksOnUndo() {
        Track track1 = createTrackWithAudio("Track 1");
        Track track2 = createTrackWithAudio("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");

        BatchFreezeTracksAction action = new BatchFreezeTracksAction(
                List.of(track1, track2),
                t -> t == track1 ? channel1 : channel2,
                SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();
        action.undo();

        assertThat(track1.isFrozen()).isFalse();
        assertThat(track2.isFrozen()).isFalse();
    }

    @Test
    void shouldSkipAlreadyFrozenTracksOnExecute() {
        Track track1 = createTrackWithAudio("Track 1");
        Track track2 = createTrackWithAudio("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");

        // Pre-freeze track1
        TrackFreezeService.freeze(track1, channel1, SAMPLE_RATE, TEMPO, CHANNELS);

        BatchFreezeTracksAction action = new BatchFreezeTracksAction(
                List.of(track1, track2),
                t -> t == track1 ? channel1 : channel2,
                SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isTrue();

        // Undo should only unfreeze track2 (track1 was not frozen by this action)
        action.undo();

        assertThat(track1.isFrozen()).isTrue(); // still frozen (pre-existing)
        assertThat(track2.isFrozen()).isFalse();
    }

    @Test
    void shouldSkipTracksWithNullChannel() {
        Track track1 = createTrackWithAudio("Track 1");
        Track track2 = createTrackWithAudio("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");

        BatchFreezeTracksAction action = new BatchFreezeTracksAction(
                List.of(track1, track2),
                t -> t == track1 ? channel1 : null,
                SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isFalse();
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track1 = createTrackWithAudio("Track 1");
        Track track2 = createTrackWithAudio("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new BatchFreezeTracksAction(
                List.of(track1, track2),
                t -> t == track1 ? channel1 : channel2,
                SAMPLE_RATE, TEMPO, CHANNELS));

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isTrue();

        undoManager.undo();
        assertThat(track1.isFrozen()).isFalse();
        assertThat(track2.isFrozen()).isFalse();

        undoManager.redo();
        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isTrue();
    }

    @Test
    void shouldHandleEmptyTrackList() {
        BatchFreezeTracksAction action = new BatchFreezeTracksAction(
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
