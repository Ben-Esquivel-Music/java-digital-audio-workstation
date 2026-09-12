package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class LoudnessHistogramTest {

    @Test
    void aGateSelectsSeparatePopulationsWithinTheSameBinInEitherInsertionOrder() {
        for (boolean reverse : new boolean[]{false, true}) {
            var histogram = new LoudnessHistogram();
            for (int observation = 0; observation < 10_000; observation++) {
                double value = observation < 8_000 ^ reverse ? -30.08 : -30.02;
                histogram.add(value, power(value));
            }
            histogram.add(-10.0, power(-10.0));
            histogram.gate(-30.05);
            long quietCount = reverse ? 8_000 : 2_000;
            assertThat(histogram.gatedCount()).isEqualTo(quietCount + 1);
            assertThat(histogram.gatedPower())
                    .isCloseTo(quietCount * power(-30.02) + power(-10.0), within(1e-10));
            assertThat(histogram.gatedValueAtRank(0)).isCloseTo(-30.02, within(1e-10));
            assertThat(histogram.gatedValueAtRank(quietCount)).isCloseTo(-10.0, within(1e-10));
        }
    }

    @Test
    void saturatedBoundaryBinKeepsConcentratedLevelsOnEachSideOfTheGate() {
        var histogram = new LoudnessHistogram();
        long expectedCount = 0;
        double expectedPower = 0.0;
        for (int observation = 0; observation < 100_000; observation++) {
            // Distinct observations force repeated merges, with a quiet and a louder mode.
            double value = observation % 5 == 0 ? -30.02 : -30.08;
            value += (observation % 1_009) * 1e-9;
            double power = power(value);
            histogram.add(value, power);
            if (value >= -30.05) {
                expectedCount++;
                expectedPower += power;
            }
        }
        assertThat(histogram.retainedCentroidCount()).isEqualTo(LoudnessHistogram.CENTROIDS_PER_BIN);
        histogram.gate(-30.05);
        assertThat(histogram.gatedCount()).isEqualTo(expectedCount);
        assertThat(histogram.gatedPower()).isCloseTo(expectedPower, within(1e-9));
        assertThat(histogram.gatedValueAtRank(0)).isBetween(-30.020001, -30.019998);
    }

    @Test
    void everyBinCanSaturateWithoutLosingCountsOrPowerOrIncreasingCapacity() {
        var histogram = new LoudnessHistogram();
        long expectedCount = 0;
        double expectedPower = 0.0;
        for (int bin = 0; bin < LoudnessMeter.HISTOGRAM_BINS; bin++) {
            for (int observation = 0; observation < 100; observation++) {
                double value = LoudnessMeter.HISTOGRAM_MIN_LUFS
                        + bin * LoudnessMeter.HISTOGRAM_BIN_LU + (observation + 0.5) * 0.001;
                double power = power(value);
                histogram.add(value, power);
                expectedCount++;
                expectedPower += power;
            }
        }
        assertThat(histogram.retainedCentroidCount())
                .isEqualTo(LoudnessMeter.HISTOGRAM_BINS * LoudnessHistogram.CENTROIDS_PER_BIN);
        histogram.gate(-70.0);
        assertThat(histogram.gatedCount()).isEqualTo(expectedCount);
        assertThat(histogram.gatedPower()).isCloseTo(expectedPower, within(expectedPower * 1e-12));
        assertThat(histogram.gatedValueAtRank(0)).isBetween(-70.0, -69.99);
        assertThat(histogram.gatedValueAtRank(expectedCount - 1)).isBetween(9.99, 10.0);
    }

    @Test
    void resetRemovesOldGatedStatisticsAndCentroidsBeforeReuse() {
        var histogram = new LoudnessHistogram();
        for (int observation = 0; observation < 100; observation++) {
            double value = -30.099 + observation * 0.0009;
            histogram.add(value, power(value));
        }
        histogram.gate(-30.05);
        assertThat(histogram.gatedCount()).isPositive();
        histogram.clear();
        assertThat(histogram.retainedCentroidCount()).isZero();
        assertThat(histogram.gatedCount()).isZero();
        assertThat(histogram.gatedPower()).isZero();
        histogram.add(-30.04, power(-30.04));
        histogram.gate(-30.05);
        assertThat(histogram.gatedCount()).isEqualTo(1);
        assertThat(histogram.gatedValueAtRank(0)).isCloseTo(-30.04, within(1e-10));
        assertThatThrownBy(() -> histogram.gatedValueAtRank(1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void aGateAboveTheHistogramRangeStillSelectsOnlyObservationsAboveIt() {
        var histogram = new LoudnessHistogram();
        histogram.add(12.0, power(12.0));
        histogram.add(20.0, power(20.0));
        histogram.gate(15.0);
        assertThat(histogram.gatedCount()).isEqualTo(1);
        assertThat(histogram.gatedValueAtRank(0)).isCloseTo(20.0, within(1e-10));
        histogram.gate(21.0);
        assertThat(histogram.gatedCount()).isZero();
    }

    private static double power(double lufs) {
        return Math.pow(10.0, (lufs + 0.691) / 10.0);
    }
}
