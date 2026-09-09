package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoudnessMeterSampleTimeTest {

    private static final int RATE = 8_000;
    private static final int HOP = RATE / 10;
    private static final int SHORT_TERM_WINDOW = 3 * RATE;

    @Test
    void loudnessRangeWaitsForThreeSecondsAndThenObservesEveryHundredMilliseconds() {
        var meter = new LoudnessMeter(RATE, 512);
        float[] quiet = tone(SHORT_TERM_WINDOW, 0.01);
        feed(meter, quiet, 0, quiet.length - 1);
        assertThat(meter.getLatestData().loudnessRange()).isZero();
        feed(meter, quiet, quiet.length - 1, 1);
        assertThat(meter.getLatestData().loudnessRange()).isZero();

        float[] loud = tone(HOP, 0.5);
        feed(meter, loud, 0, HOP - 1);
        assertThat(meter.getLatestData().loudnessRange()).isZero();
        feed(meter, loud, HOP - 1, 1);
        assertThat(meter.getLatestData().loudnessRange()).isGreaterThan(10.0);
    }

    @Test
    void windowsAndLoudnessRangeAreIndependentOfNominalAndActualCallbackSizes() {
        float[] programme = programme(18 * RATE + 137);
        var reference = new LoudnessMeter(RATE, HOP);
        List<Double> observations = new ArrayList<>();
        int offset = 0;
        while (offset + HOP <= programme.length) {
            feed(reference, programme, offset, HOP);
            offset += HOP;
            if (offset >= SHORT_TERM_WINDOW) {
                observations.add(reference.getLatestData().shortTermLufs());
            }
        }
        feed(reference, programme, offset, programme.length - offset);
        double offlineRange = offlineLoudnessRange(observations);
        assertThat(offlineRange).isGreaterThan(5.0);
        assertThat(reference.getLatestData().loudnessRange()).isCloseTo(offlineRange, within(0.2));

        for (int nominal : new int[]{64, 512, 4096}) {
            for (int[] partition : List.of(new int[]{64}, new int[]{512}, new int[]{1024},
                    new int[]{1, 79, 503, 4001, 11, 27_011}, new int[]{programme.length})) {
                var meter = new LoudnessMeter(RATE, nominal);
                feedPartitioned(meter, programme, partition);
                var actual = meter.getLatestData();
                var expected = reference.getLatestData();
                assertThat(actual.momentaryLufs()).isCloseTo(expected.momentaryLufs(), within(1e-10));
                assertThat(actual.shortTermLufs()).isCloseTo(expected.shortTermLufs(), within(1e-10));
                assertThat(actual.integratedLufs()).isCloseTo(expected.integratedLufs(), within(1e-10));
                assertThat(actual.loudnessRange()).isEqualTo(expected.loudnessRange());
            }
        }
    }

    @Test
    void shortTermWindowExpiresAudioAfterExactlyThreeSeconds() {
        var meter = new LoudnessMeter(RATE, 512);
        float[] loud = tone(SHORT_TERM_WINDOW, 0.5);
        meter.process(loud, loud, loud.length);
        float[] quiet = tone(SHORT_TERM_WINDOW, 0.1);
        feedPartitioned(meter, quiet, new int[]{11, 503, 4001});
        var quietOnly = new LoudnessMeter(RATE, 512);
        quietOnly.process(quiet, quiet, quiet.length);
        // Allow the K-weighting filter's brief transient at the gain change.
        assertThat(meter.getLatestData().shortTermLufs())
                .isCloseTo(quietOnly.getLatestData().shortTermLufs(), within(0.01));
    }

    @Test
    void fullResetRestartsWindowWarmupAndLraCadence() {
        var meter = new LoudnessMeter(RATE, 512);
        feedPartitioned(meter, programme(7 * RATE + 79), new int[]{503, 4001});
        assertThat(meter.getLatestData().loudnessRange()).isPositive();
        meter.reset();
        var fresh = new LoudnessMeter(RATE, 512);
        float[] programme = programme(4 * RATE + 137);
        feedPartitioned(meter, programme, new int[]{79, 4001});
        feedPartitioned(fresh, programme, new int[]{512});
        assertThat(meter.getLatestData()).isEqualTo(fresh.getLatestData());
    }

    @Test
    void integratedResetClearsLraStatisticsWhileKeepingRunningWindows() {
        var meter = new LoudnessMeter(RATE, 512);
        var uninterrupted = new LoudnessMeter(RATE, 512);
        float[] programme = programme(8 * RATE + 79);
        feedPartitioned(meter, programme, new int[]{503, 4001});
        feedPartitioned(uninterrupted, programme, new int[]{512});
        assertThat(meter.getLatestData().loudnessRange()).isGreaterThan(1.0);
        meter.resetIntegrated();
        float[] next = tone(HOP, 0.25);
        feedPartitioned(meter, next, new int[]{79});
        uninterrupted.process(next, next, next.length);
        assertThat(meter.getLatestData().momentaryLufs())
                .isEqualTo(uninterrupted.getLatestData().momentaryLufs());
        assertThat(meter.getLatestData().shortTermLufs())
                .isEqualTo(uninterrupted.getLatestData().shortTermLufs());
        assertThat(meter.getLatestData().loudnessRange()).isZero();
        assertThat(meter.getLatestData().integratedLufs()).isEqualTo(-120.0);
    }

    private static double offlineLoudnessRange(List<Double> observations) {
        var absoluteGated = observations.stream().filter(value -> value > -70).toList();
        double averagePower = absoluteGated.stream().mapToDouble(value ->
                Math.pow(10, (value + 0.691) / 10)).average().orElseThrow();
        double relativeGate = -0.691 + 10 * Math.log10(averagePower) - 20;
        var kept = absoluteGated.stream().filter(value -> value >= relativeGate).sorted().toList();
        return kept.get(Math.min((int) (kept.size() * 0.95), kept.size() - 1))
                - kept.get((int) (kept.size() * 0.10));
    }

    private static void feedPartitioned(LoudnessMeter meter, float[] signal, int[] partition) {
        int offset = 0;
        int block = 0;
        while (offset < signal.length) {
            int count = Math.min(partition[block++ % partition.length], signal.length - offset);
            feed(meter, signal, offset, count);
            offset += count;
        }
    }

    private static void feed(LoudnessMeter meter, float[] signal, int offset, int count) {
        float[] block = Arrays.copyOfRange(signal, offset, offset + count);
        meter.process(block, block, count);
    }

    private static float[] programme(int frames) {
        float[] samples = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            double amplitude = switch ((frame / (2 * RATE)) % 4) {
                case 0 -> 0.7;
                case 1 -> 0.005;
                case 2 -> 0.1;
                default -> 0.03;
            };
            samples[frame] = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * frame / RATE));
        }
        return samples;
    }

    private static float[] tone(int frames, double amplitude) {
        float[] samples = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            samples[frame] = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * frame / RATE));
        }
        return samples;
    }
}
