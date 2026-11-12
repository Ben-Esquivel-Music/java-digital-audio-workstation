package com.benesquivelmusic.daw.app.ui.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 335 (studio ruling R6; amendments A29 and ruling 2) — the canonical
 * UI-scale steps: the labels, the setter guard ({@link UiScale#ofFactor}) and
 * the load-time snap ({@link UiScale#nearest}).
 */
class UiScaleTest {

    @Test
    void theSevenStepsAndTheirLabelsAreTheRuledOnes() {
        assertThat(UiScale.factors()).containsExactly(0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0);
        assertThat(UiScale.values()).extracting(UiScale::label)
                .containsExactly("90%", "100%", "110%", "125%", "150%", "175%", "200%");
        assertThat(UiScale.DEFAULT).isEqualTo(UiScale.P100);
        assertThat(UiScale.P150.scale(11)).isEqualTo(16.5);
    }

    @Test
    void ofFactorIsTheSetterGuardAndAcceptsOnlyExactSteps() {
        for (UiScale step : UiScale.values()) {
            assertThat(UiScale.ofFactor(step.factor())).isEqualTo(step);
        }
        for (double refused : new double[] {1.3, 0.5, 3.0, 0.95, 2.0000001, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> UiScale.ofFactor(refused))
                    .as("%s is not a step", refused)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "1.3, P125",
            "0.95, P100",
            "1.05, P100",
            "1.375, P125",
            "1.625, P150",
            "1.875, P175",
            "1.175, P110",
            "1.0, P100",
            "1.2, P125",
            "0.5, P90",
            "0.9, P90",
            "0.0, P90",
            "-4.0, P90",
            "3.0, P200",
            "2.0, P200",
            "2.5, P200",
            "NaN, P100",
            "-Infinity, P90",
            "Infinity, P200"})
    void nearestSnapsTiesTowardOneHundredPercentAndClampsBeforeRounding(String raw, UiScale expected) {
        assertThat(UiScale.nearest(Double.parseDouble(raw))).isEqualTo(expected);
    }
}
