package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests asserting that live and offline rendering produce
 * bit-identical output for the same project state.
 *
 * <p>Live rendering goes through
 * {@link AudioEngine#processBlock(float[][], float[][], int)}, which
 * delegates to {@link RenderPipeline#renderBlock}. Offline rendering uses
 * {@link RenderPipeline#renderOffline} directly on the same project.
 * Because both paths share the identical render implementation, the
 * per-sample float output must match exactly.</p>
 */
class RenderPipelineParityTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 64;
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;

    private static AudioFormat format() {
        return new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);
    }

    /**
     * Builds a small project with two tracks, each containing one clip.
     * Track 1 has a sine-ish waveform panned slightly left with reduced
     * volume; track 2 has a sawtooth-ish waveform panned right.
     */
    private static void populateProject(Transport transport, Mixer mixer,
                                        Track[] tracksOut,
                                        int totalFrames) {
        // Track 1
        Track t1 = new Track("Lead", TrackType.AUDIO);
        AudioClip c1 = new AudioClip("Lead-Clip", 0.0, totalFrames / SAMPLES_PER_BEAT, null);
        float[][] d1 = new float[CHANNELS][totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE) * 0.8f;
            d1[0][i] = v;
            d1[1][i] = v * 0.9f;
        }
        c1.setAudioData(d1);
        t1.addClip(c1);

        MixerChannel mc1 = new MixerChannel("Lead");
        mc1.setVolume(0.7);
        mc1.setPan(-0.3);
        mixer.addChannel(mc1);

        // Track 2 — starts at beat 0.05 so its render offset differs from track 1
        double t2StartBeat = 0.05;
        double t2StartFrames = t2StartBeat * SAMPLES_PER_BEAT;
        Track t2 = new Track("Bass", TrackType.AUDIO);
        AudioClip c2 = new AudioClip("Bass-Clip", t2StartBeat,
                (totalFrames / SAMPLES_PER_BEAT) - t2StartBeat, null);
        int c2Frames = (int) (totalFrames - t2StartFrames);
        float[][] d2 = new float[CHANNELS][c2Frames];
        for (int i = 0; i < c2Frames; i++) {
            float v = ((i % 256) / 128.0f - 1.0f) * 0.6f; // simple saw
            d2[0][i] = v * 0.5f;
            d2[1][i] = v;
        }
        c2.setAudioData(d2);
        t2.addClip(c2);

        MixerChannel mc2 = new MixerChannel("Bass");
        mc2.setVolume(0.85);
        mc2.setPan(0.4);
        mixer.addChannel(mc2);

        tracksOut[0] = t1;
        tracksOut[1] = t2;
    }

    @Test
    void livePlaybackAndOfflineRenderProduceBitIdenticalOutput() {
        int totalFrames = BUFFER_SIZE * 100; // 6400 frames ≈ 0.29 beats

        // ── Live render via AudioEngine.processBlock (which delegates to RenderPipeline) ──
        AudioEngine engine = new AudioEngine(format());
        Transport liveTransport = new Transport();
        liveTransport.setTempo(TEMPO);
        Mixer liveMixer = new Mixer();
        Track[] liveTracks = new Track[2];
        populateProject(liveTransport, liveMixer, liveTracks, totalFrames);
        liveTransport.play();
        engine.setTransport(liveTransport);
        engine.setMixer(liveMixer);
        engine.setTracks(List.of(liveTracks[0], liveTracks[1]));
        engine.start();

        float[][] liveOut = new float[CHANNELS][totalFrames];
        float[][] blockIn = new float[CHANNELS][BUFFER_SIZE];
        float[][] blockOut = new float[CHANNELS][BUFFER_SIZE];
        int rendered = 0;
        while (rendered < totalFrames) {
            int n = Math.min(BUFFER_SIZE, totalFrames - rendered);
            for (int ch = 0; ch < CHANNELS; ch++) {
                java.util.Arrays.fill(blockOut[ch], 0.0f);
            }
            engine.processBlock(blockIn, blockOut, n);
            for (int ch = 0; ch < CHANNELS; ch++) {
                System.arraycopy(blockOut[ch], 0, liveOut[ch], rendered, n);
            }
            rendered += n;
        }

        // ── Offline render via RenderPipeline.renderOffline ──
        Transport offlineTransport = new Transport();
        offlineTransport.setTempo(TEMPO);
        Mixer offlineMixer = new Mixer();
        Track[] offlineTracks = new Track[2];
        populateProject(offlineTransport, offlineMixer, offlineTracks, totalFrames);
        offlineTransport.play();

        offlineMixer.prepareForPlayback(CHANNELS, BUFFER_SIZE);
        EffectsChain offlineMaster = new EffectsChain();
        offlineMaster.allocateIntermediateBuffers(CHANNELS, BUFFER_SIZE);

        RenderPipeline offlinePipeline = new RenderPipeline(format(),
                AudioEngine.MAX_TRACKS, BUFFER_SIZE);
        float[][] offlineOut = new float[CHANNELS][totalFrames];
        offlinePipeline.renderOffline(offlineTransport, offlineMixer,
                List.of(offlineTracks[0], offlineTracks[1]), null,
                offlineMaster, offlineOut, totalFrames, BUFFER_SIZE);

        // ── Bit-identical parity ──
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < totalFrames; i++) {
                assertThat(offlineOut[ch][i])
                        .as("Channel %d sample %d", ch, i)
                        .isEqualTo(liveOut[ch][i]);
            }
        }

        // Both paths must have advanced the transport by the same delta.
        assertThat(offlineTransport.getPositionInBeats())
                .isEqualTo(liveTransport.getPositionInBeats());
    }

    @Test
    void offlineRenderRejectsInvalidArguments() {
        RenderPipeline pipeline = new RenderPipeline(format(),
                AudioEngine.MAX_TRACKS, BUFFER_SIZE);
        Transport t = new Transport();
        Mixer m = new Mixer();
        EffectsChain mc = new EffectsChain();
        mc.allocateIntermediateBuffers(CHANNELS, BUFFER_SIZE);
        float[][] out = new float[CHANNELS][BUFFER_SIZE];

        assertThat(catchThrowable(() -> pipeline.renderOffline(
                t, m, List.of(), null, mc, out, 0, BUFFER_SIZE)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(catchThrowable(() -> pipeline.renderOffline(
                t, m, List.of(), null, mc, out, BUFFER_SIZE, BUFFER_SIZE + 1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(catchThrowable(() -> pipeline.renderOffline(
                null, m, List.of(), null, mc, out, BUFFER_SIZE, BUFFER_SIZE)))
                .isInstanceOf(NullPointerException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }
}
