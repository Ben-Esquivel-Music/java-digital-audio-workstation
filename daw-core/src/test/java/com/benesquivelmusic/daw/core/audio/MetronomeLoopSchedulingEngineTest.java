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

    /**
     * Loop end = 20352.5 frames — half a frame past a whole frame, so the
     * loop length is NOT a whole number of frames. This is the ordinary
     * case: at any musically meaningful tempo/rate pair the loop end lands
     * between two frames. Written as 40705 / 2¹⁵ beats (= 20352.5 / 2¹⁴)
     * so the value is exact in binary floating point and the expected
     * onsets below are exact frame indices by construction.
     */
    private static final double LOOP_END_BEATS_HALF_FRAME = 40_705.0 / 32_768.0;

    /**
     * Loop end = 20479.5 frames, again a half-frame position (40959 / 2¹⁵
     * beats). Chosen because ceil(20479.5) = 20480 = 40 × 512: the
     * quantized wrap lands EXACTLY on a block boundary, so the sub-frame
     * residue the wrap leaves behind is carried into the NEXT block, where
     * the wrap itself is invisible to that block's scheduling walk.
     */
    private static final double LOOP_END_BEATS_BLOCK_ALIGNED_WRAP = 40_959.0 / 32_768.0;

    /**
     * Seek target for the abandoned-lap regression: 81919 / 2¹⁵ beats, i.e.
     * one whole lap plus half a frame past {@link
     * #LOOP_END_BEATS_BLOCK_ALIGNED_WRAP}. Exact in binary, and the
     * block-entry mapping {@code ((target − loopEnd) mod loopLength)} takes
     * it to exactly 2⁻¹⁵ beats = half a frame past the loop start — inside
     * the widening's {@code < 1.0} residue test, so only the wrap-flag clear
     * can suppress the click there.
     */
    private static final double SEEK_PAST_BLOCK_ALIGNED_LOOP_END = 81_919.0 / 32_768.0;

    /**
     * A loop of exactly 1.5 frames: 3·2⁻¹⁵ beats against a frame of 2⁻¹⁴
     * beats. Between one and two frames, so the sub-frame guard
     * ({@code loopLength × samplesPerBeat >= 1.0}) must keep the grid
     * widening ENABLED — a threshold of 2.0 or 4.0 would not.
     */
    private static final double ONE_AND_A_HALF_FRAME_LOOP_LENGTH = 3.0 / 32_768.0;

    /** A quarter of a frame in beats: 2⁻¹⁶, exact at {@link #SAMPLE_RATE} / {@link #TEMPO}. */
    private static final double QUARTER_FRAME_BEATS = 1.0 / 65_536.0;

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

    /**
     * Story 315 review — the pre-wrap segment is clamped to WHOLE frames,
     * so a loop end that is not sample-aligned is overshot by a sub-frame
     * residue and the wrap leaves the scheduling cursor that residue PAST
     * the loop start. Walking the grid from that fractional cursor makes
     * {@code ceil()} step OVER the grid position exactly at the loop
     * start, so the wrapped-in downbeat was silently dropped — on every
     * lap whose length is not a whole number of frames, i.e. the ordinary
     * case. Every other test in this class uses a frame-aligned loop
     * length (20352 frames) and therefore cannot see the bug at all.
     *
     * <p>Here the loop ends at 20352.5 frames, so the residue alternates
     * 0.5 / 0.0 from lap to lap: laps that wrap at a half-frame position
     * lost their downbeat, laps that wrap exactly on a frame kept it.
     * Before the fix frames {@code 20353} and {@code 61058} — the
     * wrapped-in loop-start downbeats of the two half-residue laps — were
     * completely silent, while every other expected onset already
     * sounded.</p>
     *
     * <p>The onsets below are the quantized frames of the fixed walk over
     * 101 376 frames (198 whole blocks, just under five laps): each lap
     * contributes its loop-start downbeat at the quantized wrap frame and
     * its beat-1 click one beat (16384 frames) later. The smallest gap
     * between consecutive onsets is 3968 frames, far more than the
     * ~656-frame click body, so every click is surrounded by genuine
     * silence and the outside-the-windows sweep is meaningful.</p>
     */
    @Test
    void nonFrameAlignedLoopStillFiresTheWrappedDownbeat() {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS_HALF_FRAME);
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        // Main-mix click only — no side output, so no backend is needed.
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        int totalFrames = 198 * BUFFER_FRAMES; // 101_376
        float[][] rendered = renderBlocks(engine, totalFrames);

        int clickLength = Math.max(
                metronome.generateClick(true)[0].length,
                metronome.generateClick(false)[0].length);
        List<Integer> expectedOnsets = List.of(
                0, 16_384,       // lap 0: downbeat, beat 1
                20_353, 36_737,  // lap 1: wrap at ceil(20352.5), then beat 1
                40_705, 57_089,  // lap 2: this wrap lands exactly on a frame
                61_058, 77_442,  // lap 3: half-frame wrap again
                81_410, 97_794); // lap 4

        for (int onset : expectedOnsets) {
            assertThat(maxAbs(rendered, onset, Math.min(onset + 64, totalFrames)))
                    .as("a click must sound at frame %d — every lap of a "
                            + "non-frame-aligned loop owes exactly one loop-start "
                            + "downbeat and one beat-1 click", onset)
                    .isGreaterThan(0.0f);
        }
        assertNoEnergyOutsideClickWindows(rendered, expectedOnsets, clickLength, totalFrames);
    }

    /**
     * Story 315 review — the companion case that rules out a per-block
     * "did we just wrap?" flag. This loop ends at 20479.5 frames and
     * ceil(20479.5) = 20480 = 40 × 512, so the quantized wrap lands
     * exactly on a block boundary: the block that performs the wrap ends
     * with it, and the half-frame residue is only observable at the START
     * of the following block, where no wrap occurs at all. Recovering the
     * loop-start grid position must therefore be POSITIONAL — "does this
     * segment begin inside the sub-frame residue after the loop start?" —
     * rather than driven by a wrap event.
     *
     * <p>Before the fix three onsets were silent. Frames {@code 20480}
     * and {@code 61439} are the loop-start downbeats whose residue was
     * carried across a block boundary. Frame {@code 36864} is a second,
     * independent sub-bug: that lap's beat-1 grid position sits at
     * <b>36863.5</b>, exactly half a frame before the end of its block, so
     * nearest-frame rounding — {@code Math.round}, round-half-up — puts it
     * on {@code 36864}, an offset of exactly {@code numFrames} that its own
     * segment cannot address. Originally the block bound dropped it outright
     * and no click sounded at all.</p>
     *
     * <p>Story 315 review (third round) — the remedy moved, and the onset
     * with it. Clamping the offset down to the last frame the segment owns
     * made the click sound again, but at {@code 36863}: one sample EARLY,
     * on the frame the rounding had just rejected. The scheduling window is
     * now the segment's frame-ownership interval, so this position is not
     * admitted here at all — the next block collects it and sounds it on its
     * own frame 0, which is {@code 36864}, the frame nearest-frame rounding
     * always said it belonged on.</p>
     *
     * <p>81 408 frames = 159 whole blocks covers four laps' worth of grid
     * positions. The onsets below are spaced 16384, 4096, 16384, 4095,
     * 16384, 4096 and 16384 frames apart, so the minimum onset gap is
     * <b>4095</b> frames — one less than before the carry moved 36863 to
     * 36864, and still far above the ~655-frame click body, which is what
     * keeps both the silence sweep and the rising-edge onset census
     * meaningful. (3968 is the minimum gap of
     * {@link #nonFrameAlignedLoopStillFiresTheWrappedDownbeat()}, whose loop
     * is 20352.5 frames rather than 20479.5; it does not apply here.)</p>
     */
    @Test
    void wrapLandingOnABlockBoundaryStillFiresTheLoopStartAndTheHalfFrameBeat() {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS_BLOCK_ALIGNED_WRAP);
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        int totalFrames = 159 * BUFFER_FRAMES; // 81_408
        float[][] rendered = renderBlocks(engine, totalFrames);

        int clickLength = Math.max(
                metronome.generateClick(true)[0].length,
                metronome.generateClick(false)[0].length);
        List<Integer> expectedOnsets = List.of(
                0, 16_384,       // lap 0: downbeat, beat 1
                20_480, 36_864,  // lap 1: block-boundary wrap, then the beat-1
                                 //        click carried into the next block
                40_959, 57_343,  // lap 2
                61_439, 77_823); // lap 3

        // The COMPLETE onset census, which is what makes the 36_864 above
        // load-bearing: the per-onset energy sweep below cannot tell 36_863
        // from 36_864 (a click body is 655 frames long, so either onset fills
        // the other's window), and neither can the silence sweep. Recovering
        // the scheduler's own frames from the rendered rising edges can, and
        // the minimum onset gap here — 4095 frames — is far above the click
        // body, so every click is separable.
        assertThat(clickOnsets(rendered, totalFrames, metronome.generateClick(true)))
                .as("every frame a click was scheduled on, across four laps — a "
                        + "one-frame displacement changes this list")
                .containsExactlyElementsOf(expectedOnsets);

        for (int onset : expectedOnsets) {
            assertThat(maxAbs(rendered, onset, Math.min(onset + 64, totalFrames)))
                    .as("a click must sound at frame %d — neither a wrap carried "
                            + "across a block boundary nor a grid position that "
                            + "rounds to the block end may lose its click", onset)
                    .isGreaterThan(0.0f);
        }
        assertNoEnergyOutsideClickWindows(rendered, expectedOnsets, clickLength, totalFrames);
    }

    /**
     * Story 315 review — the click walk's block-entry loop mapping must CLEAR
     * the wrap flag: a seek past the loop end abandons the lap the previous
     * block's wrap left owing, so no loop-start click may sound at the seek
     * landing.
     *
     * <p>The clear is asymmetric with {@code renderTracks}, which deliberately
     * does not clear in the same place (its PDC-shifted mapping is the
     * continuation of a quantized wrap, not a seek). Every other metronome
     * fixture in this class is blind to the clear: they either never seek, or
     * seek in block 0 where the flag is still false — deleting the line leaves
     * their rendered output byte-identical. This test is the one that sees
     * it.</p>
     *
     * <p>Construction, all quantities exact in binary:</p>
     * <ul>
     *   <li>The loop is {@link #LOOP_END_BEATS_BLOCK_ALIGNED_WRAP} — 20479.5
     *       frames, whose quantized wrap {@code ceil(20479.5) = 20480} is
     *       exactly {@code 40 × 512}. Rendering 40 blocks therefore ends with
     *       the wrap ON the block boundary, which is precisely the case that
     *       leaves the flag SET with the lap's first frame still unscheduled
     *       — see
     *       {@link #wrapLandingOnABlockBoundaryStillFiresTheLoopStartAndTheHalfFrameBeat()},
     *       which proves that without a seek the very next block clicks at
     *       frame 20480.</li>
     *   <li>The seek target {@link #SEEK_PAST_BLOCK_ALIGNED_LOOP_END} then
     *       lands, after the block-entry mapping
     *       {@code loopStart + ((target − loopEnd) mod loopLength)}, half a
     *       frame past the loop start. Half a frame is inside the widening's
     *       {@code < 1.0} residue test, so the residue test ALONE would still
     *       admit the loop-start grid index — the flag is the only thing that
     *       distinguishes "this lap is owed its first click" from "a seek put
     *       us here". The clear is what makes the difference visible.</li>
     * </ul>
     *
     * <p>With the clear the four blocks after the seek are perfectly silent:
     * the walk resumes from grid index 1, and the next grid position (beat
     * 1.0 of the new lap) is roughly 16 383 frames away. Delete the clear and
     * a click sounds at the first frame after the seek.</p>
     */
    @Test
    void seekPastTheLoopEndAfterABlockBoundaryWrapAbandonsTheOwedLoopStartClick() {
        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, LOOP_END_BEATS_BLOCK_ALIGNED_WRAP);
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setSubdivision(Subdivision.QUARTER);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        // 40 blocks = 20480 frames. The last of them ends ON the quantized
        // wrap, leaving the flag set with the new lap's first frame unowned.
        int framesBeforeSeek = 40 * BUFFER_FRAMES;
        float[][] beforeSeek = renderBlocks(engine, framesBeforeSeek);
        assertThat(maxAbs(beforeSeek, 0, 64))
                .as("harness precondition: the fixture really is clicking — lap 0's "
                        + "downbeat must sound at frame 0")
                .isGreaterThan(0.0f);
        assertThat(maxAbs(beforeSeek, SAMPLES_PER_BEAT, SAMPLES_PER_BEAT + 64))
                .as("harness precondition: lap 0's beat-1 click must sound too")
                .isGreaterThan(0.0f);

        // The engine has no backend here, so nothing has claimed the
        // transport's RT clock and the seek applies inline rather than being
        // queued for the next block boundary.
        transport.setPositionInBeats(SEEK_PAST_BLOCK_ALIGNED_LOOP_END);
        assertThat(transport.getPositionInBeats())
                .as("harness precondition: the seek must have applied inline")
                .isEqualTo(SEEK_PAST_BLOCK_ALIGNED_LOOP_END);

        int framesAfterSeek = 4 * BUFFER_FRAMES;
        float[][] afterSeek = renderBlocks(engine, framesAfterSeek);

        for (int f = 0; f < framesAfterSeek; f++) {
            if (afterSeek[0][f] != 0.0f || afterSeek[1][f] != 0.0f) {
                assertThat(Math.max(Math.abs(afterSeek[0][f]), Math.abs(afterSeek[1][f])))
                        .as("frame %d after the seek (global frame %d) is non-silent — "
                                        + "the seek abandoned the lap the previous block's "
                                        + "boundary wrap left owing, so no loop-start click "
                                        + "may be recovered at the landing",
                                f, framesBeforeSeek + f)
                        .isEqualTo(0.0f);
            }
        }
    }

    /**
     * Story 315 review — the click walk's sub-frame guard
     * ({@code loopLength × samplesPerBeat >= 1.0}) must stay enabled for a
     * loop of between one and two frames.
     *
     * <p>{@link #subFrameLoopClicksOnceNotOnEveryFrame()} pins only the lower
     * side of the threshold: it rules out anything at or below 0.3 frame, so
     * 0.5, 1.0, 2.0 and 4.0 all pass it alike. This test pins the upper side.
     * The loop here is exactly 1.5 frames long, so a threshold of 2.0 or 4.0
     * disables the widening on a loop that still needs it and the render goes
     * completely silent.</p>
     *
     * <p>Construction, all quantities exact in binary. At {@link #SAMPLE_RATE}
     * / {@link #TEMPO} one frame is 2⁻¹⁴ beats; the loop is
     * {@link #ONE_AND_A_HALF_FRAME_LOOP_LENGTH} = 3·2⁻¹⁵ beats = 1.5 frames,
     * starting on beat 4.0 — a quarter-note grid position, and bar-aligned in
     * 4/4, so every click generated is the accented one. The transport is
     * parked a QUARTER of a frame into the loop rather than on the loop start
     * itself, which is what makes the test a clean discriminator: the residue
     * orbit is then the exact two-cycle 0.25 → 0.75 → 0.25 frame and never
     * returns to the loop start, so the grid index at beat 4.0 is NEVER
     * admitted by the ordinary {@code ceil()} walk. Every click in the
     * rendered block is one the widening recovered.</p>
     *
     * <p>Segments therefore alternate 2, 1, 2, 1, … frames long and begin at
     * frames 0, 2, 3, 5, 6, … — every frame except {@code 3k + 1}. Each one
     * clicks except the very first, which starts before any wrap has set the
     * flag. Consecutive clicks are one or two frames apart and merge into a
     * single continuous window, so onset counting cannot see them; the
     * assertion is waveform identity against the sum of one accented click
     * per expected onset, accumulated in the same ascending-onset order the
     * scheduler sums them in. The master chain is empty and the router passes
     * the generated click through unmodified, so the comparison can be
     * exact.</p>
     */
    @Test
    void oneAndAHalfFrameLoopKeepsTheGridWideningEnabled() {
        double loopStart = 4.0;
        double loopEnd = loopStart + ONE_AND_A_HALF_FRAME_LOOP_LENGTH;

        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(loopStart, loopEnd);
        // A quarter of a frame into the loop — inline, applied while STOPPED.
        transport.setPositionInBeats(loopStart + QUARTER_FRAME_BEATS);
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setSubdivision(Subdivision.QUARTER);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        float[][] rendered = renderBlocks(engine, BUFFER_FRAMES);

        // Segment starts are 3k and 3k + 2; every one clicks except frame 0,
        // where no wrap has set the flag yet.
        List<Integer> onsets = new ArrayList<>();
        for (int f = 0; f < BUFFER_FRAMES; f++) {
            if (f != 0 && f % 3 != 1) {
                onsets.add(f);
            }
        }
        float[][] accentedClick = metronome.generateClick(true);
        float[][] expected = new float[2][BUFFER_FRAMES];
        for (int onset : onsets) {
            for (int s = 0; s < accentedClick[0].length && onset + s < BUFFER_FRAMES; s++) {
                for (int ch = 0; ch < 2; ch++) {
                    expected[ch][onset + s] += accentedClick[ch][s];
                }
            }
        }

        for (int f = 0; f < BUFFER_FRAMES; f++) {
            for (int ch = 0; ch < 2; ch++) {
                assertThat(rendered[ch][f])
                        .as("channel %d, frame %d — a 1.5-frame loop must keep the grid "
                                        + "widening enabled; a guard that only admits loops "
                                        + "of two frames or more renders pure silence here",
                                ch, f)
                        .isEqualTo(expected[ch][f]);
            }
        }
    }

    /**
     * Story 315 review — the click walk must emit a lap's loop-start click
     * exactly ONCE. The MIDI walk has the identical defect (pinned by
     * {@code AudioEngineMidiPlaybackTest}), but the two walks run on
     * different cursors — the click walk on the raw transport cursor, the
     * content walk on the PDC-shifted one — so each needs its own regression
     * on its own code.
     *
     * <p>The widening that recovers the wrapped-in downbeat asks whether the
     * segment begins inside the sub-frame residue left by a quantized wrap
     * ({@code beatsIntoLoop × samplesPerBeat < 1.0}). That reading cannot
     * distinguish an unrendered lap-first-frame from one the previous block
     * already consumed: for a consumed frame the product evaluates to
     * <b>0.99999999999988990</b> rather than 1.0, so the widening fires a
     * second time and the lap clicks twice, one frame apart.</p>
     *
     * <p>No threshold can separate the two cases, because the two
     * populations OVERLAP — the largest genuine residue sits ABOVE the
     * smallest already-consumed reading:</p>
     * <ul>
     *   <li>largest GENUINE residue, where the widening MUST fire:
     *       <b>0.99999999999994320</b>, at 32768 Hz / 128 BPM
     *       (samplesPerBeat = 15360), loop [0.0, 0.25) beats, 128-frame
     *       blocks;</li>
     *   <li>smallest CONSUMED reading, where the widening MUST NOT fire:
     *       <b>0.99999999999988990</b>, at 48000 Hz / 120 BPM
     *       (samplesPerBeat = 24000), loop [0.0, 0.328125) beats, 64-frame
     *       blocks — the config this test uses.</li>
     * </ul>
     * <p>A cut-off admitting every genuine residue therefore also admits
     * that consumed reading (double click), and one rejecting the consumed
     * reading also rejects a genuine residue (dropped click). The fix
     * consequently carries an explicit wrap flag across the block boundary
     * and conjoins it with the positional test. <i>Provenance: both figures
     * were measured offline by simulating the walk across a sweep of sample
     * rates, tempos, block sizes and loop lengths; they are NOT reproducible
     * from any test in this repo.</i></p>
     *
     * <p>Config: 48 kHz at 120 BPM → samplesPerBeat = 24000 exactly; the loop
     * end 0.328125 = 21/64 is exact in binary → a lap of exactly 7875 frames;
     * quarter-note subdivision, so the loop contains exactly one grid
     * position (beat 0) and owes exactly one click per lap. Lap 21 starts at
     * 21 × 7875 = 165375 and is the lap that doubled.</p>
     *
     * <p><b>Why waveform identity is the only available discriminator.</b>
     * The two clicks of a doubled lap land one frame apart, so they merge
     * into a single click window: onset counting sees one onset, and the
     * silence sweep used by the tests above sees nothing outside a legitimate
     * window. What does change is the SHAPE — a doubled lap renders the sum
     * of two copies of the click offset by one frame, which differs from a
     * single copy at every sample after the first. The assertion therefore
     * compares the rendered frames against
     * {@code metronome.generateClick(true)} sample-for-sample. Accent is
     * {@code true} because grid index 0 is both a main beat and bar-aligned,
     * which is the accent rule the scheduler applies; the comparison can be
     * exact because the master chain is empty (a pure copy) and the router
     * passes the generated click through unmodified, so no arithmetic touches
     * the samples between generation and the output buffer.</p>
     */
    @Test
    void loopStartClickFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed() {
        final double ulpSampleRate = 48_000.0;   // → samplesPerBeat = 24000 at 120 BPM
        final int ulpBlockFrames = 64;
        final double ulpLoopEndBeats = 0.328125; // 21/64 beats = 7875 frames exactly
        final int doubledLapOnset = 165_375;     // lap 21 = 21 × 7875
        final int totalFrames = 2708 * ulpBlockFrames; // 173_312

        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, ulpLoopEndBeats);
        transport.play();

        Metronome metronome = new Metronome(ulpSampleRate, 2);
        metronome.setEnabled(true);
        metronome.setSubdivision(Subdivision.QUARTER);
        // Main-mix click only — no side output, so no backend is needed.
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));

        AudioFormat format = new AudioFormat(ulpSampleRate, 2, 16, ulpBlockFrames);
        AudioEngine engine = new AudioEngine(format);
        engine.setGraph(transport, new Mixer(), List.of());
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());
        engine.start();

        float[][] rendered = new float[2][totalFrames];
        float[][] input = new float[2][ulpBlockFrames];
        float[][] output = new float[2][ulpBlockFrames];
        int done = 0;
        while (done < totalFrames) {
            for (int ch = 0; ch < 2; ch++) {
                java.util.Arrays.fill(output[ch], 0.0f);
            }
            engine.processBlock(input, output, ulpBlockFrames);
            for (int ch = 0; ch < 2; ch++) {
                System.arraycopy(output[ch], 0, rendered[ch], done, ulpBlockFrames);
            }
            done += ulpBlockFrames;
        }

        float[][] expectedClick = metronome.generateClick(true);
        int clickLength = expectedClick[0].length;

        // The COMPLETE onset list, in order, with nothing else anywhere in
        // the render — see the javadoc for how it is derived and for what a
        // count-only or window-only assertion would let through.
        List<Integer> onsets = clickOnsets(rendered, totalFrames, expectedClick);
        assertThat(onsets)
                .as("every frame a click was scheduled on, across all 23 laps — "
                        + "a missing, extra, or one-frame-displaced click changes "
                        + "this list")
                .containsExactly(
                        0, 7875, 15_751, 23_626, 31_501, 39_376, 47_251, 55_126,
                        63_001, 70_876, 78_751, 86_626, 94_501, 102_376, 110_251,
                        118_126, 126_001, 133_876, 141_751, 149_625, 157_500,
                        165_375, 173_250);
        assertThat(onsets)
                .as("one click per lap start and no other: 2708 × 64 = 173312 frames "
                        + "spans 22 wraps, hence 23 lap starts")
                .hasSize(23);

        for (int s = 0; s < clickLength; s++) {
            for (int ch = 0; ch < 2; ch++) {
                assertThat(rendered[ch][doubledLapOnset + s])
                        .as("channel %d, sample %d of the click at frame %d — a lap that "
                                        + "clicked twice renders the sum of two copies one "
                                        + "frame apart, which differs from a single click at "
                                        + "every sample after the first",
                                ch, s, doubledLapOnset)
                        .isEqualTo(expectedClick[ch][s]);
            }
        }
    }

    /**
     * Story 315 review — the sub-frame guard
     * ({@code loopLength × samplesPerBeat >= 1.0}) on the click walk's grid
     * widening. Without it every segment of a sub-frame loop is a fresh wrap,
     * so the widening re-admits the loop-start grid index on every frame and
     * the metronome clicks 1536 times in 1536 frames.
     *
     * <p>Derivation. At {@link #SAMPLE_RATE} / {@link #TEMPO} samplesPerBeat
     * is exactly {@link #SAMPLES_PER_BEAT}, so one frame is 2⁻¹⁴ beats. The
     * loop starts on beat 4.0 — a quarter-note grid position, and with 4/4
     * an accented one — and is 0.3 frame long, deliberately NOT an exact
     * binary fraction. Every segment is therefore clamped to a single frame
     * ({@code max(1, ceil(0.3 − …))}) and each one wraps, the modulo mapping
     * the cursor to {@code loopStart + ((r + 1) mod 0.3)} frames. That
     * residue orbit never returns to the loop start in floating point, so
     * only the very first segment — whose cursor IS beat 4.0 — admits the
     * grid index through the ordinary {@code ceil()} walk. The guard blocks
     * the widening on all 1535 later frames.</p>
     *
     * <p>The assertion is waveform identity against a single generated
     * click, which is what "exactly one click" means here: onset counting
     * cannot see the difference, because clicks a frame apart merge into one
     * continuous window. Everything after the click body must be pure
     * silence.</p>
     *
     * <p>A loop length that DIVIDES a frame is a different, pre-existing
     * story and this test deliberately does not cover it: with 0.25 frame the
     * modulo lands the cursor back exactly ON the loop start every frame, so
     * the ordinary walk admits the grid index 1536 times with the guard AND
     * without it. See the comment on the widening.</p>
     */
    @Test
    void subFrameLoopClicksOnceNotOnEveryFrame() {
        // 0.3 frame — not an exact binary fraction, so the wrap residue drifts.
        double loopStart = 4.0;
        double loopEnd = loopStart + 0.3 / SAMPLES_PER_BEAT;

        Transport transport = new Transport();
        transport.setTempo(TEMPO);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(true);
        transport.setLoopRegion(loopStart, loopEnd);
        transport.setPositionInBeats(loopStart); // inline: applied while STOPPED
        transport.play();

        Metronome metronome = new Metronome(SAMPLE_RATE, 2);
        metronome.setEnabled(true);
        metronome.setSubdivision(Subdivision.QUARTER);
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));
        AudioEngine engine = startedEngine(transport, metronome);

        int totalFrames = 3 * BUFFER_FRAMES; // 1536
        float[][] rendered = renderBlocks(engine, totalFrames);

        float[][] expectedClick = metronome.generateClick(true);
        int clickLength = expectedClick[0].length;
        for (int f = 0; f < totalFrames; f++) {
            for (int ch = 0; ch < 2; ch++) {
                float expected = f < clickLength ? expectedClick[ch][f] : 0.0f;
                assertThat(rendered[ch][f])
                        .as("channel %d, frame %d — a sub-frame loop must click exactly "
                                        + "once, at frame 0; a click started on every frame "
                                        + "renders the running sum of 1536 copies instead",
                                ch, f)
                        .isEqualTo(expected);
            }
        }
    }

    /**
     * Story 315 review (third round) — the click walk's half-frame CARRY,
     * pinned with LOOPING DISABLED so it is independent of every piece of the
     * loop machinery above. The MIDI walk has the same discriminator in
     * {@code AudioEngineMidiPlaybackTest}; the two walks are required to read
     * alike, and this is the fixture that says so for clicks.
     *
     * <p>The defect. Click admission is a test in BEATS
     * ({@code [gridStartBeat, schedulingEndBeat)}) while
     * {@code scheduleSegmentClicks} maps in FRAMES, with
     * {@code round((beatPos − segStartBeat) × samplesPerBeat)}. A grid
     * position in the last half frame of a segment was admitted by that
     * segment and then rounded to a frame the segment cannot address, so the
     * clamp pulled it back to the segment's last frame — one sample early,
     * systematically — or, on the block's last segment, the block bound
     * dropped it outright. The window is now the segment's FRAME-OWNERSHIP
     * interval, so the position is declined here and collected by the next
     * segment, which sounds it at its own frame 0.</p>
     *
     * <p>Construction. 44.1 kHz at 68 BPM gives
     * {@code samplesPerBeat = 2646000 / 68 = 38911.7647…}, deliberately NOT a
     * whole number of frames, so beat 1.0 lands at frame <b>38911.76</b> —
     * 0.24 frame before block 76's boundary at {@code 76 × 512 = 38912}.
     * Nearest-frame rounding puts it on 38912, which is block 76's frame 0,
     * not block 75's last frame. Before the carry the click sounded at
     * <b>38911</b>; it must now sound at <b>38912</b>. Eighty blocks
     * (40 960 frames) covers beat 1 with room to spare and stops well short
     * of beat 2 at frame 77 823.5, so the complete onset census is
     * {@code [0, 38912]}.</p>
     *
     * <p>An "exactly on a block boundary" grid position deliberately has no
     * fixture: {@code lastIdxExclusive = ceil(schedulingEndBeat ×
     * clicksPerBeat − 1e-9)} already makes the right bound exclusive for an
     * exactly-aligned index, so that case was correct before this change and
     * would not discriminate.</p>
     */
    @Test
    void gridPositionInTheLastHalfFrameOfABlockSoundsOnTheNextBlocksFirstFrame() {
        final double carrySampleRate = 44_100.0;
        final double carryTempo = 68.0;              // → samplesPerBeat = 38911.7647…
        final int carryBlockFrames = 512;
        final int carryOnset = 38_912;               // 76 × 512, the frame beat 1.0 rounds to
        final int totalFrames = 80 * carryBlockFrames; // 40_960

        Transport transport = new Transport();
        transport.setTempo(carryTempo);
        transport.setTimeSignature(4, 4);
        transport.setLoopEnabled(false); // explicit: this fixture is loop-free
        transport.play();

        Metronome metronome = new Metronome(carrySampleRate, 2);
        metronome.setEnabled(true);
        metronome.setSubdivision(Subdivision.QUARTER);
        // Main-mix click only — no side output, so no backend is needed.
        metronome.setClickOutput(new ClickOutput(0, 1.0, true, false));

        AudioFormat format = new AudioFormat(carrySampleRate, 2, 16, carryBlockFrames);
        AudioEngine engine = new AudioEngine(format);
        engine.setGraph(transport, new Mixer(), List.of());
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());
        engine.start();

        float[][] rendered = new float[2][totalFrames];
        float[][] input = new float[2][carryBlockFrames];
        float[][] output = new float[2][carryBlockFrames];
        int done = 0;
        while (done < totalFrames) {
            for (int ch = 0; ch < 2; ch++) {
                java.util.Arrays.fill(output[ch], 0.0f);
            }
            engine.processBlock(input, output, carryBlockFrames);
            for (int ch = 0; ch < 2; ch++) {
                System.arraycopy(output[ch], 0, rendered[ch], done, carryBlockFrames);
            }
            done += carryBlockFrames;
        }

        float[][] accentedClick = metronome.generateClick(true);
        int clickLength = Math.max(accentedClick[0].length,
                metronome.generateClick(false)[0].length);

        List<Integer> onsets = clickOnsets(rendered, totalFrames, accentedClick);
        assertThat(onsets)
                .as("beat 1.0 sits at frame 38911.76, whose nearest frame is %d — the "
                        + "first frame of the NEXT block. A window ending on this "
                        + "block's cursor end admits it here and the clamp drags it "
                        + "back to 38911; the frame-ownership window declines it and "
                        + "the next block sounds it on time", carryOnset)
                .containsExactly(0, carryOnset);
        assertNoEnergyOutsideClickWindows(rendered, onsets, clickLength, totalFrames);
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

    /**
     * Every frame a click was SCHEDULED on, in order, recovered from the
     * rendered audio as a rising edge out of silence.
     *
     * <p>{@code Metronome.generateClick} begins with one sample of silence,
     * so the first non-silent frame of a click sits one frame after the frame
     * it was scheduled on. The lead-in is measured off {@code clickShape}
     * rather than hard-coded, and subtracted, so the returned frames are the
     * scheduler's own — directly comparable with the loop arithmetic.</p>
     *
     * <p>This is a complete census: any click the walk drops, adds, or moves
     * by a frame changes the list, provided consecutive clicks are separated
     * by genuine silence (they are — the smallest lap here is 7875 frames
     * against a 960-frame click body). Clicks closer together than the click
     * body merge into one window and are NOT separable this way; that case is
     * what the waveform-identity assertions are for.</p>
     */
    private static List<Integer> clickOnsets(float[][] rendered, int totalFrames,
                                             float[][] clickShape) {
        int leadIn = 0;
        while (leadIn < clickShape[0].length
                && clickShape[0][leadIn] == 0.0f
                && clickShape[1][leadIn] == 0.0f) {
            leadIn++;
        }
        List<Integer> onsets = new ArrayList<>();
        boolean sounding = false;
        for (int f = 0; f < totalFrames; f++) {
            boolean nonSilent = rendered[0][f] != 0.0f || rendered[1][f] != 0.0f;
            if (nonSilent && !sounding) {
                onsets.add(f - leadIn);
            }
            sounding = nonSilent;
        }
        return onsets;
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
