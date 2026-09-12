package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Public-process regressions against unquantized 400 ms and 3 s observations. */
class LoudnessMeterGateBoundaryTest {

    private static final int RATE = 48_000;
    private static final int HOP = 4_800;
    private static final int PROGRAMME_BLOCKS = 10_000;

    @ParameterizedTest
    @CsvSource({"890, true", "905, false"})
    void integratedGateKeepsOrRejectsAQuietPlateauInsideItsBoundaryBin(int loudBlocks,
                                                                     boolean quietIncluded) {
        var measurement = measure(loudBlocks, -30.05, -30.05);
        double gate = relativeGate(measurement.momentary(), -10.0);
        assertThat(gate).isBetween(-30.1, -30.0);
        assertThat(-30.05 >= gate).isEqualTo(quietIncluded);
        double expected = integratedReference(measurement.momentary());
        assertThat(expected).isBetween(quietIncluded ? -20.2 : -10.1,
                quietIncluded ? -20.0 : -9.9);
        assertThat(measurement.integrated()).isCloseTo(expected, within(0.001));
    }

    @ParameterizedTest
    @CsvSource({"985, true", "1000, false"})
    void loudnessRangeGateKeepsOrRejectsAQuietPlateauInsideItsBoundaryBin(int loudBlocks,
                                                                       boolean quietIncluded) {
        var measurement = measure(loudBlocks, -40.05, -40.05);
        double gate = relativeGate(measurement.shortTerm(), -20.0);
        assertThat(gate).isBetween(-40.1, -40.0);
        assertThat(-40.05 >= gate).isEqualTo(quietIncluded);
        double expected = rangeReference(measurement.shortTerm());
        if (quietIncluded) {
            assertThat(expected).isCloseTo(30.05, within(0.001));
        } else {
            assertThat(expected).isLessThan(0.1);
        }
        assertThat(measurement.range()).isCloseTo(expected, within(0.001));
    }

    @ParameterizedTest
    @CsvSource({"900, -30.08, -30.02, -10.0", "990, -40.08, -40.02, -20.0"})
    void mixedLevelsInOneBoundaryBinAreGatedIndividually(int loudBlocks, double below,
                                                        double above, double relativeGateLu) {
        var measurement = measure(loudBlocks, below, above);
        List<Double> observations = relativeGateLu == -10.0
                ? measurement.momentary() : measurement.shortTerm();
        double gate = relativeGate(observations, relativeGateLu);
        assertThat(gate).isStrictlyBetween(below, above);
        assertThat(measurement.integrated())
                .isCloseTo(integratedReference(measurement.momentary()), within(0.001));
        assertThat(measurement.range())
                .isCloseTo(rangeReference(measurement.shortTerm()), within(0.001));
    }

    private static Measurement measure(int loudBlocks, double firstQuietLufs, double secondQuietLufs) {
        var calibration = new LoudnessMeter(RATE, HOP, 1);
        float[] unit = tone(1.0);
        for (int block = 0; block < 100; block++) {
            calibration.process(unit, unit, HOP);
        }
        double unitLufs = calibration.getLatestData().momentaryLufs();
        calibration.close();

        float[] loud = tone(Math.pow(10.0, (-10.0 - unitLufs) / 20.0));
        float[] firstQuiet = tone(Math.pow(10.0, (firstQuietLufs - unitLufs) / 20.0));
        float[] secondQuiet = tone(Math.pow(10.0, (secondQuietLufs - unitLufs) / 20.0));
        int secondQuietStart = loudBlocks + (PROGRAMME_BLOCKS - loudBlocks) / 2;
        var meter = new LoudnessMeter(RATE, HOP, 1);
        List<Double> momentary = new ArrayList<>();
        List<Double> shortTerm = new ArrayList<>();
        try {
            for (int block = 0; block < PROGRAMME_BLOCKS; block++) {
                float[] samples = block < loudBlocks ? loud
                        : block < secondQuietStart ? firstQuiet : secondQuiet;
                meter.process(samples, samples, HOP);
                var data = meter.getLatestData();
                if (block >= 3) {
                    momentary.add(data.momentaryLufs());
                }
                if (block >= 29) {
                    shortTerm.add(data.shortTermLufs());
                }
            }
            var data = meter.getLatestData();
            return new Measurement(momentary, shortTerm, data.integratedLufs(), data.loudnessRange());
        } finally {
            meter.close();
        }
    }

    private record Measurement(List<Double> momentary, List<Double> shortTerm,
                               double integrated, double range) {}

    private static float[] tone(double gain) {
        float[] samples = new float[HOP];
        for (int frame = 0; frame < HOP; frame++) {
            samples[frame] = (float) (gain * Math.sin(2.0 * Math.PI * 1000.0 * frame / RATE));
        }
        return samples;
    }

    private static double integratedReference(List<Double> observations) {
        double gate = relativeGate(observations, -10.0);
        return lufs(observations.stream().filter(value -> value > -70.0 && value >= gate)
                .mapToDouble(LoudnessMeterGateBoundaryTest::power).average().orElseThrow());
    }

    private static double rangeReference(List<Double> observations) {
        double gate = relativeGate(observations, -20.0);
        var retained = observations.stream().filter(value -> value > -70.0 && value >= gate)
                .sorted().toList();
        int low = (int) Math.floor(retained.size() * 0.10);
        int high = Math.min((int) Math.floor(retained.size() * 0.95), retained.size() - 1);
        return retained.get(high) - retained.get(low);
    }

    private static double relativeGate(List<Double> observations, double relativeLu) {
        return lufs(observations.stream().filter(value -> value > -70.0)
                .mapToDouble(LoudnessMeterGateBoundaryTest::power).average().orElseThrow()) + relativeLu;
    }

    private static double power(double lufs) {
        return Math.pow(10.0, (lufs + 0.691) / 10.0);
    }

    private static double lufs(double power) {
        return -0.691 + 10.0 * Math.log10(power);
    }
}
