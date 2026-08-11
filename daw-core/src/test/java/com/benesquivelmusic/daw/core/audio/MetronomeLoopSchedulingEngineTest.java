package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
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
