package com.benesquivelmusic.daw.core.spatial.qc;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AtmosAbComparator}. The three acceptance cases from
 * the issue are covered by:
 * <ul>
 *   <li>{@link #identicalToIdenticalReportsZeroDelta()}</li>
 *   <li>{@link #knownMinusThreeDbOffsetOnSingleChannelIsMeasured()}</li>
 *   <li>{@link #recoversTwentyMillisecondAlignmentToWithinOneSample()}</li>
 * </ul>
 */
class AtmosAbComparatorTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @Test
    void identicalToIdenticalReportsZeroDelta() {
        // Use noise so the autocorrelation has a single, unambiguous peak
        // at lag 0; periodic signals would tie at every multiple of the
        // period.
        float[][] buffer = whiteNoiseBed(4, 4_800, 0x42);

        AbComparisonResult r = new AtmosAbComparator(SAMPLE_RATE)
                .compare(buffer, deepCopy(buffer));

        for (int c = 0; c < r.channelCount(); c++) {
            assertThat(r.deltasDb()[c]).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1.0e-9));
            assertThat(r.correlations()[c]).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1.0e-9));
        }
        assertThat(r.bedRmsDeltaDb()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(r.alignmentSamples()).isZero();
        assertThat(r.matchScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1.0e-6));
    }

    @Test
    void knownMinusThreeDbOffsetOnSingleChannelIsMeasured() {
        // 7.1.4 layout (12 channels) — channel 3 (e.g. centre) is -3 dB on
        // the mix vs the reference, all other channels are identical.
        int channels = 12;
        float[][] reference = sineBed(channels, 4_800, 440.0);
        float[][] mix = deepCopy(reference);

        double linear = Math.pow(10.0, -3.0 / 20.0);
        for (int i = 0; i < mix[3].length; i++) {
            mix[3][i] *= (float) linear;
        }

        AbComparisonResult r = new AtmosAbComparator(SAMPLE_RATE)
                .compare(mix, reference);

        for (int c = 0; c < channels; c++) {
            double expected = (c == 3) ? -3.0 : 0.0;
            assertThat(r.deltasDb()[c])
                    .as("channel %d delta", c)
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1.0e-6));
        }
        // Auto-trim should propose +3 dB on channel 3, 0 dB elsewhere.
        double[] trim = new AtmosAbComparator(SAMPLE_RATE)
                .estimateAutoTrim(mix, reference);
        assertThat(trim[3]).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1.0e-6));
        for (int c = 0; c < channels; c++) {
            if (c != 3) {
                assertThat(trim[c]).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1.0e-6));
            }
        }
    }

    @Test
    void recoversTwentyMillisecondAlignmentToWithinOneSample() {
        // 20 ms at 48 kHz = 960 samples.
        int offsetSamples = (int) Math.round(20.0 * SAMPLE_RATE / 1000.0);
        // Use noise to produce a sharp cross-correlation peak.
        float[][] reference = whiteNoiseBed(4, 8_192, 0xA7);

        // Build mix as reference shifted right by `offsetSamples`, i.e.
        // mix[i] = reference[i - offset]  →  mix lags reference by `offset`
        // samples, and the estimator should report +offset.
        float[][] mix = new float[reference.length][reference[0].length];
        for (int c = 0; c < reference.length; c++) {
            for (int i = offsetSamples; i < reference[c].length; i++) {
                mix[c][i] = reference[c][i - offsetSamples];
            }
        }

        int recovered = new AtmosAbComparator(SAMPLE_RATE)
                .estimateAlignmentSamples(mix, reference);

        assertThat(Math.abs(recovered - offsetSamples))
                .as("recovered=%d expected=%d", recovered, offsetSamples)
                .isLessThanOrEqualTo(1);
    }

    @Test
    void rejectsMismatchedChannelCounts() {
        AtmosAbComparator c = new AtmosAbComparator(SAMPLE_RATE);
        assertThatThrownBy(() -> c.compare(new float[2][16], new float[3][16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyBuffers() {
        AtmosAbComparator c = new AtmosAbComparator(SAMPLE_RATE);
        assertThatThrownBy(() -> c.compare(new float[0][], new float[0][]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveSampleRate() {
        assertThatThrownBy(() -> new AtmosAbComparator(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- helpers -----

    private static float[][] sineBed(int channels, int frames, double freqHz) {
        float[][] buf = new float[channels][frames];
        double w = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < frames; i++) {
                buf[c][i] = (float) Math.sin(w * i + c * 0.1);
            }
        }
        return buf;
    }

    private static float[][] whiteNoiseBed(int channels, int frames, long seed) {
        Random rng = new Random(seed);
        float[][] buf = new float[channels][frames];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < frames; i++) {
                buf[c][i] = (float) (rng.nextGaussian() * 0.25);
            }
        }
        return buf;
    }

    private static float[][] deepCopy(float[][] src) {
        float[][] copy = new float[src.length][];
        for (int c = 0; c < src.length; c++) {
            copy[c] = src[c].clone();
        }
        return copy;
    }
}
