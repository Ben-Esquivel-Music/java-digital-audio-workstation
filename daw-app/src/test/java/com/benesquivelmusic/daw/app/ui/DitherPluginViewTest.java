package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link DitherPluginView}.
 *
 * <p>Mirrors {@link TruePeakLimiterPluginViewTest}: avoids instantiating the
 * {@code VBox} (which would require the JavaFX toolkit), and instead exercises
 * compile-time constants and pure-static logic of the view.</p>
 */
class DitherPluginViewTest {

    @Test
    void bitDepthsShouldExposeSixteenTwentyTwentyFour() {
        assertThat(DitherPluginView.BIT_DEPTHS).containsExactly(16, 20, 24);
    }

    @Test
    void snapBitDepthShouldRoundDownToSupportedValues() {
        assertThat(DitherPluginView.snapBitDepth(16)).isEqualTo(16);
        assertThat(DitherPluginView.snapBitDepth(18)).isEqualTo(16);
        assertThat(DitherPluginView.snapBitDepth(20)).isEqualTo(20);
        assertThat(DitherPluginView.snapBitDepth(22)).isEqualTo(20);
        assertThat(DitherPluginView.snapBitDepth(24)).isEqualTo(24);
        assertThat(DitherPluginView.snapBitDepth(32)).isEqualTo(24);
    }

    @Test
    void describeShouldReturnNonEmptyTextForEveryTypeShapeCombination() {
        for (DitherProcessor.DitherType t : DitherProcessor.DitherType.values()) {
            for (DitherProcessor.NoiseShape s : DitherProcessor.NoiseShape.values()) {
                String desc = DitherPluginView.describe(t, s);
                assertThat(desc)
                        .as("description for type=%s shape=%s", t, s)
                        .isNotBlank();
            }
        }
    }

    @Test
    void describeShouldMentionShapeOnlyForNoiseShapedType() {
        // Non-shaped types: shape choice is irrelevant — description should
        // not mention the shape name.
        String tpdfPowr3 = DitherPluginView.describe(
                DitherProcessor.DitherType.TPDF, DitherProcessor.NoiseShape.POWR_3);
        assertThat(tpdfPowr3).doesNotContain("POW-r");

        String shapedPowr3 = DitherPluginView.describe(
                DitherProcessor.DitherType.NOISE_SHAPED, DitherProcessor.NoiseShape.POWR_3);
        assertThat(shapedPowr3).contains("POW-r");
    }
}
