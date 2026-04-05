package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.FoldDownCoefficients;
import com.benesquivelmusic.daw.sdk.spatial.MonitoringFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FoldDownMonitorControllerTest {

    private static final int NUM_FRAMES = 64;
    private FoldDownMonitorController controller;

    @BeforeEach
    void setUp() {
        controller = new FoldDownMonitorController(MonitoringFormat.IMMERSIVE_7_1_4);
    }

    // ---- Constructor --------------------------------------------------------

    @Test
    void shouldRejectNullSourceFormat() {
        assertThatThrownBy(() -> new FoldDownMonitorController(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Default state ------------------------------------------------------

    @Test
    void shouldDefaultToSourceFormat() {
        assertThat(controller.getMonitoringFormat()).isEqualTo(MonitoringFormat.IMMERSIVE_7_1_4);
    }

    @Test
    void shouldDefaultToItuCoefficients() {
        assertThat(controller.getCoefficients()).isEqualTo(FoldDownCoefficients.ITU_R_BS_775);
    }

    @Test
    void shouldDefaultToNonCustomCoefficients() {
        assertThat(controller.isCustomCoefficients()).isFalse();
    }

    @Test
    void shouldReturnSourceFormat() {
        assertThat(controller.getSourceFormat()).isEqualTo(MonitoringFormat.IMMERSIVE_7_1_4);
    }

    @Test
    void shouldDisplaySourceFormatName() {
        assertThat(controller.getMonitoringFormatDisplayName()).isEqualTo("7.1.4");
    }

    // ---- Format selection ---------------------------------------------------

    @Test
    void shouldSwitchToStereo() {
        controller.setMonitoringFormat(MonitoringFormat.STEREO);
        assertThat(controller.getMonitoringFormat()).isEqualTo(MonitoringFormat.STEREO);
        assertThat(controller.getMonitoringFormatDisplayName()).isEqualTo("Stereo");
    }

    @Test
    void shouldSwitchToMono() {
        controller.setMonitoringFormat(MonitoringFormat.MONO);
        assertThat(controller.getMonitoringFormat()).isEqualTo(MonitoringFormat.MONO);
        assertThat(controller.getMonitoringFormatDisplayName()).isEqualTo("Mono");
    }

    @Test
    void shouldSwitchTo51() {
        controller.setMonitoringFormat(MonitoringFormat.SURROUND_5_1);
        assertThat(controller.getMonitoringFormat()).isEqualTo(MonitoringFormat.SURROUND_5_1);
        assertThat(controller.getMonitoringFormatDisplayName()).isEqualTo("5.1");
    }

    @Test
    void shouldSwitchBackToSourceFormat() {
        controller.setMonitoringFormat(MonitoringFormat.STEREO);
        controller.setMonitoringFormat(MonitoringFormat.IMMERSIVE_7_1_4);
        assertThat(controller.getMonitoringFormat()).isEqualTo(MonitoringFormat.IMMERSIVE_7_1_4);
    }

    @Test
    void shouldRejectNullFormat() {
        assertThatThrownBy(() -> controller.setMonitoringFormat(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectFormatWithMoreChannelsThanSource() {
        FoldDownMonitorController stereoController =
                new FoldDownMonitorController(MonitoringFormat.STEREO);

        assertThatThrownBy(() -> stereoController.setMonitoringFormat(MonitoringFormat.SURROUND_5_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5.1");
    }

    // ---- Available formats --------------------------------------------------

    @Test
    void shouldListAllFormatsForImmersiveSource() {
        List<MonitoringFormat> available = controller.getAvailableFormats();
        assertThat(available).containsExactly(
                MonitoringFormat.IMMERSIVE_7_1_4,
                MonitoringFormat.SURROUND_5_1,
                MonitoringFormat.STEREO,
                MonitoringFormat.MONO);
    }

    @Test
    void shouldListOnlyStereoAndMonoForStereoSource() {
        FoldDownMonitorController stereoController =
                new FoldDownMonitorController(MonitoringFormat.STEREO);
        List<MonitoringFormat> available = stereoController.getAvailableFormats();
        assertThat(available).containsExactly(MonitoringFormat.STEREO, MonitoringFormat.MONO);
    }

    @Test
    void shouldListOnlyMonoForMonoSource() {
        FoldDownMonitorController monoController =
                new FoldDownMonitorController(MonitoringFormat.MONO);
        List<MonitoringFormat> available = monoController.getAvailableFormats();
        assertThat(available).containsExactly(MonitoringFormat.MONO);
    }

    // ---- Custom coefficients ------------------------------------------------

    @Test
    void shouldSetCustomCoefficients() {
        FoldDownCoefficients custom = new FoldDownCoefficients(0.5, 0.6, 0.1, 0.8);
        controller.setCoefficients(custom);
        assertThat(controller.getCoefficients()).isEqualTo(custom);
        assertThat(controller.isCustomCoefficients()).isTrue();
    }

    @Test
    void shouldRestoreDefaultCoefficients() {
        controller.setCoefficients(new FoldDownCoefficients(0.5, 0.6, 0.1, 0.8));
        controller.setCoefficients(FoldDownCoefficients.ITU_R_BS_775);
        assertThat(controller.isCustomCoefficients()).isFalse();
    }

    @Test
    void shouldRejectNullCoefficients() {
        assertThatThrownBy(() -> controller.setCoefficients(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Audio processing: pass-through -------------------------------------

    @Test
    void shouldPassThroughWhenMonitoringSourceFormat() {
        float[][] input = createInput(12, 1.0f);
        float[][] result = controller.process(input, NUM_FRAMES);

        assertThat(result).isSameAs(input);
    }

    // ---- Audio processing: fold-down to 5.1 ---------------------------------

    @Test
    void shouldFoldDown714To51() {
        controller.setMonitoringFormat(MonitoringFormat.SURROUND_5_1);

        float[][] input = createInput(12, 0.0f);
        fillChannel(input[0], 1.0f); // L
        fillChannel(input[1], 1.0f); // R

        float[][] result = controller.process(input, NUM_FRAMES);

        assertThat(result.length).isEqualTo(6);
        assertThat((double) result[0][0]).isCloseTo(1.0, offset(0.001));
        assertThat((double) result[1][0]).isCloseTo(1.0, offset(0.001));
    }

    // ---- Audio processing: fold-down to stereo ------------------------------

    @Test
    void shouldFoldDown714ToStereo() {
        controller.setMonitoringFormat(MonitoringFormat.STEREO);

        float[][] input = createInput(12, 0.0f);
        fillChannel(input[0], 1.0f); // L
        fillChannel(input[1], 1.0f); // R
        fillChannel(input[2], 1.0f); // C

        float[][] result = controller.process(input, NUM_FRAMES);

        assertThat(result.length).isEqualTo(2);
        // L = L + 0.707*C = 1.0 + 0.707 ≈ 1.707
        assertThat((double) result[0][0]).isCloseTo(1.0 + Math.sqrt(0.5), offset(0.01));
    }

    // ---- Audio processing: fold-down to mono --------------------------------

    @Test
    void shouldFoldDown714ToMono() {
        controller.setMonitoringFormat(MonitoringFormat.MONO);

        float[][] input = createInput(12, 0.0f);
        fillChannel(input[0], 1.0f); // L
        fillChannel(input[1], 1.0f); // R

        float[][] result = controller.process(input, NUM_FRAMES);

        assertThat(result.length).isEqualTo(1);
        // After folding to stereo (L=1, R=1), mono = 0.707 * (1+1) ≈ 1.414
        assertThat((double) result[0][0]).isCloseTo(Math.sqrt(0.5) * 2.0, offset(0.01));
    }

    // ---- Audio processing: custom coefficients ------------------------------

    @Test
    void shouldApplyCustomCoefficientsInFoldDown() {
        controller.setMonitoringFormat(MonitoringFormat.STEREO);
        FoldDownCoefficients custom = new FoldDownCoefficients(0.5, 0.5, 0.0, 0.5);
        controller.setCoefficients(custom);

        float[][] input = createInput(12, 0.0f);
        fillChannel(input[0], 1.0f); // L
        fillChannel(input[2], 1.0f); // C

        float[][] result = controller.process(input, NUM_FRAMES);

        assertThat(result.length).isEqualTo(2);
        // L = L + 0.5*C = 1.0 + 0.5 = 1.5 (custom center coefficient)
        assertThat((double) result[0][0]).isCloseTo(1.5, offset(0.01));
    }

    @Test
    void shouldIncludeLfeWhenCustomCoefficientsAllow() {
        controller.setMonitoringFormat(MonitoringFormat.STEREO);
        FoldDownCoefficients customWithLfe = new FoldDownCoefficients(
                Math.sqrt(0.5), Math.sqrt(0.5), 0.5, Math.sqrt(0.5));
        controller.setCoefficients(customWithLfe);

        float[][] input = createInput(12, 0.0f);
        fillChannel(input[3], 1.0f); // LFE only

        float[][] result = controller.process(input, NUM_FRAMES);

        assertThat(result.length).isEqualTo(2);
        // LFE should contribute to both L and R with 0.5 coefficient
        assertThat((double) result[0][0]).isCloseTo(0.5, offset(0.01));
        assertThat((double) result[1][0]).isCloseTo(0.5, offset(0.01));
    }

    // ---- Stereo source: fold-down to mono -----------------------------------

    @Test
    void shouldFoldStereoToMono() {
        FoldDownMonitorController stereoController =
                new FoldDownMonitorController(MonitoringFormat.STEREO);
        stereoController.setMonitoringFormat(MonitoringFormat.MONO);

        float[][] input = createInput(2, 0.0f);
        fillChannel(input[0], 0.8f);
        fillChannel(input[1], 0.4f);

        float[][] result = stereoController.process(input, NUM_FRAMES);

        assertThat(result.length).isEqualTo(1);
        double expected = Math.sqrt(0.5) * (0.8 + 0.4);
        assertThat((double) result[0][0]).isCloseTo(expected, offset(0.001));
    }

    // ---- Helpers ------------------------------------------------------------

    private static float[][] createInput(int channels, float value) {
        float[][] buffer = new float[channels][NUM_FRAMES];
        for (float[] channel : buffer) {
            Arrays.fill(channel, value);
        }
        return buffer;
    }

    private static void fillChannel(float[] channel, float value) {
        Arrays.fill(channel, value);
    }
}
