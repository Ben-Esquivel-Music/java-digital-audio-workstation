package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnalyzerProcessorDiscontinuityTest {
    private static final int RATE = 48_000;

    @Test
    void idleGapPreservesIntegratedLoudnessAndLraHistory() {
        var clock = new AtomicLong(1);
        var resumed = new ArrayList<AnalyzerSnapshot>();
        var continuous = new ArrayList<AnalyzerSnapshot>();
        try (var afterGap = new AnalyzerProcessor(AnalyzerProcessor.Kind.LOUDNESS, resumed::add, clock::get);
             var reference = new AnalyzerProcessor(AnalyzerProcessor.Kind.LOUDNESS, continuous::add, () -> 1L)) {
            for (double amplitude : new double[]{0.5, 0.1}) {
                float[][] section = tone(RATE * 6, RATE, amplitude);
                afterGap.onBlock(section, 2, section[0].length, RATE);
                reference.onBlock(section, 2, section[0].length, RATE);
            }
            assertThat(latest(resumed).loudnessRange()).isGreaterThan(5);
            assertThat(latest(resumed).integratedLufs()).isGreaterThan(-30);
            // An unfinished block must be discarded at the pause boundary.
            afterGap.onBlock(tone(100, RATE, 0.9), 2, 100, RATE);
            clock.addAndGet(AnalyzerProcessor.IDLE_NANOS + 1);
            float[][] nextBlock = tone(RATE / 10, RATE, 0.1);
            afterGap.onBlock(nextBlock, 2, nextBlock[0].length, RATE);
            reference.onBlock(nextBlock, 2, nextBlock[0].length, RATE);
            assertThat(latest(resumed).integratedLufs()).isCloseTo(latest(continuous).integratedLufs(), within(1e-9));
            assertThat(latest(resumed).loudnessRange()).isCloseTo(latest(continuous).loudnessRange(), within(1e-9));
        }
    }

    @ParameterizedTest
    @CsvSource({"96000,2", "48000,1", "48000,4"})
    void actualSampleRateOrChannelCountChangeStartsNewProgramHistory(int rate, int channels) {
        var results = new ArrayList<AnalyzerSnapshot>();
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.LOUDNESS, results::add, () -> 1L)) {
            processor.onBlock(tone(RATE * 4, RATE, 0.5), 2, RATE * 4, RATE);
            assertThat(latest(results).integratedLufs()).isGreaterThan(-30);
            float[][] changed = new float[channels][];
            float[] samples = tone(rate / 10, rate, 0.1)[0];
            java.util.Arrays.fill(changed, samples);
            processor.onBlock(changed, channels, samples.length, rate);
            assertThat(latest(results).integratedLufs()).isEqualTo(-120);
            assertThat(latest(results).loudnessRange()).isZero();
        }
    }

    @Test
    void idleGapRequiresEntireFreshWindowBeforePublishing() {
        var clock = new AtomicLong(1);
        var results = new ArrayList<AnalyzerSnapshot>();
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.WAVEFORM, results::add, clock::get)) {
            processor.onBlock(tone(1023, RATE, 0.5), 2, 1023, RATE);
            clock.addAndGet(AnalyzerProcessor.IDLE_NANOS + 1);
            processor.onBlock(tone(1, RATE, 0.1), 2, 1, RATE);
            assertThat(results).isEmpty();
            processor.onBlock(tone(1023, RATE, 0.1), 2, 1023, RATE);
            assertThat(results).hasSize(1);
            for (float sample : ((AnalyzerSnapshot.Waveform) results.getFirst()).data().maxValues()) {
                assertThat(Math.abs(sample)).isLessThanOrEqualTo(0.1f);
            }
        }
    }

    private static LoudnessData latest(ArrayList<AnalyzerSnapshot> results) {
        return ((AnalyzerSnapshot.Loudness) results.getLast()).data();
    }

    private static float[][] tone(int frames, int rate, double amplitude) {
        float[][] samples = new float[2][frames];
        for (int frame = 0; frame < frames; frame++) {
            samples[0][frame] = samples[1][frame] = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * frame / rate));
        }
        return samples;
    }
}
