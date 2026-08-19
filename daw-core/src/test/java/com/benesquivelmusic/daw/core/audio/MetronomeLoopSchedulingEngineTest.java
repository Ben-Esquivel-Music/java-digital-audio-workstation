package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — loop-aware metronome scheduling: click scheduling walks the
 * same loop-split segments the clip renderer walks, so no click is scheduled
 * for a beat at or beyond the loop end, and the wrapped-in click at the loop
 * start fires within the wrapping block at its exact post-wrap offset
 * (previously clicks were scheduled over the linear, un-wrapped block range —
 * bleeding past the loop end and missing the wrapped-in downbeat).
 *
 * <p>Follows the {@code MetronomeSideOutputRouterEngineTest} harness pattern
 * (engine + transport + metronome + router, master output accumulated across
 * deterministic blocks). All quantities are exactly representable in binary
 * floating point (2¹⁵ Hz at 120 BPM → 2¹⁴ samples per beat), so the expected
 * click onsets are exact frame indices by construction.</p>
 */
class MetronomeLoopSchedulingEngineTest {

    /** 2¹⁵ Hz at 120 BPM → samplesPerBeat = 2¹⁴ = 16384, exact in binary. */
    private static final double SAMPLE_RATE = 32_768.0;
    private static final double TEMPO = 120.0;
    private static final int SAMPLES_PER_BEAT = 16_384;
    private static final int BUFFER_FRAMES = 512;

    /**
     * Loop = 1 beat + 3968 frames = 20352 frames = 39.75 blocks — the wrap
     * lands mid-block, so the wrapped-in downbeat can only fire if the block
     * is split at the loop boundary. Clicks land at beats 0 and 1 of each
     * lap; the gap between the beat-1 click and the next lap's downbeat
     * (3968 frames) exceeds the ~656-frame click length, so every click is
     * separated by genuine silence.
     */
    private static final int LOOP_FRAMES = SAMPLES_PER_BEAT + 3968;
    private static final double LOOP_END_BEATS = LOOP_FRAMES / (double) SAMPLES_PER_BEAT;

    /**
     * Sub-sample loop for the modulo-wrap regression: one frame is 2⁻¹⁴
     * beats, the loop is 2⁻¹⁶ beats (a quarter frame) and starts 2⁻¹⁶ beats
     * after beat 4 — so it contains NO sixteenth-grid position (4.0 lies
     * before it, 4.25 after it) and a loop-faithful metronome stays silent.
     * The block is half a beat long so that, if the wrap ever leaves the
     * cursor past the loop end, the linear walk reaches the out-of-loop
     * grid position 4.25 within the same block.
     */
    private static final double SUB_SAMPLE_LOOP_LENGTH = 1.0 / 65_536.0; // 2⁻¹⁶ beats
    private static final double SUB_SAMPLE_LOOP_START = 4.0 + SUB_SAMPLE_LOOP_LENGTH;
    private static final double SUB_SAMPLE_LOOP_END = SUB_SAMPLE_LOOP_START + SUB_SAMPLE_LOOP_LENGTH;
    private static final int SUB_SAMPLE_BLOCK_FRAMES = SAMPLES_PER_BEAT / 2;

    @Test
    void noClickSchedulesPastTheLoopEndAndTheWrappedDownbeatFiresMidBlock() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 2, 16, BUFFER_FRAMES);
        AudioEngine engine = new AudioEngine(format);
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS);
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        // Main-mix click only — no side output, so no backend is needed.
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));

        engine.setGraph(transport, new Mixer(), List.of());
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());
        engine.start();

        int laps = 3;
        int totalFrames = laps * LOOP_FRAMES;
        float[][] rendered = new float[2][totalFrames];
        float[][] input = new float[2][BUFFER_FRAMES];
        float[][] output = new float[2][BUFFER_FRAMES];
        int done = 0;
        while (done < totalFrames) {
            int frames = Math.min(BUFFER_FRAMES, totalFrames - done);
            for (int ch = 0; ch < 2; ch++) {
                java.util.Arrays.fill(output[ch], 0.0f);
            }
            engine.processBlock(input, output, frames);
            for (int ch = 0; ch < 2; ch++) {
                System.arraycopy(output[ch], 0, rendered[ch], done, frames);
            }
            done += frames;
        }

        // Expected onsets: every lap restarts the loop timeline, so clicks
        // land at beat 0 and beat 1 of each lap — and nowhere else. Note the
        // lap boundaries (20352, 40704) sit mid-block (offsets 384 and 256):
        // those wrapped-in downbeats only fire if scheduling splits the block.
        int clickLength = Math.max(
                metronome.generateClick(true)[0].length,
                metronome.generateClick(false)[0].length);
        List<Integer> expectedOnsets = new ArrayList<>();
        for (int lap = 0; lap < laps; lap++) {
            expectedOnsets.add(lap * LOOP_FRAMES);
            expectedOnsets.add(lap * LOOP_FRAMES + SAMPLES_PER_BEAT);
        }

        for (int onset : expectedOnsets) {
            assertThat(maxAbs(rendered, onset, Math.min(onset + 64, totalFrames)))
                    .as("a click must sound at frame %d (loop-mapped beat %s)",
                            onset, (onset % LOOP_FRAMES) / (double) SAMPLES_PER_BEAT)
                    .isGreaterThan(0.0f);
        }

        // Everything outside a click window must be perfectly silent: any
        // energy there is either a click scheduled for a beat at/after the
        // loop end (bleed) or a mis-placed wrapped click.
        for (int f = 0; f < totalFrames; f++) {
            if (insideAnyClickWindow(f, expectedOnsets, clickLength)) {
                continue;
            }
            if (rendered[0][f] != 0.0f || rendered[1][f] != 0.0f) {
                assertThat(rendered[0][f])
                        .as("frame %d lies outside every legitimate click window — "
                                + "non-silence here is a click scheduled past the "
                                + "loop end or at a wrong wrap offset", f)
                        .isEqualTo(0.0f);
            }
        }
    }

    /**
     * Story 315 review — a seek target exactly AT the loop end is permitted
     * while looping ({@code setPositionInBeats} does not clamp into the loop;
     * {@code advancePosition} wraps only at the next block boundary).
     * Scheduling loop-maps that cursor to the loop start before walking the
     * grid, so the very first block fires the wrapped downbeat at frame 0
     * and the whole run is indistinguishable from one started at the loop
     * start. (Unmapped, the first block scheduled linearly over the
     * out-of-loop range [loopEnd, loopEnd + block) — which contains no grid
     * position — and the frame-0 downbeat went silently missing.)
     */
    @Test
    void seekExactlyToTheLoopEndFiresTheWrappedDownbeatAtFrameZero() {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS);
        transport.setPositionInBeats(LOOP_END_BEATS); // inline: applied while STOPPED
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        int laps = 3;
        int totalFrames = laps * LOOP_FRAMES;
        float[][] rendered = renderBlocks(engine, totalFrames);

        // Identical expected onsets to a run started at the loop start:
        // beats 0 and 1 of each lap — and nowhere else.
        int clickLength = Math.max(
                metronome.generateClick(true)[0].length,
                metronome.generateClick(false)[0].length);
        List<Integer> expectedOnsets = new ArrayList<>();
        for (int lap = 0; lap < laps; lap++) {
            expectedOnsets.add(lap * LOOP_FRAMES);
            expectedOnsets.add(lap * LOOP_FRAMES + SAMPLES_PER_BEAT);
        }

        for (int onset : expectedOnsets) {
            assertThat(maxAbs(rendered, onset, Math.min(onset + 64, totalFrames)))
                    .as("a click must sound at frame %d — the loop-end seek maps to "
                                    + "the loop start, so the run must match one started there",
                            onset)
                    .isGreaterThan(0.0f);
        }
        assertNoEnergyOutsideClickWindows(rendered, expectedOnsets, clickLength, totalFrames);
    }

    /**
     * Story 315 review — a seek BEYOND the loop end must not emit the
     * out-of-loop grid click at the seek target: scheduling loop-maps the
     * cursor ({@code loopStart + ((pos − loopEnd) mod loopLength)} — the
     * same closed form {@code advancePosition} applies one block later), so
     * the first block already walks the wrapped timeline and every click
     * lands at its post-wrap offset. (Unmapped, beat 2 — a grid position
     * past the 1.2421875-beat loop end — fired a click at frame 0.)
     */
    @Test
    void seekBeyondTheLoopEndSchedulesFromTheWrappedPositionNotTheSeekTarget() {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS);
        transport.setPositionInBeats(2.0); // past the loop end; inline while STOPPED
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        // Beat 2 maps to 2 − 1.2421875 = 0.7578125 beats = 12416 frames into
        // the loop: beat 1 arrives 3968 frames in, the wrap (and its
        // downbeat) 7936 frames in, then the lap pattern repeats.
        int mappedStartFrame = 2 * SAMPLES_PER_BEAT - LOOP_FRAMES; // 12416
        int firstBeatOnset = SAMPLES_PER_BEAT - mappedStartFrame;  // 3968
        int firstWrapOnset = LOOP_FRAMES - mappedStartFrame;       // 7936
        int laps = 2;
        int totalFrames = firstWrapOnset + laps * LOOP_FRAMES;     // 48640

        float[][] rendered = renderBlocks(engine, totalFrames);

        int clickLength = Math.max(
                metronome.generateClick(true)[0].length,
                metronome.generateClick(false)[0].length);
        List<Integer> expectedOnsets = new ArrayList<>();
        expectedOnsets.add(firstBeatOnset);
        for (int lap = 0; lap < laps; lap++) {
            expectedOnsets.add(firstWrapOnset + lap * LOOP_FRAMES);
            int beatOneOnset = firstWrapOnset + lap * LOOP_FRAMES + SAMPLES_PER_BEAT;
            if (beatOneOnset < totalFrames) {
                expectedOnsets.add(beatOneOnset);
            }
        }

        assertThat(maxAbs(rendered, 0, 64))
                .as("no click may fire at the seek target itself — beat 2 lies past "
                        + "the loop end, so a click there is out-of-loop bleed")
                .isEqualTo(0.0f);
        for (int onset : expectedOnsets) {
            assertThat(maxAbs(rendered, onset, Math.min(onset + 64, totalFrames)))
                    .as("a click must sound at frame %d — the post-wrap offset of its "
                                    + "loop-mapped grid position",
                            onset)
                    .isGreaterThan(0.0f);
        }
        assertNoEnergyOutsideClickWindows(rendered, expectedOnsets, clickLength, totalFrames);
    }

    /**
     * Story 315 review — the in-block wrap must be modulo, not plain
     * subtraction. Every segment is clamped to whole frames, so a loop
     * shorter than one frame is overshot by MORE than its own length: one
     * frame from the loop start lands 3·2⁻¹⁶ beats past the loop end. A
     * single subtraction wrapped the cursor to {@code loopStart + 3·2⁻¹⁶} =
     * beat 4 + 2⁻¹⁴ — still past the end — after which the split guard never
     * fired again and the rest of the block walked the linear timeline: the
     * out-of-loop sixteenth at beat 4.25 clicked at frame 4096. Modulo maps
     * any overshoot back into {@code [loopStart, loopEnd)}, where there is no
     * grid position, so the output must be perfectly silent and the
     * transport cursor must stay inside the loop across block boundaries.
     */
    @Test
    void subSampleLoopNeverSchedulesClicksFromBeyondTheLoopEnd() {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(SUB_SAMPLE_LOOP_START, SUB_SAMPLE_LOOP_END);
        transport.setPositionInBeats(SUB_SAMPLE_LOOP_START); // inline: applied while STOPPED
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setSubdivision(Subdivision.SIXTEENTH);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 2, 16, SUB_SAMPLE_BLOCK_FRAMES);
        AudioEngine engine = new AudioEngine(format);
        engine.setGraph(transport, new Mixer(), List.of());
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());
        engine.start();

        float[][] input = new float[2][SUB_SAMPLE_BLOCK_FRAMES];
        float[][] output = new float[2][SUB_SAMPLE_BLOCK_FRAMES];
        for (int block = 0; block < 3; block++) {
            for (int ch = 0; ch < 2; ch++) {
                java.util.Arrays.fill(output[ch], 0.0f);
            }
            engine.processBlock(input, output, SUB_SAMPLE_BLOCK_FRAMES);

            for (int f = 0; f < SUB_SAMPLE_BLOCK_FRAMES; f++) {
                if (output[0][f] != 0.0f || output[1][f] != 0.0f) {
                    assertThat(Math.max(Math.abs(output[0][f]), Math.abs(output[1][f])))
                            .as("frame %d of block %d is non-silent — the sub-sample loop "
                                            + "contains no grid position, so a click here was "
                                            + "scheduled from beyond the loop end",
                                    f, block)
                            .isEqualTo(0.0f);
                }
            }
            assertThat(transport.getPositionInBeats())
                    .as("after block %d the transport cursor must still sit inside "
                            + "the sub-sample loop", block)
                    .isGreaterThanOrEqualTo(SUB_SAMPLE_LOOP_START)
                    .isLessThan(SUB_SAMPLE_LOOP_END);
        }
    }

    /** Builds the engine exactly as the harness above: graph, metronome, router, start. */
    private static AudioEngine startedEngine(Transport transport, Metronome metronome) {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 2, 16, BUFFER_FRAMES);
        AudioEngine engine = new AudioEngine(format);
        engine.setGraph(transport, new Mixer(), List.of());
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());
        engine.start();
        return engine;
    }

    /** Renders {@code totalFrames} through the engine in BUFFER_FRAMES blocks. */
    private static float[][] renderBlocks(AudioEngine engine, int totalFrames) {
        float[][] rendered = new float[2][totalFrames];
        float[][] input = new float[2][BUFFER_FRAMES];
        float[][] output = new float[2][BUFFER_FRAMES];
        int done = 0;
        while (done < totalFrames) {
            int frames = Math.min(BUFFER_FRAMES, totalFrames - done);
            for (int ch = 0; ch < 2; ch++) {
                java.util.Arrays.fill(output[ch], 0.0f);
            }
            engine.processBlock(input, output, frames);
            for (int ch = 0; ch < 2; ch++) {
                System.arraycopy(output[ch], 0, rendered[ch], done, frames);
            }
            done += frames;
        }
        return rendered;
    }

    /**
     * Everything outside a click window must be perfectly silent: any energy
     * there is a click scheduled for a beat at/after the loop end (bleed) or
     * a mis-placed wrapped click.
     */
    private static void assertNoEnergyOutsideClickWindows(float[][] rendered,
                                                          List<Integer> onsets,
                                                          int clickLength,
                                                          int totalFrames) {
        for (int f = 0; f < totalFrames; f++) {
            if (insideAnyClickWindow(f, onsets, clickLength)) {
                continue;
            }
            if (rendered[0][f] != 0.0f || rendered[1][f] != 0.0f) {
                assertThat(rendered[0][f])
                        .as("frame %d lies outside every legitimate click window — "
                                + "non-silence here is a click scheduled past the "
                                + "loop end or at a wrong wrap offset", f)
                        .isEqualTo(0.0f);
            }
        }
    }

    private static boolean insideAnyClickWindow(int frame, List<Integer> onsets, int clickLength) {
        for (int onset : onsets) {
            if (frame >= onset && frame < onset + clickLength) {
                return true;
            }
        }
        return false;
    }

    private static float maxAbs(float[][] buf, int from, int toExclusive) {
        float max = 0.0f;
        for (float[] ch : buf) {
            for (int i = from; i < toExclusive; i++) {
                float a = Math.abs(ch[i]);
                if (a > max) {
                    max = a;
                }
            }
        }
        return max;
    }
}
