package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class CrossfadeGeneratorTest {

    @Test
    void linearFadeShouldStartAndEndCorrectly() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, 100);

        // Fade-out: starts at 1.0, ends at 0.0
        assertThat((double) curves[0][0]).isCloseTo(1.0, offset(0.001));
        assertThat((double) curves[0][99]).isCloseTo(0.0, offset(0.001));

        // Fade-in: starts at 0.0, ends at 1.0
        assertThat((double) curves[1][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) curves[1][99]).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void linearFadeShouldBeLinear() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, 101);

        // At the midpoint (sample 50), both should be 0.5
        assertThat((double) curves[0][50]).isCloseTo(0.5, offset(0.001));
        assertThat((double) curves[1][50]).isCloseTo(0.5, offset(0.001));
    }

    @Test
    void linearFadeShouldSumToOne() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, 100);

        for (int i = 0; i < 100; i++) {
            assertThat((double) (curves[0][i] + curves[1][i]))
                    .as("Linear fade-out + fade-in at sample %d", i)
                    .isCloseTo(1.0, offset(0.001));
        }
    }

    @Test
    void equalPowerFadeShouldStartAndEndCorrectly() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.EQUAL_POWER, 100);

        assertThat((double) curves[0][0]).isCloseTo(1.0, offset(0.001));
        assertThat((double) curves[0][99]).isCloseTo(0.0, offset(0.001));
        assertThat((double) curves[1][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) curves[1][99]).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void equalPowerFadeShouldMaintainConstantPower() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.EQUAL_POWER, 100);

        // Equal-power: sum of squares should be approximately 1.0
        for (int i = 0; i < 100; i++) {
            double sumOfSquares = curves[0][i] * curves[0][i] + curves[1][i] * curves[1][i];
            assertThat(sumOfSquares)
                    .as("Equal-power sum of squares at sample %d", i)
                    .isCloseTo(1.0, offset(0.001));
        }
    }

    @Test
    void sCurveFadeShouldStartAndEndCorrectly() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.S_CURVE, 100);

        assertThat((double) curves[0][0]).isCloseTo(1.0, offset(0.001));
        assertThat((double) curves[0][99]).isCloseTo(0.0, offset(0.001));
        assertThat((double) curves[1][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) curves[1][99]).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void sCurveFadeShouldCrossAtMidpoint() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.S_CURVE, 101);

        assertThat((double) curves[0][50]).isCloseTo(0.5, offset(0.001));
        assertThat((double) curves[1][50]).isCloseTo(0.5, offset(0.001));
    }

    @Test
    void sCurveFadeShouldSumToOne() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.S_CURVE, 100);

        for (int i = 0; i < 100; i++) {
            assertThat((double) (curves[0][i] + curves[1][i]))
                    .as("S-curve fade-out + fade-in at sample %d", i)
                    .isCloseTo(1.0, offset(0.001));
        }
    }

    @ParameterizedTest
    @EnumSource(CrossfadeCurve.class)
    void allCurvesShouldProduceCorrectLength(CrossfadeCurve curve) {
        float[][] curves = CrossfadeGenerator.generate(curve, 256);

        assertThat(curves).hasNumberOfRows(2);
        assertThat(curves[0]).hasSize(256);
        assertThat(curves[1]).hasSize(256);
    }

    @ParameterizedTest
    @EnumSource(CrossfadeCurve.class)
    void allCurvesShouldBeMonotonicallyDecreasingForFadeOut(CrossfadeCurve curve) {
        float[][] curves = CrossfadeGenerator.generate(curve, 100);

        for (int i = 1; i < 100; i++) {
            assertThat(curves[0][i])
                    .as("Fade-out at sample %d should be <= sample %d", i, i - 1)
                    .isLessThanOrEqualTo(curves[0][i - 1] + 0.0001f);
        }
    }

    @ParameterizedTest
    @EnumSource(CrossfadeCurve.class)
    void allCurvesShouldBeMonotonicallyIncreasingForFadeIn(CrossfadeCurve curve) {
        float[][] curves = CrossfadeGenerator.generate(curve, 100);

        for (int i = 1; i < 100; i++) {
            assertThat(curves[1][i])
                    .as("Fade-in at sample %d should be >= sample %d", i, i - 1)
                    .isGreaterThanOrEqualTo(curves[1][i - 1] - 0.0001f);
        }
    }

    @Test
    void shouldGenerateFromDurationAndSampleRate() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, 1.0, 44100.0);

        assertThat(curves[0]).hasSize(44100);
        assertThat(curves[1]).hasSize(44100);
    }

    @Test
    void shouldHandleSingleSample() {
        float[][] curves = CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, 1);

        assertThat(curves[0]).hasSize(1);
        assertThat(curves[1]).hasSize(1);
    }

    @Test
    void shouldRejectZeroSamples() {
        assertThatThrownBy(() -> CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeSamples() {
        assertThatThrownBy(() -> CrossfadeGenerator.generate(CrossfadeCurve.LINEAR, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
