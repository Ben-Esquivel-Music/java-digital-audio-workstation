package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnalyzerProcessorCorrelationTest {
    private static final int RATE = 48_000;
    private static final int WINDOW_FRAMES = RATE / 15;

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 0", "0, 0.0000005", "1, 0.0000005"})
    void clearsReadingWhenEitherChannelLacksSignalAndRecovers(int quietChannel, double amplitude) {
        var snapshots = new ArrayList<AnalyzerSnapshot>();
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.CORRELATION, snapshots::add, () -> 1L)) {
            float[][] stereo = {tone(0.5), tone(0.5)};
            processor.onBlock(stereo, 2, WINDOW_FRAMES, RATE);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data().correlation())
                    .isCloseTo(1, within(1e-6));

            stereo[quietChannel] = tone(amplitude);
            processor.onBlock(stereo, 2, WINDOW_FRAMES, RATE);
            assertThat(snapshots).hasSize(2);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data()).isNull();

            processor.onBlock(new float[][]{tone(0.5), tone(-0.5)}, 2, WINDOW_FRAMES, RATE);
            assertThat(snapshots).hasSize(3);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data().correlation())
                    .isCloseTo(-1, within(1e-6));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void preservesCorrelationWhenBothChannelsHaveSignalDespiteUnequalLevels(int quietChannel) {
        var snapshots = new ArrayList<AnalyzerSnapshot>();
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.CORRELATION, snapshots::add, () -> 1L)) {
            float[][] stereo = {tone(0.5), tone(0.5)};
            stereo[quietChannel] = tone(0.000002);

            processor.onBlock(stereo, 2, WINDOW_FRAMES, RATE);

            assertThat(snapshots).hasSize(1);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data().correlation())
                    .isCloseTo(1, within(1e-6));
        }
    }

    @Test
    void monoSignalKeepsDualMonoCorrelationAndClearsOnSilence() {
        var snapshots = new ArrayList<AnalyzerSnapshot>();
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.CORRELATION, snapshots::add, () -> 1L)) {
            processor.onBlock(new float[][]{tone(0.5)}, 1, WINDOW_FRAMES, RATE);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data().correlation())
                    .isCloseTo(1, within(1e-6));

            processor.onBlock(new float[1][WINDOW_FRAMES], 1, WINDOW_FRAMES, RATE);
            assertThat(snapshots).hasSize(2);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data()).isNull();

            processor.onBlock(new float[][]{tone(0.5)}, 1, WINDOW_FRAMES, RATE);
            assertThat(snapshots).hasSize(3);
            assertThat(((AnalyzerSnapshot.Correlation) snapshots.getLast()).data().correlation())
                    .isCloseTo(1, within(1e-6));
        }
    }

    private static float[] tone(double amplitude) {
        float[] samples = new float[WINDOW_FRAMES];
        for (int frame = 0; frame < samples.length; frame++) {
            samples[frame] = (float) (amplitude * Math.sin(2 * Math.PI * 1500 * frame / RATE));
        }
        return samples;
    }
}
