package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — sample-accurate loop wrap (Audio Engine Wiring Design Book
 * §4.1, §5.1): the render block is split at the loop boundary — render to
 * loop end, wrap, render the remainder. No post-loop-end audio, no
 * buffer-quantized restart.
 *
 * <p>All quantities are chosen to be exactly representable in binary floating
 * point (sample rate 2¹⁵ Hz at 120 BPM → 2¹⁴ samples per beat; every block
 * advance is a multiple of 2⁻⁶ beats), so the expected↔actual comparison is
 * bit-exact by construction: if an assertion here ever fails, the wrap
 * algorithm is wrong — not the environment.</p>
 */
class RenderPipelineLoopPrecisionTest {

    /** 2¹⁵ Hz at 120 BPM → samplesPerBeat = 2¹⁴ = 16384, exact in binary. */
    private static final double SAMPLE_RATE = 32_768.0;
    private static final double TEMPO = 120.0;
    private static final int SAMPLES_PER_BEAT = 16_384;

    /** Deliberately does NOT divide the loop length (65792 / 512 = 128.5). */
    private static final int BLOCK_SIZE = 512;

    /** Loop = 4 beats + 256 frames: frame-grid aligned, block-grid hostile. */
    private static final int LOOP_FRAMES = 4 * SAMPLES_PER_BEAT + 256;
    private static final double LOOP_END_BEATS = LOOP_FRAMES / (double) SAMPLES_PER_BEAT;

    @Test
    void loopWrapIsSampleAccurateWithNoPostLoopEndBleed() {
        // Mono keeps the mixer path gain-neutral (volume 1.0, no pan law), so
        // every rendered sample must be bit-identical to its source sample.
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK_SIZE);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Track 1"));

        // The clip spans 8 beats — well past the loop end — and every sample
        // encodes its own source index, so any post-loop-end bleed or
        // buffer-quantized restart shows up as a concrete value mismatch.
        int clipSamples = 8 * SAMPLES_PER_BEAT;
        float[][] clipData = new float[1][clipSamples];
        for (int i = 0; i < clipSamples; i++) {
            clipData[0][i] = (i + 1) / 1_048_576.0f; // distinct, nonzero, exact
        }
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Ramp", 0.0, 8.0, null);
        clip.setAudioData(clipData);
        track.addClip(clip);

        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();

        int laps = 3;
        int totalFrames = laps * LOOP_FRAMES;
        float[] rendered = new float[totalFrames];
        float[][] input = new float[1][BLOCK_SIZE];
        float[][] output = new float[1][BLOCK_SIZE];
        int done = 0;
        while (done < totalFrames) {
            int frames = Math.min(BLOCK_SIZE, totalFrames - done);
            Arrays.fill(output[0], 0.0f);
            engine.processBlock(input, output, frames);
            System.arraycopy(output[0], 0, rendered, done, frames);
            done += frames;
        }

        // Split-block assertion: every output frame must carry the clip
        // sample at the loop-mapped source position. In one sweep this proves
        // (a) no output sample originates from beyond the loop end and
        // (b) each wrap restarts at the loop start on the exact next frame.
        int firstMismatch = -1;
        for (int f = 0; f < totalFrames; f++) {
            int src = f % LOOP_FRAMES;
            if (rendered[f] != clipData[0][src]) {
                firstMismatch = f;
                break;
            }
        }
        if (firstMismatch >= 0) {
            int src = firstMismatch % LOOP_FRAMES;
            assertThat(rendered[firstMismatch])
                    .as("output frame %d (lap %d, in-loop frame %d) must be the "
                                    + "clip sample at source index %d — a mismatch here "
                                    + "means post-loop-end bleed or a mis-quantized restart",
                            firstMismatch, firstMismatch / LOOP_FRAMES, src, src)
                    .isEqualTo(clipData[0][src]);
        }

        // Explicit boundary spot checks (redundant with the sweep, but they
        // document the contract):
        assertThat(rendered[LOOP_FRAMES - 1])
                .as("the last pre-wrap frame is the last in-loop source sample")
                .isEqualTo(clipData[0][LOOP_FRAMES - 1]);
        assertThat(rendered[LOOP_FRAMES])
                .as("the first post-wrap frame restarts at the loop start, sample-accurately")
                .isEqualTo(clipData[0][0]);
    }

    @Test
    void transportPositionWrapsAtTheExactLoopBoundaryNotTheBlockBoundary() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK_SIZE);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Track 1"));

        Track track = new Track("Track 1", TrackType.AUDIO);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();

        float[][] input = new float[1][BLOCK_SIZE];
        float[][] output = new float[1][BLOCK_SIZE];
        // 129 blocks = 66048 frames = one full lap (65792) + 256 frames.
        for (int block = 0; block < 129; block++) {
            engine.processBlock(input, output, BLOCK_SIZE);
        }

        // With the closed-form wrap the overshoot carries exactly:
        // position = (129 × 512 − 65792) / 16384 = 256 / 16384 beats.
        assertThat(transport.getPositionInBeats())
                .as("the wrap carries the intra-block overshoot instead of "
                        + "quantizing the restart to the buffer size")
                .isEqualTo(256.0 / SAMPLES_PER_BEAT);
    }
}
