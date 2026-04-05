package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FoldDownRendererTest {

    private static final int NUM_SAMPLES = 64;

    // ---- 7.1.4 → 7.1 ----

    @Test
    void shouldFold714To71ByMixingHeightIntoEarLevel() {
        float[][] input = new float[12][NUM_SAMPLES];
        // Set left height front to constant 1.0
        fillChannel(input[8], 1.0f); // LTF
        // Set left front to constant 0.5
        fillChannel(input[0], 0.5f); // L

        float[][] result = FoldDownRenderer.foldTo71(input, NUM_SAMPLES);

        assertThat(result).hasNumberOfRows(8);
        // L should be L + LTF * −3dB = 0.5 + 1.0 * 0.707 ≈ 1.207
        double expected = 0.5 + Math.sqrt(0.5);
        assertThat((double) result[0][0]).isCloseTo(expected, offset(0.001));
    }

    @Test
    void shouldPreserveCenterAndLfeInFoldTo71() {
        float[][] input = new float[12][NUM_SAMPLES];
        fillChannel(input[2], 0.8f); // C
        fillChannel(input[3], 0.6f); // LFE

        float[][] result = FoldDownRenderer.foldTo71(input, NUM_SAMPLES);

        assertThat((double) result[2][0]).isCloseTo(0.8, offset(0.001));
        assertThat((double) result[3][0]).isCloseTo(0.6, offset(0.001));
    }

    // ---- 7.1 → 5.1 ----

    @Test
    void shouldFold71To51ByMixingRearIntoSide() {
        float[][] input = new float[8][NUM_SAMPLES];
        fillChannel(input[4], 0.5f); // LS
        fillChannel(input[6], 1.0f); // LRS

        float[][] result = FoldDownRenderer.foldTo51(input, NUM_SAMPLES);

        assertThat(result).hasNumberOfRows(6);
        // LS output = LS + LRS * −3dB = 0.5 + 0.707
        double expected = 0.5 + Math.sqrt(0.5);
        assertThat((double) result[4][0]).isCloseTo(expected, offset(0.001));
    }

    // ---- 5.1 → Stereo ----

    @Test
    void shouldFold51ToStereo() {
        float[][] input = new float[6][NUM_SAMPLES];
        fillChannel(input[0], 1.0f); // L
        fillChannel(input[1], 1.0f); // R
        fillChannel(input[2], 1.0f); // C
        fillChannel(input[4], 1.0f); // LS
        fillChannel(input[5], 1.0f); // RS

        float[][] result = FoldDownRenderer.foldToStereo(input, NUM_SAMPLES);

        assertThat(result).hasNumberOfRows(2);
        // Left = L + 0.707*C + 0.707*LS = 1.0 + 0.707 + 0.707 ≈ 2.414
        double expectedLeft = 1.0 + 2 * Math.sqrt(0.5);
        assertThat((double) result[0][0]).isCloseTo(expectedLeft, offset(0.001));
    }

    @Test
    void shouldDiscardLfeInStereoFoldDown() {
        float[][] input = new float[6][NUM_SAMPLES];
        fillChannel(input[3], 1.0f); // LFE only

        float[][] result = FoldDownRenderer.foldToStereo(input, NUM_SAMPLES);

        // LFE should not appear in stereo
        assertThat((double) result[0][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) result[1][0]).isCloseTo(0.0, offset(0.001));
    }

    // ---- Stereo → Mono ----

    @Test
    void shouldFoldStereoToMono() {
        float[][] input = new float[2][NUM_SAMPLES];
        fillChannel(input[0], 0.8f); // L
        fillChannel(input[1], 0.4f); // R

        float[][] result = FoldDownRenderer.foldToMono(input, NUM_SAMPLES);

        assertThat(result).hasNumberOfRows(1);
        double expected = Math.sqrt(0.5) * (0.8 + 0.4);
        assertThat((double) result[0][0]).isCloseTo(expected, offset(0.001));
    }

    // ---- Full chain ----

    @Test
    void shouldFoldDown714ToStereo() {
        float[][] input = new float[12][NUM_SAMPLES];
        fillChannel(input[0], 1.0f); // L
        fillChannel(input[1], 1.0f); // R

        float[][] result = FoldDownRenderer.foldDown(input, SpeakerLayout.LAYOUT_STEREO, NUM_SAMPLES);

        assertThat(result).hasNumberOfRows(2);
        // L channel should survive the fold-down chain
        assertThat((double) result[0][0]).isGreaterThan(0.0);
    }

    @Test
    void shouldFoldDown714ToMono() {
        float[][] input = new float[12][NUM_SAMPLES];
        fillChannel(input[2], 1.0f); // C

        float[][] result = FoldDownRenderer.foldDown(input, new SpeakerLayout("Mono",
                java.util.List.of(com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel.C)), NUM_SAMPLES);

        assertThat(result).hasNumberOfRows(1);
        assertThat((double) result[0][0]).isGreaterThan(0.0);
    }

    // ---- Validation ----

    @Test
    void shouldRejectWrongChannelCount() {
        float[][] input = new float[4][NUM_SAMPLES];
        assertThatThrownBy(() -> FoldDownRenderer.foldTo71(input, NUM_SAMPLES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
    }

    private static void fillChannel(float[] channel, float value) {
        java.util.Arrays.fill(channel, value);
    }
}
