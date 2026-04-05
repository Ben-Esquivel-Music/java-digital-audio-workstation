package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TrackFreezeServiceTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;

    @Test
    void shouldFreezeTrack() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");

        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(track.isFrozen()).isTrue();
        assertThat(track.getFrozenAudioData()).isNotNull();
    }

    @Test
    void shouldUnfreezeTrack() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");

        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);
        TrackFreezeService.unfreeze(track);

        assertThat(track.isFrozen()).isFalse();
        assertThat(track.getFrozenAudioData()).isNull();
    }

    @Test
    void shouldRejectFreezeOfAlreadyFrozenTrack() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");
        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        assertThatThrownBy(() ->
                TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already frozen");
    }

    @Test
    void shouldRejectUnfreezeOfNonFrozenTrack() {
        Track track = new Track("Test", TrackType.AUDIO);

        assertThatThrownBy(() -> TrackFreezeService.unfreeze(track))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not frozen");
    }

    @Test
    void shouldRejectNullTrackOnFreeze() {
        MixerChannel channel = new MixerChannel("Test");

        assertThatThrownBy(() ->
                TrackFreezeService.freeze(null, channel, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullChannelOnFreeze() {
        Track track = new Track("Test", TrackType.AUDIO);

        assertThatThrownBy(() ->
                TrackFreezeService.freeze(track, null, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrackOnUnfreeze() {
        assertThatThrownBy(() -> TrackFreezeService.unfreeze(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        Track track = new Track("Test", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("Test");

        assertThatThrownBy(() ->
                TrackFreezeService.freeze(track, channel, 0, TEMPO, CHANNELS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNonPositiveTempo() {
        Track track = new Track("Test", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("Test");

        assertThatThrownBy(() ->
                TrackFreezeService.freeze(track, channel, SAMPLE_RATE, 0, CHANNELS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo");
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        Track track = new Track("Test", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("Test");

        assertThatThrownBy(() ->
                TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels");
    }

    @Test
    void shouldFreezeTrackWithNoClipsToEmptyBuffer() {
        Track track = new Track("Empty", TrackType.AUDIO);
        MixerChannel channel = new MixerChannel("Test");

        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(track.isFrozen()).isTrue();
        assertThat(track.getFrozenAudioData()).isNotNull();
        assertThat(track.getFrozenAudioData().length).isEqualTo(CHANNELS);
        assertThat(track.getFrozenAudioData()[0].length).isZero();
    }

    @Test
    void shouldProcessThroughEffectsChainWhenFreezing() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");

        // Add a simple gain processor that doubles the signal
        channel.addInsert(new com.benesquivelmusic.daw.core.mixer.InsertSlot(
                "Gain", new DoubleGainProcessor()));

        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(track.isFrozen()).isTrue();
        float[][] frozenData = track.getFrozenAudioData();
        assertThat(frozenData).isNotNull();
        // The original audio was 0.25f, after doubling it should be 0.5f
        if (frozenData[0].length > 0) {
            assertThat(frozenData[0][0]).isCloseTo(0.5f, offset(0.01f));
        }
    }

    @Test
    void shouldPassthroughWhenEffectsChainIsEmpty() {
        Track track = createTrackWithAudio();
        MixerChannel channel = new MixerChannel("Test");

        TrackFreezeService.freeze(track, channel, SAMPLE_RATE, TEMPO, CHANNELS);

        float[][] frozenData = track.getFrozenAudioData();
        assertThat(frozenData).isNotNull();
        if (frozenData[0].length > 0) {
            assertThat(frozenData[0][0]).isCloseTo(0.25f, offset(0.01f));
        }
    }

    // ── Batch freeze tests ──────────────────────────────────────────────────

    @Test
    void shouldBatchFreezeMultipleTracks() {
        Track track1 = createTrackWithAudio();
        Track track2 = createTrackWithAudio();
        track2.setName("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");

        List<Track> tracks = List.of(track1, track2);
        TrackFreezeService.freezeAll(tracks,
                t -> t == track1 ? channel1 : channel2,
                SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isTrue();
    }

    @Test
    void shouldSkipAlreadyFrozenTracksInBatchFreeze() {
        Track track1 = createTrackWithAudio();
        Track track2 = createTrackWithAudio();
        track2.setName("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");

        TrackFreezeService.freeze(track1, channel1, SAMPLE_RATE, TEMPO, CHANNELS);

        List<Track> tracks = List.of(track1, track2);
        TrackFreezeService.freezeAll(tracks,
                t -> t == track1 ? channel1 : channel2,
                SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isTrue();
    }

    @Test
    void shouldSkipTracksWithNullChannelInBatchFreeze() {
        Track track1 = createTrackWithAudio();
        Track track2 = createTrackWithAudio();
        track2.setName("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");

        List<Track> tracks = List.of(track1, track2);
        TrackFreezeService.freezeAll(tracks,
                t -> t == track1 ? channel1 : null,
                SAMPLE_RATE, TEMPO, CHANNELS);

        assertThat(track1.isFrozen()).isTrue();
        assertThat(track2.isFrozen()).isFalse();
    }

    @Test
    void shouldBatchUnfreezeMultipleTracks() {
        Track track1 = createTrackWithAudio();
        Track track2 = createTrackWithAudio();
        track2.setName("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");
        MixerChannel channel2 = new MixerChannel("Ch2");

        TrackFreezeService.freeze(track1, channel1, SAMPLE_RATE, TEMPO, CHANNELS);
        TrackFreezeService.freeze(track2, channel2, SAMPLE_RATE, TEMPO, CHANNELS);

        List<Track> tracks = List.of(track1, track2);
        TrackFreezeService.unfreezeAll(tracks);

        assertThat(track1.isFrozen()).isFalse();
        assertThat(track2.isFrozen()).isFalse();
    }

    @Test
    void shouldSkipNonFrozenTracksInBatchUnfreeze() {
        Track track1 = createTrackWithAudio();
        Track track2 = createTrackWithAudio();
        track2.setName("Track 2");
        MixerChannel channel1 = new MixerChannel("Ch1");

        TrackFreezeService.freeze(track1, channel1, SAMPLE_RATE, TEMPO, CHANNELS);

        List<Track> tracks = List.of(track1, track2);
        TrackFreezeService.unfreezeAll(tracks);

        assertThat(track1.isFrozen()).isFalse();
        assertThat(track2.isFrozen()).isFalse();
    }

    @Test
    void shouldRejectNullTracksInBatchFreeze() {
        assertThatThrownBy(() ->
                TrackFreezeService.freezeAll(null, t -> null, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullChannelLookupInBatchFreeze() {
        assertThatThrownBy(() ->
                TrackFreezeService.freezeAll(List.of(), null, SAMPLE_RATE, TEMPO, CHANNELS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTracksInBatchUnfreeze() {
        assertThatThrownBy(() -> TrackFreezeService.unfreezeAll(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Track createTrackWithAudio() {
        Track track = new Track("Test Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip1", 0.0, 1.0, null);
        int frames = beatsToFrames(1.0);
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

    /**
     * Converts beats to sample frames at the test sample rate and tempo.
     */
    private static int beatsToFrames(double beats) {
        double seconds = beats * 60.0 / TEMPO;
        return (int) Math.round(seconds * SAMPLE_RATE);
    }

    /**
     * Simple test processor that doubles the input signal.
     */
    private static final class DoubleGainProcessor implements AudioProcessor {

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            int channelCount = Math.min(inputBuffer.length, outputBuffer.length);
            for (int ch = 0; ch < channelCount; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] = inputBuffer[ch][i] * 2.0f;
                }
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
