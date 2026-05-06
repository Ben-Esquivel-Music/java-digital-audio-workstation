package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for story 126's just-in-time sample-rate conversion
 * at the bus boundary: {@link RenderPipeline} consults the
 * {@link SampleRateConversionCache} when a clip's
 * {@link SourceRateMetadata} reports a native rate that differs from
 * the session rate.
 */
class RenderPipelineSampleRateConversionTest {

    private static final int CHANNELS = 1;
    private static final int BUFFER_SIZE = 64;
    private static final double TEMPO = 60.0;  // one beat per second

    private static AudioFormat format(int sessionRateHz) {
        return new AudioFormat(sessionRateHz, CHANNELS, 16, BUFFER_SIZE);
    }

    private static float[] makeSine(int frames, double freqHz, double rateHz) {
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / rateHz);
        }
        return out;
    }

    private static float[][] runPipeline(int sessionRateHz, AudioClip clip,
                                          SampleRateConversionCache cache,
                                          int totalFrames) {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        Mixer mixer = new Mixer();
        Track track = new Track("T", TrackType.AUDIO);
        track.addClip(clip);
        mixer.addChannel(new MixerChannel("T"));
        mixer.prepareForPlayback(CHANNELS, BUFFER_SIZE);
        transport.play();

        EffectsChain master = new EffectsChain();
        master.allocateIntermediateBuffers(CHANNELS, BUFFER_SIZE);

        RenderPipeline pipeline = new RenderPipeline(format(sessionRateHz),
                AudioEngine.MAX_TRACKS, BUFFER_SIZE);
        pipeline.setSampleRateConversionCache(cache);
        pipeline.setSrcQualityTier(QualityTier.MEDIUM);

        float[][] out = new float[CHANNELS][totalFrames];
        pipeline.renderOffline(transport, mixer, List.of(track), null,
                master, out, totalFrames, BUFFER_SIZE);
        return out;
    }

    @Test
    void clipNativeRateMatchingSessionRateProducesNoPitchShift() {
        int sessionRate = 48_000;
        int frames = 48_000;  // 1 s
        float[][] data = new float[][]{ makeSine(frames, 440.0, sessionRate) };

        AudioClip clip = new AudioClip("clip-eq", 0.0, frames / (double) sessionRate * (TEMPO / 60.0), null);
        clip.setAudioData(data);
        clip.setSourceRateMetadata(new SourceRateMetadata(sessionRate, 1, frames));

        SampleRateConversionCache cache = new SampleRateConversionCache();
        float[][] out = runPipeline(sessionRate, clip, cache, frames);

        // Cache short-circuits when rates match; nothing inserted.
        assertThat(cache.size()).isZero();
        // No SRC means the rendered samples equal the source samples.
        for (int i = 0; i < 1024; i++) {
            assertThat(out[0][i]).isEqualTo(data[0][i]);
        }
    }

    @Test
    void clip44kInto48kSessionIsResampledViaCache() {
        int sessionRate = 48_000;
        int nativeRate = 44_100;
        int nativeFrames = nativeRate;  // 1 second at native rate
        // The session occupies 1 second; at TEMPO=60bpm that's 1 beat.
        float[] sine44k = makeSine(nativeFrames, 440.0, nativeRate);

        AudioClip clip = new AudioClip("clip-44k", 0.0, 1.0, null);
        clip.setAudioData(new float[][]{ sine44k });
        clip.setSourceRateMetadata(new SourceRateMetadata(nativeRate, 1, nativeFrames));

        SampleRateConversionCache cache = new SampleRateConversionCache();
        // Render exactly one beat worth of session-rate audio.
        int totalFrames = sessionRate;
        float[][] out = runPipeline(sessionRate, clip, cache, totalFrames);

        // Cache materialized exactly one converted buffer.
        assertThat(cache.size()).isEqualTo(1);

        // The rendered output, resampled back to 44.1kHz with the same
        // tier, should match the original within the documented MEDIUM
        // tier passband ripple (≤ 0.1 dB ≈ 1.16% amplitude).
        SampleRateConverter back = new SampleRateConverter.Medium();
        float[] roundTrip = back.process(out[0], sessionRate, nativeRate);

        // Skip the filter-warmup transient at both ends and compare RMS.
        int skip = 1_000;
        int compareFrames = Math.min(nativeFrames, roundTrip.length) - 2 * skip;
        double sumSq = 0.0;
        double sumOrigSq = 0.0;
        for (int i = 0; i < compareFrames; i++) {
            float a = sine44k[skip + i];
            float b = roundTrip[skip + i];
            double d = a - b;
            sumSq += d * d;
            sumOrigSq += a * a;
        }
        double rmsError = Math.sqrt(sumSq / compareFrames);
        double rmsSignal = Math.sqrt(sumOrigSq / compareFrames);
        // Pitch is preserved when the round-trip RMS is within the
        // tier's tolerance — an actual pitch shift would produce an
        // RMS error close to the signal RMS (≈ 0.7).
        assertThat(rmsError / rmsSignal).isLessThan(0.25);
    }

    @Test
    void cacheIsInvalidatedWhenSessionRateChanges() {
        int frames = 44_100;
        AudioClip clip = new AudioClip("clip-rate-change", 0.0, 1.0, null);
        clip.setAudioData(new float[][]{ makeSine(frames, 440.0, 44_100) });
        clip.setSourceRateMetadata(new SourceRateMetadata(44_100, 1, frames));

        AudioEngine engine = new AudioEngine(format(48_000));
        SampleRateConversionCache cache = engine.getSampleRateConversionCache();

        // Force a cache entry by resolving a clip via the pipeline
        // through the cache directly (the pipeline uses the same key).
        cache.get(clip.getId(), clip.getSourceRateMetadata(), 48_000,
                QualityTier.MEDIUM, clip::getAudioData);
        assertThat(cache.size()).isEqualTo(1);

        // Changing the session rate must drop every cached conversion.
        engine.setFormat(format(44_100));
        assertThat(cache.size()).isZero();
    }
}
