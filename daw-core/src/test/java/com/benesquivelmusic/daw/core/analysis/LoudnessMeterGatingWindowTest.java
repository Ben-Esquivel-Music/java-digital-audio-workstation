package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoudnessMeterGatingWindowTest {

    private static final int RATE = 8_000;
    private static final int WINDOW = 3_200;
    private static final int HOP = 800;

    @Test
    void integratedGateWaitsForFourHundredMillisecondsThenAdvancesEveryHundred() {
        var meter = new LoudnessMeter(RATE, 512);
        float[] signal = programme(WINDOW + HOP);
        feed(meter, signal, 0, WINDOW - 1);
        assertThat(meter.getLatestData().integratedLufs()).isEqualTo(-120.0);
        feed(meter, signal, WINDOW - 1, 1);
        double first = meter.getLatestData().integratedLufs();
        assertThat(first).isGreaterThan(-70.0);
        feed(meter, signal, WINDOW, HOP - 1);
        assertThat(meter.getLatestData().integratedLufs()).isEqualTo(first);
        feed(meter, signal, WINDOW + HOP - 1, 1);
        assertThat(meter.getLatestData().integratedLufs()).isNotEqualTo(first);
    }

    @Test
    void gatingMatchesAnIndependentOverlappingWindowReferenceForEveryCallbackPartition() {
        float[] signal = programme(RATE * 12 + 137);
        var referenceMeter = new LoudnessMeter(RATE, HOP);
        List<Double> windows = new ArrayList<>();
        for (int offset = 0; offset + HOP <= signal.length; offset += HOP) {
            feed(referenceMeter, signal, offset, HOP);
            if (offset + HOP >= WINDOW) {
                windows.add(referenceMeter.getLatestData().momentaryLufs());
            }
        }
        double absolutePower = windows.stream().filter(lufs -> lufs > -70.0)
                .mapToDouble(LoudnessMeterGatingWindowTest::power).average().orElseThrow();
        double relativeGate = lufs(absolutePower) - 10.0;
        double reference = lufs(windows.stream().filter(value -> value > -70.0 && value >= relativeGate)
                .mapToDouble(LoudnessMeterGatingWindowTest::power).average().orElseThrow());
        Double measured = null;
        for (int[] partition : List.of(new int[]{64}, new int[]{512}, new int[]{1024},
                new int[]{1, 79, 503, 4001, 11, 1024})) {
            var meter = new LoudnessMeter(RATE, partition[0]);
            int offset = 0;
            int block = 0;
            while (offset < signal.length) {
                int count = Math.min(partition[block++ % partition.length], signal.length - offset);
                feed(meter, signal, offset, count);
                offset += count;
            }
            double actual = meter.getLatestData().integratedLufs();
            assertThat(actual).isCloseTo(reference, within(0.1));
            if (measured != null) {
                assertThat(actual).isCloseTo(measured, within(1e-10));
            }
            measured = actual;
            assertThat(meter.getHistory().getLast().timestampSeconds())
                    .isCloseTo(signal.length / (double) RATE, within(1e-10));
        }
    }

    @Test
    void resetIntegratedExcludesSamplesBeforeTheResetAndStartsANewFullWindow() {
        var meter = new LoudnessMeter(RATE, HOP);
        float[] loud = programme(RATE);
        feed(meter, loud, 0, loud.length);
        meter.resetIntegrated();
        float[] silence = new float[WINDOW];
        feed(meter, silence, 0, WINDOW - 1);
        assertThat(meter.getLatestData().integratedLufs()).isEqualTo(-120.0);
        feed(meter, silence, WINDOW - 1, 1);
        assertThat(meter.getLatestData().integratedLufs()).isLessThan(-30.0);
    }

    private static void feed(LoudnessMeter meter, float[] signal, int offset, int count) {
        float[] block = Arrays.copyOfRange(signal, offset, offset + count);
        meter.process(block, block, count);
    }

    private static float[] programme(int frames) {
        float[] samples = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            double amplitude = switch ((frame / HOP) % 12) {
                case 0, 1 -> 0.7;
                case 2, 3, 4 -> 0.02;
                case 5, 6, 7, 8 -> 0.25;
                default -> 0.0001;
            };
            samples[frame] = (float) (amplitude * Math.sin(2 * Math.PI * 997 * frame / RATE));
        }
        return samples;
    }

    private static double power(double lufs) {
        return Math.pow(10.0, (lufs + 0.691) / 10.0);
    }

    private static double lufs(double power) {
        return -0.691 + 10.0 * Math.log10(power);
    }
}
