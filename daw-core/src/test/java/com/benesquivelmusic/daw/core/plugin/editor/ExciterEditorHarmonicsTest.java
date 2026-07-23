package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-math tests for {@link ExciterEditor}'s static harmonic-sketch model,
 * ported from the retired daw-app {@code ExciterPluginViewTest} (story 302
 * §8.3). Exercises only the static magnitude vector — no JavaFX toolkit, no
 * control or canvas construction.
 */
class ExciterEditorHarmonicsTest {

    @Test
    void shouldShowAtLeastFundamentalAndSecondAndThirdHarmonicBins() {
        // The static FFT display must show at least the fundamental + 2nd
        // and 3rd harmonics (the harmonics emphasised by the exciter modes).
        assertThat(ExciterEditor.FFT_DISPLAY_HARMONICS).isGreaterThanOrEqualTo(3);
    }

    @Test
    void harmonicMagnitudesShouldHaveCorrectLength() {
        for (ExciterProcessor.Mode mode : ExciterProcessor.Mode.values()) {
            assertThat(ExciterEditor.harmonicMagnitudes(mode))
                    .as("magnitudes for mode %s", mode)
                    .hasSize(ExciterEditor.FFT_DISPLAY_HARMONICS);
        }
    }

    @Test
    void classATubeShouldEmphasizeSecondHarmonic() {
        double[] m = ExciterEditor.harmonicMagnitudes(ExciterProcessor.Mode.CLASS_A_TUBE);
        // 2nd harmonic (index 1) > 3rd harmonic (index 2).
        assertThat(m[1]).isGreaterThan(m[2]);
    }

    @Test
    void transformerShouldEmphasizeThirdHarmonic() {
        double[] m = ExciterEditor.harmonicMagnitudes(ExciterProcessor.Mode.TRANSFORMER);
        // 3rd harmonic (index 2) > 2nd harmonic (index 1).
        assertThat(m[2]).isGreaterThan(m[1]);
    }

    @Test
    void fundamentalShouldBeNormalizedToUnity() {
        for (ExciterProcessor.Mode mode : ExciterProcessor.Mode.values()) {
            assertThat(ExciterEditor.harmonicMagnitudes(mode)[0])
                    .as("fundamental for mode %s", mode)
                    .isEqualTo(1.0);
        }
    }
}
