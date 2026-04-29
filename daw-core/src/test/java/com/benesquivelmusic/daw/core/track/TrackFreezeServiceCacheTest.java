package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.cache.RenderCacheConfig;
import com.benesquivelmusic.daw.core.audio.cache.RenderKey;
import com.benesquivelmusic.daw.core.audio.cache.RenderedTrackCache;
import com.benesquivelmusic.daw.core.audio.cache.TrackDspHasher;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrackFreezeServiceCacheTest {

    private static final int SAMPLE_RATE = 44_100;
    private static final double TEMPO = 120.0;
    private static final int CHANNELS = 2;
    private static final String PROJECT = "test-project";

    @Test
    void cacheHitOnSecondFreezeSkipsRenderingAndReturnsStoredAudio(@TempDir Path tmp)
            throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        RenderKey key = new RenderKey(
                new TrackDspHasher().addParameter("gain", 0.5).digestHex(),
                SAMPLE_RATE, 32);

        // First freeze: cache miss → render and store.
        Track first = createTrackWithAudio();
        MixerChannel channel = channelWithGainTwo();
        TrackFreezeService.freeze(first, channel, SAMPLE_RATE, TEMPO, CHANNELS,
                cache, PROJECT, key);
        assertThat(first.isFrozen()).isTrue();
        assertThat(cache.contains(PROJECT, key)).isTrue();
        float[][] firstAudio = first.getFrozenAudioData();

        // Second freeze on a fresh track with the same key: cache hit
        // → bypass rendering, restore stored audio. To prove the
        // chain was *not* re-applied we hand the second freeze a
        // different mixer channel (silencing gain). If the cache
        // were ignored the resulting audio would be zeros; with a
        // hit it must equal the original render.
        Track second = createTrackWithAudio();
        MixerChannel silencing = channelWithGain(0.0f);
        TrackFreezeService.freeze(second, silencing, SAMPLE_RATE, TEMPO, CHANNELS,
                cache, PROJECT, key);

        assertThat(second.isFrozen()).isTrue();
        assertThat(second.getFrozenAudioData()).isDeepEqualTo(firstAudio);
        assertThat(cache.stats().sessionHits()).isEqualTo(1);
        assertThat(cache.stats().sessionMisses()).isEqualTo(1);
    }

    @Test
    void differentRenderKeyProducesCacheMiss(@TempDir Path tmp) throws IOException {
        RenderedTrackCache cache = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());

        RenderKey k1 = new RenderKey(
                new TrackDspHasher().addParameter("gain", 0.5).digestHex(),
                SAMPLE_RATE, 32);
        RenderKey k2 = new RenderKey(
                new TrackDspHasher().addParameter("gain", 0.7).digestHex(),
                SAMPLE_RATE, 32);

        Track t1 = createTrackWithAudio();
        TrackFreezeService.freeze(t1, channelWithGainTwo(), SAMPLE_RATE, TEMPO, CHANNELS,
                cache, PROJECT, k1);

        Track t2 = createTrackWithAudio();
        TrackFreezeService.freeze(t2, channelWithGainTwo(), SAMPLE_RATE, TEMPO, CHANNELS,
                cache, PROJECT, k2);

        assertThat(cache.contains(PROJECT, k1)).isTrue();
        assertThat(cache.contains(PROJECT, k2)).isTrue();
        // Two misses (each first-time hash), zero hits.
        assertThat(cache.stats().sessionHits()).isEqualTo(0);
        assertThat(cache.stats().sessionMisses()).isEqualTo(2);
    }

    @Test
    void cacheSurvivesAcrossNewServiceInvocations(@TempDir Path tmp) throws IOException {
        RenderKey key = new RenderKey(
                new TrackDspHasher().addParameter("gain", 0.5).digestHex(),
                SAMPLE_RATE, 32);

        // Session 1 — populate cache via freeze.
        RenderedTrackCache session1 = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        Track t1 = createTrackWithAudio();
        TrackFreezeService.freeze(t1, channelWithGainTwo(), SAMPLE_RATE, TEMPO, CHANNELS,
                session1, PROJECT, key);
        float[][] originalAudio = t1.getFrozenAudioData();

        // Session 2 — fresh cache instance over the same root.
        RenderedTrackCache session2 = new RenderedTrackCache(tmp, RenderCacheConfig.defaults());
        Track t2 = createTrackWithAudio();
        TrackFreezeService.freeze(t2, channelWithGain(0.0f), SAMPLE_RATE, TEMPO, CHANNELS,
                session2, PROJECT, key);

        assertThat(t2.getFrozenAudioData()).isDeepEqualTo(originalAudio);
        assertThat(session2.stats().sessionHits()).isEqualTo(1);
    }

    private static final RenderKey KEY_AT_RATE = new RenderKey(
            new TrackDspHasher().addParameter("gain", 0.5).digestHex(),
            SAMPLE_RATE, 32);

    private static Track createTrackWithAudio() {
        Track track = new Track("Test Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip1", 0.0, 1.0, null);
        int frames = (int) Math.round(60.0 / TEMPO * SAMPLE_RATE);
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

    private static MixerChannel channelWithGainTwo() {
        return channelWithGain(2.0f);
    }

    private static MixerChannel channelWithGain(float gain) {
        MixerChannel channel = new MixerChannel("Test");
        channel.addInsert(new InsertSlot("Gain", new GainProcessor(gain)));
        return channel;
    }

    /** Trivial test processor — multiplies every sample by a constant. */
    private static final class GainProcessor implements AudioProcessor {
        private final float gain;
        GainProcessor(float gain) { this.gain = gain; }

        @Override
        public void process(float[][] in, float[][] out, int numFrames) {
            int channelCount = Math.min(in.length, out.length);
            for (int c = 0; c < channelCount; c++) {
                for (int f = 0; f < numFrames; f++) {
                    out[c][f] = in[c][f] * gain;
                }
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return CHANNELS; }
        @Override public int getOutputChannelCount() { return CHANNELS; }
    }
}
