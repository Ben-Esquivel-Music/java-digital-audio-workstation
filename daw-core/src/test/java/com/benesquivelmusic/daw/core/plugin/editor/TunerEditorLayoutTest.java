package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-math tests for {@link TunerEditor}'s static cents-to-x deviation-bar
 * mapping (story 302 §8.3 — the editor replacing the daw-app
 * {@code TunerDisplayWindow} wrapper). Exercises only the package-private
 * static — no JavaFX toolkit, no control or canvas construction.
 */
class TunerEditorLayoutTest {

    private static final double WIDTH = 260.0;

    @Test
    void centsRangeShouldBeFiftyCents() {
        assertThat(TunerEditor.CENTS_RANGE).isEqualTo(50.0);
    }

    @Test
    void zeroCentsShouldMapToCentre() {
        assertThat(TunerEditor.centsToX(0.0, WIDTH)).isCloseTo(WIDTH / 2.0, within(1e-9));
    }

    @Test
    void fullScaleFlatShouldMapToLeftEdge() {
        assertThat(TunerEditor.centsToX(-50.0, WIDTH)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void fullScaleSharpShouldMapToRightEdge() {
        assertThat(TunerEditor.centsToX(50.0, WIDTH)).isCloseTo(WIDTH, within(1e-9));
    }

    @Test
    void beyondRangeShouldClampToEdges() {
        assertThat(TunerEditor.centsToX(-80.0, WIDTH)).isCloseTo(0.0, within(1e-9));
        assertThat(TunerEditor.centsToX(120.0, WIDTH)).isCloseTo(WIDTH, within(1e-9));
    }

    @Test
    void mappingShouldIncreaseMonotonicallyWithCents() {
        double flat = TunerEditor.centsToX(-10.0, WIDTH);
        double centre = TunerEditor.centsToX(0.0, WIDTH);
        double sharp = TunerEditor.centsToX(10.0, WIDTH);
        assertThat(flat).isLessThan(centre);
        assertThat(centre).isLessThan(sharp);
    }
}
