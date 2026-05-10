package com.benesquivelmusic.daw.app.longtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;

/**
 * Long-running end-to-end render test (story 209): synthesise five
 * tracks of stereo audio, sum them into a master, and export to WAV.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>The {@link WavExporter} pipeline produces a non-empty file at
 *       the documented size for the given sample count, channel count
 *       and bit depth.</li>
 *   <li>The output bytes are bit-identical to the SHA-256 golden under
 *       {@code daw-app/src/test/long/resources/golden/}.</li>
 *   <li>The {@link LongTestHarness} budget gate is honoured — the
 *       harness fails the test if it runs slower than {@code 2 ×}
 *       {@link LongRenderTest#budgetSeconds()}.</li>
 * </ul>
 */
@LongRenderTest(
        budgetSeconds = 30.0,
        description = "5-track full master render → WAV golden")
final class FullRenderLongTest {

    private static final int   SAMPLE_RATE = 48_000;
    private static final int   BIT_DEPTH   = 24;
    /** 4 seconds of audio is enough to exercise the chunked write loop. */
    private static final int   FRAMES      = SAMPLE_RATE * 4;
    private static final int   TRACKS      = 5;
    /** Pre-mix gain — keep the sum well below clip to avoid wrap-around. */
    private static final float MIX_GAIN    = 1.0f / TRACKS;

    @Test
    void rendersFiveTrackMasterMatchingGolden(Path workDir) throws Exception {
        // 1. Synthesise 5 tracks: 4× sines at musically-related frequencies
        //    plus one deterministic noise track. Different per-track seeds
        //    keep the master non-trivial without introducing nondeterminism.
        float[][] tracks = new float[TRACKS][];
        tracks[0] = LongTestSupport.sine(220.0,  0.5, SAMPLE_RATE, FRAMES);
        tracks[1] = LongTestSupport.sine(330.0,  0.4, SAMPLE_RATE, FRAMES);
        tracks[2] = LongTestSupport.sine(440.0,  0.3, SAMPLE_RATE, FRAMES);
        tracks[3] = LongTestSupport.sine(660.0,  0.2, SAMPLE_RATE, FRAMES);
        tracks[4] = LongTestSupport.noise(42L,   0.2,              FRAMES);

        // 2. Sum to a stereo master with a tiny inter-channel offset so
        //    L != R (representative of a real mix).
        float[] left  = new float[FRAMES];
        float[] right = new float[FRAMES];
        for (int n = 0; n < FRAMES; n++) {
            float sum = 0f;
            for (int t = 0; t < TRACKS; t++) sum += tracks[t][n];
            left[n]  = sum * MIX_GAIN;
            right[n] = sum * MIX_GAIN * 0.95f;
        }

        // 3. Render to WAV via the real exporter.
        Path master = workDir.resolve("master.wav");
        WavExporter.write(new float[][]{left, right},
                SAMPLE_RATE, BIT_DEPTH, DitherType.NONE,
                AudioMetadata.EMPTY, master);

        // 4. Sanity: the file exists and is at least the size of a fully
        //    populated PCM payload (header + interleaved 24-bit samples).
        long minSize = (long) FRAMES * 2 * (BIT_DEPTH / 8);
        assertThat(Files.size(master)).isGreaterThanOrEqualTo(minSize);

        // 5. Bit-accuracy check against the on-disk golden.
        LongTestSupport.assertMatchesGolden(master, "full-render-5track-master.wav");
    }
}
