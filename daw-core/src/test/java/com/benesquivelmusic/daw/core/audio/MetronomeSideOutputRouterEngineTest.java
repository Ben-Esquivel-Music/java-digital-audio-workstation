package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 136 — verifies that the audio engine actually invokes the
 * {@link MetronomeSideOutputRouter} on every buffer cycle so the
 * drummer-cue workflow has audible runtime effect (closing the gap that
 * used to live behind the {@code TODO(story 136)} in
 * {@code MainController}).
 *
 * <p>Each test wires a {@link MockAudioBackend} into the engine,
 * configures the metronome, runs a deterministic number of blocks, and
 * inspects either the engine's master output, the side-output channel,
 * or the cue-bus hardware-output channel — all observed through the
 * mock backend.</p>
 */
class MetronomeSideOutputRouterEngineTest {

    /** 120 BPM at 44.1 kHz → exactly 22 050 samples per beat. */
    private static final double SAMPLE_RATE = 44_100.0;
    private static final int BUFFER_FRAMES = 256;
    private static final int SAMPLES_PER_BEAT = 22_050;
    /** Click length matches Metronome.CLICK_DURATION_SECONDS = 0.02. */
    private static final int CLICK_SAMPLES = (int) (0.02 * SAMPLE_RATE);

    @Test
    void engineShouldRouteClickToSideOutputAndKeepMainMixSilentWhenMainDisabled() {
        Fixture fx = new Fixture();
        // Side-output to channel 5, main-mix gated OFF — story 136's drummer-
        // cue workflow promise: the click must NOT bleed into the master mix.
        fx.metronome.setClickOutput(new ClickOutput(5, 1.0, false, true));

        // Run one full second of transport (≈ 2 beats at 120 BPM).
        fx.runForFrames(SAMPLES_PER_BEAT * 2);

        // Master mix should be perfectly silent — no click summed in.
        assertThat(fx.maxAbs(fx.allOutput)).isEqualTo(0.0f);

        // The mock backend should have captured exactly 2 clicks worth of
        // samples on hardware channel 5 (one per beat).
        float[] sideOutput = fx.backend.recordedChannelOutput(5);
        assertThat(sideOutput).hasSize(2 * CLICK_SAMPLES);
        // Channel 5 was the only side-output destination.
        assertThat(fx.backend.recordedChannelOutput(0)).isEmpty();
        assertThat(fx.backend.recordedChannelOutput(4)).isEmpty();
        assertThat(fx.backend.recordedChannelOutput(6)).isEmpty();
    }

    @Test
    void engineShouldRouteClickToCueBusHardwareOutputAtConfiguredLevel() {
        Fixture fx = new Fixture();
        // Drummer cue bus on stereo pair index 3 → physical channels 6/7.
        CueBus drummer = fx.cueBusManager.createCueBus("Drummer", 3);
        fx.router.setCueBusLevel(drummer.id(), 0.5);
        // Side-output disabled; main-mix can stay enabled — we are only
        // asserting the cue-bus path here.
        fx.metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));

        fx.runForFrames(SAMPLES_PER_BEAT * 2);

        // The cue bus's hardware-output stereo pair (channels 6 and 7)
        // must have received exactly two click buffers, each attenuated
        // by the configured 0.5 level.
        float[] left = fx.backend.recordedChannelOutput(6);
        float[] right = fx.backend.recordedChannelOutput(7);
        assertThat(left).hasSize(2 * CLICK_SAMPLES);
        assertThat(right).hasSize(2 * CLICK_SAMPLES);

        float[][] referenceClick = fx.metronome.generateClick(true);
        for (int i = 0; i < CLICK_SAMPLES; i++) {
            float expected = (referenceClick[0][i] + referenceClick[1][i]) * 0.5f * 0.5f;
            assertThat(left[i]).isCloseTo(expected, within(1e-7f));
            assertThat(right[i]).isCloseTo(expected, within(1e-7f));
        }
    }

    @Test
    void mainMixSideOutputAndCueBusContributionShouldBeSampleAccuratelyAligned() {
        Fixture fx = new Fixture();
        CueBus drummer = fx.cueBusManager.createCueBus("Drummer", 2); // ch 4/5
        fx.router.setCueBusLevel(drummer.id(), 1.0);
        // Main-mix on, side output on channel 7, cue bus on 4/5 — all three
        // destinations active so we can compare them sample-by-sample.
        fx.metronome.setClickOutput(new ClickOutput(7, 1.0, true, true));

        // Drive enough blocks to fully render the 20 ms (882-sample) click,
        // even though BUFFER_FRAMES is the typical low-latency 256: the
        // engine carries the click tail across blocks (story 136) so the
        // entire click ends up in the master mix.
        fx.runForFrames(1024);

        float[] side = fx.backend.recordedChannelOutput(7);
        float[] cueLeft = fx.backend.recordedChannelOutput(4);
        float[] cueRight = fx.backend.recordedChannelOutput(5);
        assertThat(side).hasSize(CLICK_SAMPLES);
        assertThat(cueLeft).hasSize(CLICK_SAMPLES);
        assertThat(cueRight).hasSize(CLICK_SAMPLES);

        // All three destinations share the same source buffer, so per-sample
        // values must line up bit-exactly: the main-mix sample (averaged to
        // mono) equals the side-output sample equals each cue-bus channel.
        for (int i = 0; i < CLICK_SAMPLES; i++) {
            float mainMono = (fx.allOutput[0][i] + fx.allOutput[1][i]) * 0.5f;
            assertThat(side[i]).isEqualTo(mainMono);
            assertThat(cueLeft[i]).isEqualTo(mainMono);
            assertThat(cueRight[i]).isEqualTo(mainMono);
        }
    }

    @Test
    void disabledMetronomeShouldSilenceEveryDestination() {
        Fixture fx = new Fixture();
        CueBus drummer = fx.cueBusManager.createCueBus("Drummer", 1);
        fx.router.setCueBusLevel(drummer.id(), 1.0);
        fx.metronome.setClickOutput(new ClickOutput(5, 1.0, true, true));
        fx.metronome.setEnabled(false);

        fx.runForFrames(SAMPLES_PER_BEAT * 2);

        assertThat(fx.maxAbs(fx.allOutput)).isEqualTo(0.0f);
        assertThat(fx.backend.recordedChannelOutput(5)).isEmpty();
        assertThat(fx.backend.recordedChannelOutput(2)).isEmpty();
        assertThat(fx.backend.recordedChannelOutput(3)).isEmpty();
    }

    /** Boilerplate: a minimal engine + transport + metronome + router setup. */
    private static final class Fixture {
        final AudioFormat format = new AudioFormat(SAMPLE_RATE, 2, 16, BUFFER_FRAMES);
        final AudioEngine engine = new AudioEngine(format);
        final Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        final MetronomeSideOutputRouter router = new MetronomeSideOutputRouter();
        final CueBusManager cueBusManager = new CueBusManager();
        final MockAudioBackend backend = new MockAudioBackend();
        final Transport transport = new Transport();
        // Accumulator for every block of master output produced during a run.
        float[][] allOutput;

        Fixture() {
            backend.open(DeviceId.defaultFor("Mock"),
                    new com.benesquivelmusic.daw.sdk.audio.AudioFormat(SAMPLE_RATE, 2, 16),
                    BUFFER_FRAMES);
            transport.setTempo(120.0);
            transport.setTimeSignature(4, 4);
            transport.setPositionInBeats(0.0);
            transport.play();
            engine.setTransport(transport);
            engine.setMixer(new Mixer());
            engine.setTracks(List.of());
            engine.setBackend(backend);
            engine.setMetronome(metronome);
            engine.setMetronomeSideOutputRouter(router);
            engine.setCueBusManager(cueBusManager);
            engine.start();
        }

        /**
         * Drives exactly {@code totalFrames} frames worth of audio
         * through the engine (using a partial last block when needed)
         * and accumulates the master output into {@link #allOutput}.
         */
        void runForFrames(int totalFrames) {
            allOutput = new float[2][totalFrames];
            float[][] input = new float[2][BUFFER_FRAMES];
            float[][] output = new float[2][BUFFER_FRAMES];
            int rendered = 0;
            while (rendered < totalFrames) {
                int frames = Math.min(BUFFER_FRAMES, totalFrames - rendered);
                for (int ch = 0; ch < 2; ch++) {
                    java.util.Arrays.fill(output[ch], 0.0f);
                }
                engine.processBlock(input, output, frames);
                for (int ch = 0; ch < 2; ch++) {
                    System.arraycopy(output[ch], 0, allOutput[ch],
                            rendered, frames);
                }
                rendered += frames;
            }
        }

        float maxAbs(float[][] buf) {
            float max = 0.0f;
            for (float[] ch : buf) {
                for (float s : ch) {
                    float a = Math.abs(s);
                    if (a > max) {
                        max = a;
                    }
                }
            }
            return max;
        }
    }
}
