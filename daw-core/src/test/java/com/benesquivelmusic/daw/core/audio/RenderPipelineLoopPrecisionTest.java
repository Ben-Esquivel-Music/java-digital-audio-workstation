package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

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

    /** Reported insert-chain latency: 256 frames → renderOffset = 2⁻⁶ beats, exact. */
    private static final int INSERT_LATENCY_FRAMES = 256;

    /**
     * Sub-sample loop: one frame is 2⁻¹⁴ beats, so a loop of 2⁻¹⁶ beats (a
     * quarter frame) is shorter than the smallest renderable segment.
     */
    private static final double SUB_SAMPLE_LOOP_START = 4.0;
    private static final double SUB_SAMPLE_LOOP_LENGTH = 1.0 / 65_536.0; // 2⁻¹⁶ beats

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

    /**
     * Story 315 review — the no-bleed guarantee must survive a latent insert
     * chain: with {@value #INSERT_LATENCY_FRAMES} frames of reported plugin
     * latency the PDC-shifted render cursor
     * ({@code position + renderOffsetBeats}) reaches the loop end 256 frames
     * before the raw transport cursor does. At block 128 the transport sits
     * at beat 4.0 (inside the loop) while the shifted cursor sits exactly on
     * the loop end (65536 + 256 = 65792 frames) — without loop-mapping the
     * shifted cursor, {@code renderTracks} skips its split guard and renders
     * the whole block linearly from beyond the loop.
     */
    @Test
    void latentInsertChainDoesNotBleedPostLoopEndContentAtTheWrap() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK_SIZE);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel("Track 1");
        mixer.addChannel(channel);
        // A unity-gain passthrough that only REPORTS latency: the channel
        // stays gain-neutral while getSystemLatencySamples() becomes 256, so
        // the render cursor runs 256 frames (2⁻⁶ beats, exact) ahead of the
        // transport.
        channel.addInsert(new InsertSlot("Latent",
                new ReportedLatencyProcessor(INSERT_LATENCY_FRAMES)));
        assertThat(mixer.getSystemLatencySamples())
                .as("harness precondition: the insert chain must report its latency")
                .isEqualTo(INSERT_LATENCY_FRAMES);

        // The only clip content sits immediately PAST the loop end, so every
        // rendered frame must be silent: any energy is content rendered from
        // at/beyond the loop end by the PDC-shifted cursor.
        int clipSamples = SAMPLES_PER_BEAT;
        float[][] clipData = new float[1][clipSamples];
        for (int i = 0; i < clipSamples; i++) {
            clipData[0][i] = (i + 1) / 1_048_576.0f; // distinct, nonzero, exact
        }
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("PostLoop", LOOP_END_BEATS, LOOP_END_BEATS + 1.0, null);
        clip.setAudioData(clipData);
        track.addClip(clip);

        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();

        // 132 blocks cover the shifted cursor's loop-end hit at block 128
        // (and the transport's own wrap one boundary later) with margin.
        float[][] input = new float[1][BLOCK_SIZE];
        float[][] output = new float[1][BLOCK_SIZE];
        for (int block = 0; block < 132; block++) {
            Arrays.fill(output[0], 0.0f);
            engine.processBlock(input, output, BLOCK_SIZE);
            for (int f = 0; f < BLOCK_SIZE; f++) {
                if (output[0][f] != 0.0f) {
                    assertThat(output[0][f])
                            .as("frame %d of block %d is non-silent — the only clip "
                                            + "content lies past the loop end, so energy "
                                            + "here is PDC-shifted post-loop-end bleed",
                                    f, block)
                            .isEqualTo(0.0f);
                }
            }
        }
    }

    /**
     * Story 315 review — the in-block wrap must be modulo, not plain
     * subtraction. Every segment is clamped to whole frames, so a loop
     * shorter than one frame (2⁻¹⁶ beats = a quarter frame) is overshot by
     * MORE than its own length on every frame: one frame from the loop start
     * lands 3·2⁻¹⁶ beats past the loop end. A single subtraction wrapped the
     * cursor to {@code loopStart + 3·2⁻¹⁶} — still past the end — after
     * which the split guard never fired again and frames 1…511 of the block
     * rendered linearly from beyond the loop (source samples 65537…66047).
     * Modulo maps any overshoot back into {@code [loopStart, loopEnd)}, so
     * every frame of every block is one full sub-sample lap and carries the
     * single in-loop source sample; the transport's own closed-form wrap
     * then keeps the cursor inside the loop across block boundaries.
     */
    @Test
    void subSampleLoopNeverRendersFromBeyondTheLoopEnd() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK_SIZE);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Track 1"));

        // Every source sample encodes its own index, so content rendered from
        // beyond the loop end shows up as a concrete value mismatch.
        int clipSamples = 8 * SAMPLES_PER_BEAT;
        float[][] clipData = new float[1][clipSamples];
        for (int i = 0; i < clipSamples; i++) {
            clipData[0][i] = (i + 1) / 1_048_576.0f; // distinct, nonzero, exact
        }
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Ramp", 0.0, 8.0, null);
        clip.setAudioData(clipData);
        track.addClip(clip);

        double loopStart = SUB_SAMPLE_LOOP_START;
        double loopEnd = loopStart + SUB_SAMPLE_LOOP_LENGTH;
        transport.setLoopEnabled(true);
        transport.setLoopRegion(loopStart, loopEnd);
        transport.setPositionInBeats(loopStart); // inline: applied while STOPPED
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        engine.start();

        // The only source sample whose beat position lies inside the loop is
        // the one at the loop start (4 × 16384 = 65536); each rendered frame
        // is one full sub-sample lap and must carry exactly that sample.
        float inLoopSample = clipData[0][(int) (loopStart * SAMPLES_PER_BEAT)];
        float[][] input = new float[1][BLOCK_SIZE];
        float[][] output = new float[1][BLOCK_SIZE];
        for (int block = 0; block < 3; block++) {
            Arrays.fill(output[0], 0.0f);
            engine.processBlock(input, output, BLOCK_SIZE);
            for (int f = 0; f < BLOCK_SIZE; f++) {
                if (output[0][f] != inLoopSample) {
                    assertThat(output[0][f])
                            .as("frame %d of block %d must be the single in-loop source "
                                            + "sample — anything else is content rendered "
                                            + "from beyond the sub-sample loop's end",
                                    f, block)
                            .isEqualTo(inLoopSample);
                }
            }
            assertThat(transport.getPositionInBeats())
                    .as("after block %d the transport cursor must still sit inside "
                            + "the sub-sample loop", block)
                    .isGreaterThanOrEqualTo(loopStart)
                    .isLessThan(loopEnd);
        }
    }

    /** Unity-gain passthrough that only reports latency — drives the PDC offset. */
    private record ReportedLatencyProcessor(int latency) implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int frames) {
            for (int ch = 0; ch < in.length; ch++) {
                System.arraycopy(in[ch], 0, out[ch], 0, frames);
            }
        }

        @Override public void reset() {}
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }

        @Override
        public int getLatencySamples() {
            return latency;
        }
    }
}
