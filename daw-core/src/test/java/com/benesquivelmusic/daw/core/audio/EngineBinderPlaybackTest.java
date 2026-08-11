package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 314 — audibility, position, loop, and metronome through the
 * production {@link EngineBinder}: the same block-drive assertions as
 * {@link AudioEnginePlaybackTest}, but the binding goes through the binder
 * (never the raw engine setters), proving the production wiring renders.
 */
class EngineBinderPlaybackTest {

    // 44.1 kHz stereo with an 8-frame buffer for block-granular assertions.
    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 8;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);

    // At 120 BPM: samplesPerBeat = 44100 * 60 / 120 = 22050
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;

    @Test
    void aBoundProjectWithAClipRendersNonSilentBlocksDuringPlay() {
        AudioEngine engine = new AudioEngine(FORMAT);
        DawProject project = buildClipProject(1.0);

        new EngineBinder(engine).bind(project);
        project.getTransport().play();
        engine.start();

        float[][] rendered = drive(engine, 16);
        assertThat(maxAbs(rendered))
                .as("play on a bound project with a clip is audible (maxAbs > 0)")
                .isGreaterThan(0.0f);
    }

    @Test
    void anEngineWithoutABinderBindRendersPassthroughSilenceForTheSameDriveLoop() {
        // Regression guard for the pre-story-314 production state: no binder
        // bind means transport/mixer/tracks stay null and every block is a
        // passthrough of the (silent) input.
        AudioEngine engine = new AudioEngine(FORMAT);
        buildClipProject(1.0); // exists, but never bound
        engine.start();

        float[][] rendered = drive(engine, 16);
        assertThat(maxAbs(rendered))
                .as("an unbound engine renders passthrough silence").isEqualTo(0.0f);
    }

    @Test
    void anUnboundEnginePassesANonZeroInputBufferThroughUnchanged() {
        // Silent input cannot distinguish passthrough from hard-muting — a
        // recognizable non-zero input must come out element-for-element.
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < BUFFER_SIZE; i++) {
                input[ch][i] = 0.25f + 0.1f * ch + 0.01f * i;
            }
        }
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int ch = 0; ch < CHANNELS; ch++) {
            assertThat(output[ch])
                    .as("channel %d passes the input through unchanged (not hard-muted)", ch)
                    .containsExactly(input[ch]);
        }
    }

    @Test
    void theTransportPositionAdvancesBlockByBlockUnderEngineDrive() {
        AudioEngine engine = new AudioEngine(FORMAT);
        DawProject project = buildClipProject(1.0);
        Transport transport = project.getTransport();

        new EngineBinder(engine).bind(project);
        transport.play();
        engine.start();
        assertThat(transport.getPositionInBeats()).isEqualTo(0.0);

        int blocks = 5;
        drive(engine, blocks);

        double expected = blocks * BUFFER_SIZE / SAMPLES_PER_BEAT;
        assertThat(transport.getPositionInBeats())
                .as("engine-driven advancePosition moves the playhead block by block")
                .isCloseTo(expected, offset(0.0001));
    }

    @Test
    void playbackWrapsBackIntoTheLoopRegionAtBlockGranularity() {
        AudioEngine engine = new AudioEngine(FORMAT);
        DawProject project = buildClipProject(2.0);
        Transport transport = project.getTransport();
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 1.0);
        // Start a few blocks below the loop end so the drive loop crosses it.
        double startPosition = 1.0 - 3.0 * BUFFER_SIZE / SAMPLES_PER_BEAT;
        transport.setPositionInBeats(startPosition);

        new EngineBinder(engine).bind(project);
        transport.play();
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        for (int block = 0; block < 10; block++) {
            engine.processBlock(input, output, BUFFER_SIZE);
            // Block-granularity wrap (sample accuracy is story 315): after
            // every block the position sits inside [loopStart, loopEnd).
            assertThat(transport.getPositionInBeats())
                    .as("position stays inside the loop region after block %d", block)
                    .isGreaterThanOrEqualTo(0.0)
                    .isLessThan(1.0);
        }
        assertThat(transport.getPositionInBeats())
                .as("the position wrapped back below its pre-wrap start")
                .isLessThan(startPosition);
    }

    @Test
    void theMetronomeClickBlockExecutesDuringPlayOnAnEmptyBoundProject() {
        AudioEngine engine = new AudioEngine(FORMAT);
        DawProject project = new DawProject("Empty", FORMAT);
        project.getTransport().setTempo(TEMPO);

        Metronome metronome = new Metronome(SAMPLE_RATE, CHANNELS);
        metronome.setEnabled(true);
        // Main-mix click only — no side output, so no backend is needed.
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());

        new EngineBinder(engine).bind(project);
        project.getTransport().play();
        engine.start();

        // The first click lands on beat 0 (sample 0), so the opening blocks
        // already carry click audio when the click-scheduling block runs.
        float[][] rendered = drive(engine, 32);
        assertThat(maxAbs(rendered))
                .as("the click-scheduling block executes during play (non-silent master out)")
                .isGreaterThan(0.0f);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** One audio track carrying a sine clip starting at beat 0. */
    private static DawProject buildClipProject(double lengthBeats) {
        DawProject project = new DawProject("Playback", FORMAT);
        project.getTransport().setTempo(TEMPO);

        Track track = project.createAudioTrack("Lead");
        AudioClip clip = new AudioClip("Clip", 0.0, lengthBeats, null);
        int samples = (int) (SAMPLES_PER_BEAT * lengthBeats);
        float[][] data = new float[CHANNELS][samples];
        for (int i = 0; i < samples; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE) * 0.7f;
            data[0][i] = v;
            data[1][i] = v * 0.85f;
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return project;
    }

    /** Drives {@code blocks} zero-input blocks and accumulates the master output. */
    private static float[][] drive(AudioEngine engine, int blocks) {
        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        float[][] all = new float[CHANNELS][blocks * BUFFER_SIZE];
        for (int block = 0; block < blocks; block++) {
            for (int ch = 0; ch < CHANNELS; ch++) {
                Arrays.fill(output[ch], 0.0f);
            }
            engine.processBlock(input, output, BUFFER_SIZE);
            for (int ch = 0; ch < CHANNELS; ch++) {
                System.arraycopy(output[ch], 0, all[ch], block * BUFFER_SIZE, BUFFER_SIZE);
            }
        }
        return all;
    }

    private static float maxAbs(float[][] buffer) {
        float max = 0.0f;
        for (float[] channel : buffer) {
            for (float sample : channel) {
                float abs = Math.abs(sample);
                if (abs > max) {
                    max = abs;
                }
            }
        }
        return max;
    }
}
