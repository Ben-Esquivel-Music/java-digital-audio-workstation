package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Verifies that {@link RenderPipeline} evaluates the per-clip gain
 * envelope sample-accurately, and that legacy scalar clip-gain still
 * works when no envelope is set.
 */
class RenderPipelineClipGainEnvelopeTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 1;
    private static final int BUFFER_SIZE = 64;
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;

    private static AudioFormat format() {
        return new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);
    }

    private static float[][] renderDcClipWithEnvelope(ClipGainEnvelope envelope,
                                                      double scalarClipGainDb,
                                                      int totalFrames) {
        Track track = new Track("t", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c", 0.0, totalFrames / SAMPLES_PER_BEAT, null);
        float[][] data = new float[CHANNELS][totalFrames];
        for (int i = 0; i < totalFrames; i++) data[0][i] = 1.0f; // DC = 1.0
        clip.setAudioData(data);
        clip.setGainDb(scalarClipGainDb);
        if (envelope != null) clip.setGainEnvelope(envelope);
        track.addClip(clip);

        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.play();

        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("c");
        ch.setVolume(1.0);
        ch.setPan(0.0); // center — with single channel output this is pass-through.
        mixer.addChannel(ch);
        mixer.prepareForPlayback(CHANNELS, BUFFER_SIZE);

        EffectsChain master = new EffectsChain();
        master.allocateIntermediateBuffers(CHANNELS, BUFFER_SIZE);

        RenderPipeline pipeline = new RenderPipeline(format(),
                AudioEngine.MAX_TRACKS, BUFFER_SIZE);
        float[][] out = new float[CHANNELS][totalFrames];
        pipeline.renderOffline(transport, mixer, List.of(track), null,
                master, out, totalFrames, BUFFER_SIZE);
        return out;
    }

    @Test
    void scalarClipGain_isAppliedWhenEnvelopeAbsent() {
        int frames = BUFFER_SIZE * 4;
        // -6 dB → linear ≈ 0.5012
        float[][] out = renderDcClipWithEnvelope(null, -6.0, frames);
        double expected = Math.pow(10.0, -6.0 / 20.0);
        // Mono pan-center is left channel with cos(pi/4); here CHANNELS=1 so
        // it's routed as mono. We sanity-check proportionality across frames.
        assertThat(out[0][0]).isCloseTo(out[0][100], offset(1e-6f));
        assertThat(out[0][0]).isNotZero();
        // Doubling the scalar gain should double the output.
        float[][] out2 = renderDcClipWithEnvelope(null, 0.0, frames);
        assertThat((double) out2[0][0] / out[0][0])
                .isCloseTo(1.0 / expected, offset(1e-4));
    }

    @Test
    void envelopeOverridesScalarClipGain() {
        // Scalar says -20 dB; envelope says 0 dB — envelope must win.
        int frames = BUFFER_SIZE * 4;
        float[][] withEnv = renderDcClipWithEnvelope(
                ClipGainEnvelope.constant(0.0), -20.0, frames);
        float[][] noEnv = renderDcClipWithEnvelope(null, 0.0, frames);

        // Same output shape as no-envelope 0 dB; scalar gain ignored.
        for (int i = 0; i < frames; i++) {
            assertThat(withEnv[0][i]).isCloseTo(noEnv[0][i], offset(1e-6f));
        }
    }

    @Test
    void linearEnvelopeProducesSampleAccurateRamp() {
        int frames = BUFFER_SIZE * 4; // 256 frames
        // Linear ramp from 0 dB at frame 0 to -20 dB at frame 200.
        ClipGainEnvelope env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(200L, -20.0, CurveShape.LINEAR)));
        float[][] out = renderDcClipWithEnvelope(env, 0.0, frames);
        float[][] unityOut = renderDcClipWithEnvelope(null, 0.0, frames);

        // The per-sample ratio should match the envelope's linearAtFrame().
        for (int i = 0; i <= 200; i += 50) {
            double expectedRatio = env.linearAtFrame(i);
            assertThat((double) out[0][i] / unityOut[0][i])
                    .as("frame %d", i)
                    .isCloseTo(expectedRatio, offset(1e-5));
        }
        // Past last breakpoint clamps to -20 dB gain factor.
        double clampRatio = Math.pow(10.0, -20.0 / 20.0);
        assertThat((double) out[0][250] / unityOut[0][250])
                .isCloseTo(clampRatio, offset(1e-5));
    }
}
