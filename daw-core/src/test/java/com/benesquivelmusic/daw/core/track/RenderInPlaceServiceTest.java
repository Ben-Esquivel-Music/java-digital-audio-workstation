package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class RenderInPlaceServiceTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;

    @Test
    void shouldRenderAudioTrackAndReplaceClips() {
        Track track = audioTrackWithConstant(0.25f);
        MixerChannel channel = new MixerChannel("ch");

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());

        assertThat(result).isNotNull();
        assertThat(result.destinationTrack()).isSameAs(track);
        assertThat(track.getClips()).containsExactly(result.renderedClip());
        float[][] data = result.renderedClip().getAudioData();
        assertThat(data).isNotNull();
        assertThat(data.length).isEqualTo(CHANNELS);
        // Unity gain / centre pan & equal-power at centre = *1.0; input was 0.25
        assertThat(data[0][100]).isCloseTo(0.25f, offset(0.01f));
        assertThat(data[1][100]).isCloseTo(0.25f, offset(0.01f));
    }

    @Test
    void shouldApplyInsertEffectsWhenIncluded() {
        Track track = audioTrackWithConstant(0.1f);
        MixerChannel channel = new MixerChannel("ch");
        // Insert that doubles the signal.
        channel.getEffectsChain().addProcessor(new GainProcessor(2.0f));

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());

        assertThat(result).isNotNull();
        float[][] data = result.renderedClip().getAudioData();
        assertThat(data[0][100]).isCloseTo(0.2f, offset(0.01f));
    }

    @Test
    void shouldSkipInsertEffectsWhenNotIncluded() {
        Track track = audioTrackWithConstant(0.1f);
        MixerChannel channel = new MixerChannel("ch");
        channel.getEffectsChain().addProcessor(new GainProcessor(2.0f));

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.builder()
                        .includeInserts(false)
                        .build());

        assertThat(result).isNotNull();
        float[][] data = result.renderedClip().getAudioData();
        assertThat(data[0][100]).isCloseTo(0.1f, offset(0.01f));
    }

    @Test
    void shouldApplyChannelVolume() {
        Track track = audioTrackWithConstant(0.4f);
        MixerChannel channel = new MixerChannel("ch");
        channel.setVolume(0.5);

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());

        float[][] data = result.renderedClip().getAudioData();
        assertThat(data[0][100]).isCloseTo(0.2f, offset(0.01f));
    }

    @Test
    void shouldPlaceRenderedClipOnNewTrackWhenRequested() {
        Track track = audioTrackWithConstant(0.3f);
        Track newTrack = new Track("Rendered", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("ch");

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.builder()
                        .createNewTrack(true)
                        .newTrackFactory(src -> newTrack)
                        .build());

        assertThat(result).isNotNull();
        assertThat(result.destinationTrack()).isSameAs(newTrack);
        assertThat(newTrack.getClips()).containsExactly(result.renderedClip());
        // Source track is untouched.
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isNotSameAs(result.renderedClip());
    }

    @Test
    void shouldRenderMidiTrackViaInjectedRenderer() {
        Track track = new Track("Synth", TrackType.MIDI);
        // Add an audio clip placeholder so the track reports a non-zero duration;
        // the MIDI renderer is what actually produces audio.
        AudioClip placeholder = new AudioClip("midi-window", 0.0, 2.0, null);
        track.addClip(placeholder);
        MixerChannel channel = new MixerChannel("ch");

        RenderInPlaceOptions.MidiRenderer midi = (t, sr, bpm, ch, frames) -> {
            float[][] out = new float[ch][frames];
            for (int c = 0; c < ch; c++) {
                for (int i = 0; i < frames; i++) {
                    out[c][i] = 0.5f;
                }
            }
            return out;
        };

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.builder().midiRenderer(midi).build());

        assertThat(result).isNotNull();
        AudioClip clip = result.renderedClip();
        assertThat(clip.getAudioData()).isNotNull();
        assertThat(clip.getAudioData()[0].length).isGreaterThan(0);
        assertThat(clip.getAudioData()[0][0]).isCloseTo(0.5f, offset(0.01f));
        // Track now contains only the rendered clip (placeholder replaced).
        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRejectMidiTrackWithoutRenderer() {
        Track track = new Track("Synth", TrackType.MIDI);
        track.addClip(new AudioClip("window", 0.0, 1.0, null));
        MixerChannel channel = new MixerChannel("ch");

        assertThatThrownBy(() -> RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MidiRenderer");
    }

    @Test
    void shouldReturnNullWhenAudioTrackHasNoContent() {
        Track track = new Track("Empty", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("ch");

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());

        assertThat(result).isNull();
    }

    @Test
    void shouldRejectFrozenTrack() {
        Track track = audioTrackWithConstant(0.1f);
        track.setFrozen(true);
        MixerChannel channel = new MixerChannel("ch");

        assertThatThrownBy(() -> RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void restoreShouldBringBackOriginalClipsInReplaceMode() {
        Track track = audioTrackWithConstant(0.2f);
        List<AudioClip> originals = List.copyOf(track.getClips());
        MixerChannel channel = new MixerChannel("ch");

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.defaults());
        assertThat(track.getClips()).containsExactly(result.renderedClip());

        RenderInPlaceService.restore(track, result);

        assertThat(track.getClips()).containsExactlyElementsOf(originals);
    }

    @Test
    void restoreShouldRemoveClipFromNewTrack() {
        Track track = audioTrackWithConstant(0.2f);
        List<AudioClip> originals = List.copyOf(track.getClips());
        Track newTrack = new Track("new", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("ch");

        RenderInPlaceService.Result result = RenderInPlaceService.render(
                track, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                RenderInPlaceOptions.builder()
                        .createNewTrack(true)
                        .newTrackFactory(src -> newTrack)
                        .build());
        assertThat(newTrack.getClips()).containsExactly(result.renderedClip());

        RenderInPlaceService.restore(track, result);

        assertThat(newTrack.getClips()).isEmpty();
        assertThat(track.getClips()).containsExactlyElementsOf(originals);
    }

    @Test
    void shouldRejectInvalidParameters() {
        Track track = new Track("t", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("c");
        RenderInPlaceOptions opts = RenderInPlaceOptions.defaults();

        assertThatThrownBy(() -> RenderInPlaceService.render(null, channel, SAMPLE_RATE, TEMPO, CHANNELS, opts))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RenderInPlaceService.render(track, null, SAMPLE_RATE, TEMPO, CHANNELS, opts))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RenderInPlaceService.render(track, channel, 0, TEMPO, CHANNELS, opts))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RenderInPlaceService.render(track, channel, SAMPLE_RATE, 0, CHANNELS, opts))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RenderInPlaceService.render(track, channel, SAMPLE_RATE, TEMPO, 0, opts))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private Track audioTrackWithConstant(float value) {
        Track track = new Track("Audio", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip", 0.0, 1.0, null);
        int frames = (int) Math.round(1.0 * 60.0 / TEMPO * SAMPLE_RATE);
        float[][] data = new float[CHANNELS][frames];
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < frames; i++) {
                data[ch][i] = value;
            }
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return track;
    }

    /** Simple test processor that multiplies the signal by a fixed gain. */
    private static final class GainProcessor implements AudioProcessor {
        private final float gain;

        GainProcessor(float gain) {
            this.gain = gain;
        }

        @Override
        public void process(float[][] in, float[][] out, int numFrames) {
            for (int ch = 0; ch < in.length && ch < out.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    out[ch][i] = in[ch][i] * gain;
                }
            }
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }

        @Override
        public void reset() {
            // no-op
        }
    }
}
