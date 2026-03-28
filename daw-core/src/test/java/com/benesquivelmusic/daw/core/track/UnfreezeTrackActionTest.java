package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnfreezeTrackActionTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Test", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("Test");

        UnfreezeTrackAction action = new UnfreezeTrackAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(action.description()).isEqualTo("Unfreeze Track");
    }

    @Test
    void shouldUnfreezeTrackOnExecute() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");
        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        UnfreezeTrackAction action = new UnfreezeTrackAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();

        assertThat(track.isFrozen()).isFalse();
        assertThat(track.getFrozenAudioData()).isNull();
    }

    @Test
    void shouldRefreezeTrackOnUndo() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");
        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        UnfreezeTrackAction action = new UnfreezeTrackAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS);
        action.execute();
        action.undo();

        assertThat(track.isFrozen()).isTrue();
        assertThat(track.getFrozenAudioData()).isNotNull();
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");
        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new UnfreezeTrackAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS));
        assertThat(track.isFrozen()).isFalse();

        undoManager.undo();
        assertThat(track.isFrozen()).isTrue();

        undoManager.redo();
        assertThat(track.isFrozen()).isFalse();
    }

    @Test
    void shouldRejectNullTrack() {
        MixerChannel channel = new MixerChannel("Test");

        assertThatThrownBy(() ->
                new UnfreezeTrackAction(null, channel, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullChannel() {
        Track track = new Track("Test", TrackType.AUDIO);

        assertThatThrownBy(() ->
                new UnfreezeTrackAction(track, null, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(NullPointerException.class);
    }

    private Track createTrackWithAudio() {
        Track track = new Track("Test Track", TrackType.AUDIO);
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
