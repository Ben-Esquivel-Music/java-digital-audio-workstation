package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderInPlaceActionTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("t", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("c");

        RenderInPlaceAction action = new RenderInPlaceAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());

        assertThat(action.description()).isEqualTo("Render in Place");
    }

    @Test
    void shouldRenderOnExecuteAndRestoreOnUndo() {
        Track track = audioTrackWithAudio();
        List<AudioClip> originals = List.copyOf(track.getClips());
        MixerChannel channel = new MixerChannel("c");

        RenderInPlaceAction action = new RenderInPlaceAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());

        action.execute();

        assertThat(action.getResult()).isNotNull();
        assertThat(track.getClips()).containsExactly(action.getResult().renderedClip());

        action.undo();

        assertThat(track.getClips()).containsExactlyElementsOf(originals);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = audioTrackWithAudio();
        List<AudioClip> originals = List.copyOf(track.getClips());
        MixerChannel channel = new MixerChannel("c");
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new RenderInPlaceAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults()));
        assertThat(track.getClips()).hasSize(1);

        undoManager.undo();
        assertThat(track.getClips()).containsExactlyElementsOf(originals);

        undoManager.redo();
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isNotSameAs(originals.get(0));
    }

    @Test
    void undoIsNoOpWhenExecuteProducedNoResult() {
        Track track = new Track("empty", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("c");

        RenderInPlaceAction action = new RenderInPlaceAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());
        action.execute();

        assertThat(action.getResult()).isNull();
        action.undo(); // must not throw
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldRejectNulls() {
        Track track = new Track("t", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("c");

        assertThatThrownBy(() -> new RenderInPlaceAction(
                null, channel, SAMPLE_RATE, TEMPO, CHANNELS, RenderInPlaceOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RenderInPlaceAction(
                track, null, SAMPLE_RATE, TEMPO, CHANNELS, RenderInPlaceOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RenderInPlaceAction(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS, null))
                .isInstanceOf(NullPointerException.class);
    }

    private Track audioTrackWithAudio() {
        Track track = new Track("Audio", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip", 0.0, 1.0, null);
        int frames = (int) Math.round(1.0 * 60.0 / TEMPO * SAMPLE_RATE);
        float[][] data = new float[CHANNELS][frames];
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < frames; i++) {
                data[ch][i] = 0.3f;
            }
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return track;
    }
}
