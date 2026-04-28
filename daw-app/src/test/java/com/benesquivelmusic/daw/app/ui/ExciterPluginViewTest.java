package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-UI-thread tests for {@link ExciterPluginView}.
 *
 * <p>Mirrors {@link DeEsserPluginViewTest}: avoids instantiating the
 * {@code VBox} (which would require the JavaFX toolkit), and instead exercises
 * compile-time constants and pure-data invariants of the view.</p>
 */
class ExciterPluginViewTest {

    @Test
    void shouldShowAtLeastFundamentalAndSecondAndThirdHarmonicBins() {
        // The static FFT display must show at least the fundamental + 2nd
        // and 3rd harmonics (the harmonics emphasised by the issue).
        assertThat(ExciterPluginView.FFT_DISPLAY_HARMONICS).isGreaterThanOrEqualTo(3);
    }

    @Test
    void harmonicMagnitudesShouldHaveCorrectLength() {
        for (ExciterProcessor.Mode mode : ExciterProcessor.Mode.values()) {
            assertThat(ExciterPluginView.harmonicMagnitudes(mode))
                    .as("magnitudes for mode %s", mode)
                    .hasSize(ExciterPluginView.FFT_DISPLAY_HARMONICS);
        }
    }

    @Test
    void classATubeShouldEmphasizeSecondHarmonic() {
        double[] m = ExciterPluginView.harmonicMagnitudes(ExciterProcessor.Mode.CLASS_A_TUBE);
        // 2nd harmonic (index 1) > 3rd harmonic (index 2).
        assertThat(m[1]).isGreaterThan(m[2]);
    }

    @Test
    void transformerShouldEmphasizeThirdHarmonic() {
        double[] m = ExciterPluginView.harmonicMagnitudes(ExciterProcessor.Mode.TRANSFORMER);
        // 3rd harmonic (index 2) > 2nd harmonic (index 1).
        assertThat(m[2]).isGreaterThan(m[1]);
    }

    @Test
    void fundamentalShouldBeNormalizedToUnity() {
        for (ExciterProcessor.Mode mode : ExciterProcessor.Mode.values()) {
            assertThat(ExciterPluginView.harmonicMagnitudes(mode)[0])
                    .as("fundamental for mode %s", mode)
                    .isEqualTo(1.0);
        }
    }
}
