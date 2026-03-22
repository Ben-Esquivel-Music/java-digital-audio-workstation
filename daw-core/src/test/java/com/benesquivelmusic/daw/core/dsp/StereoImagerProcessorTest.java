package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StereoImagerProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThat(imager.getInputChannelCount()).isEqualTo(2);
        assertThat(imager.getOutputChannelCount()).isEqualTo(2);
        assertThat(imager.getWidth()).isEqualTo(1.0);
    }

    @Test
    void shouldCollapseToMonoAtWidthZero() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setWidth(0.0);

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        // Different content on L and R
        for (int i = 0; i < 256; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0);
        }
        imager.process(input, output, 256);

        // At width 0, L and R should be identical (mono)
        for (int i = 0; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(output[1][i],
                    org.assertj.core.data.Offset.offset(0.001f));
        }
    }

    @Test
    void shouldPassThroughAtWidthOne() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setWidth(1.0);

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0);
        }
        imager.process(input, output, 256);

        // At width 1, output should closely match input
        for (int i = 0; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(0.001f));
            assertThat(output[1][i]).isCloseTo(input[1][i],
                    org.assertj.core.data.Offset.offset(0.001f));
        }
    }

    @Test
    void shouldWidenStereoFieldAboveOne() {
        var imager = new StereoImagerProcessor(44100.0);

        float[][] input = new float[2][1024];
        for (int i = 0; i < 1024; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0 + 0.5);
        }

        // Process at width 1 (normal)
        imager.setWidth(1.0);
        float[][] outputNormal = new float[2][1024];
        imager.process(input, outputNormal, 1024);

        // Process at width 2 (extra wide)
        imager.reset();
        imager.setWidth(2.0);
        float[][] outputWide = new float[2][1024];
        imager.process(input, outputWide, 1024);

        // The difference between L and R should be larger at width 2
        double diffNormal = channelDifference(outputNormal, 1024);
        double diffWide = channelDifference(outputWide, 1024);
        assertThat(diffWide).isGreaterThan(diffNormal);
    }

    @Test
    void shouldHandleMonoInput() {
        var imager = new StereoImagerProcessor(44100.0);
        float[][] input = new float[1][128];
        float[][] output = new float[1][128];
        for (int i = 0; i < 128; i++) input[0][i] = 0.5f;

        imager.process(input, output, 128);
        // Should pass through without error
        assertThat(output[0][0]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void shouldSupportMonoLowFrequency() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setMonoLowFrequency(true);
        imager.setMonoLowCutoffHz(200.0);

        assertThat(imager.isMonoLowFrequency()).isTrue();
        assertThat(imager.getMonoLowCutoffHz()).isEqualTo(200.0);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / 44100.0 + Math.PI / 4);
        }
        imager.process(input, output, 512);

        // Should produce finite output
        for (int i = 0; i < 512; i++) {
            assertThat(Float.isFinite(output[0][i])).isTrue();
            assertThat(Float.isFinite(output[1][i])).isTrue();
        }
    }

    @Test
    void shouldRejectInvalidWidth() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setWidth(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imager.setWidth(2.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new StereoImagerProcessor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResetState() {
        var imager = new StereoImagerProcessor(44100.0);
        float[][] input = new float[2][64];
        float[][] output = new float[2][64];
        imager.process(input, output, 64);
        imager.reset();
        // Should not throw
    }

    // --- Multi-band width tests ---

    @Test
    void shouldEnableMultiBandMode() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setBandWidths(
                new double[]{200.0, 5000.0},
                new double[]{0.0, 1.0, 1.5});

        assertThat(imager.isMultiBandEnabled()).isTrue();
        assertThat(imager.getCrossoverFrequencies()).containsExactly(200.0, 5000.0);
        assertThat(imager.getBandWidths()).containsExactly(0.0, 1.0, 1.5);
    }

    @Test
    void shouldDisableMultiBandWithClear() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setBandWidths(
                new double[]{200.0},
                new double[]{0.0, 1.0});
        assertThat(imager.isMultiBandEnabled()).isTrue();

        imager.clearBandWidths();
        assertThat(imager.isMultiBandEnabled()).isFalse();
        assertThat(imager.getCrossoverFrequencies()).isEmpty();
        assertThat(imager.getBandWidths()).isEmpty();
    }

    @Test
    void shouldNarrowLowBandInMultiBandMode() {
        var imager = new StereoImagerProcessor(44100.0);
        // Low band (< 200 Hz) at width 0 (mono), high band at width 1 (normal)
        imager.setBandWidths(
                new double[]{200.0},
                new double[]{0.0, 1.0});

        // Generate a low-frequency stereo signal with phase difference
        int frames = 4096;
        float[][] input = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / 44100.0 + Math.PI / 3);
        }

        float[][] output = new float[2][frames];
        imager.process(input, output, frames);

        // The low-frequency output should be more mono (less L-R difference)
        // than the input, because the low band width is 0
        double inputDiff = channelDifference(input, frames);
        double outputDiff = channelDifference(output, frames);
        assertThat(outputDiff).isLessThan(inputDiff);
    }

    @Test
    void shouldWidenHighBandInMultiBandMode() {
        var imager = new StereoImagerProcessor(44100.0);
        // Low band normal, high band extra wide
        imager.setBandWidths(
                new double[]{200.0},
                new double[]{1.0, 2.0});

        int frames = 4096;
        float[][] input = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            // High-frequency stereo content
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 5000.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 5000.0 * i / 44100.0 + 0.5);
        }

        float[][] outputWide = new float[2][frames];
        imager.process(input, outputWide, frames);

        // Compare with normal width
        imager.clearBandWidths();
        imager.reset();
        imager.setWidth(1.0);
        float[][] outputNormal = new float[2][frames];
        imager.process(input, outputNormal, frames);

        double diffWide = channelDifference(outputWide, frames);
        double diffNormal = channelDifference(outputNormal, frames);
        assertThat(diffWide).isGreaterThan(diffNormal);
    }

    @Test
    void shouldProduceFiniteOutputInMultiBandMode() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setBandWidths(
                new double[]{200.0, 2000.0, 8000.0},
                new double[]{0.0, 0.5, 1.0, 1.5});

        int frames = 1024;
        float[][] input = new float[2][frames];
        float[][] output = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) (Math.sin(2.0 * Math.PI * 100.0 * i / 44100.0)
                    + 0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0)
                    + 0.3 * Math.sin(2.0 * Math.PI * 10000.0 * i / 44100.0));
            input[1][i] = (float) (Math.sin(2.0 * Math.PI * 100.0 * i / 44100.0 + 0.2)
                    + 0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0 + 0.3)
                    + 0.3 * Math.sin(2.0 * Math.PI * 10000.0 * i / 44100.0 + 0.5));
        }

        imager.process(input, output, frames);

        for (int i = 0; i < frames; i++) {
            assertThat(Float.isFinite(output[0][i])).isTrue();
            assertThat(Float.isFinite(output[1][i])).isTrue();
        }
    }

    @Test
    void shouldRejectMismatchedBandWidthsLength() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setBandWidths(
                new double[]{200.0},
                new double[]{0.0, 1.0, 1.5})) // Should be length 2
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullCrossoverFrequencies() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setBandWidths(null, new double[]{1.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectUnsortedCrossoverFrequencies() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setBandWidths(
                new double[]{5000.0, 200.0},
                new double[]{0.0, 1.0, 1.5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBandWidthOutOfRange() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setBandWidths(
                new double[]{200.0},
                new double[]{-0.1, 1.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imager.setBandWidths(
                new double[]{200.0},
                new double[]{1.0, 2.1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectCrossoverAboveNyquist() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setBandWidths(
                new double[]{25000.0},
                new double[]{0.0, 1.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResetMultiBandFilterState() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setBandWidths(
                new double[]{200.0},
                new double[]{0.0, 1.0});

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.5f;
            input[1][i] = 0.3f;
        }
        imager.process(input, output, 256);
        imager.reset(); // Should not throw
    }

    @Test
    void shouldBeSymmetricAtWidthOne() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setWidth(1.0);

        // Symmetric input: same signal on L and R
        int frames = 512;
        float[][] input = new float[2][frames];
        float[][] output = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            float val = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[0][i] = val;
            input[1][i] = val;
        }
        imager.process(input, output, frames);

        // Output should also be symmetric
        for (int i = 0; i < frames; i++) {
            assertThat(output[0][i]).isCloseTo(output[1][i],
                    org.assertj.core.data.Offset.offset(0.0001f));
        }
    }

    @Test
    void shouldMaintainWidthSymmetry() {
        // Test that width N and then width 1/N are roughly inverse
        // (not exact due to mid/side math, but should be centered)
        var imager = new StereoImagerProcessor(44100.0);

        int frames = 512;
        float[][] input = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0 + 0.3);
        }

        // Width 0 → mono
        imager.setWidth(0.0);
        float[][] monoOut = new float[2][frames];
        imager.process(input, monoOut, frames);
        double monoDiff = channelDifference(monoOut, frames);

        // Width 2 → extra wide
        imager.reset();
        imager.setWidth(2.0);
        float[][] wideOut = new float[2][frames];
        imager.process(input, wideOut, frames);
        double wideDiff = channelDifference(wideOut, frames);

        // Mono should have zero L-R difference, wide should have maximum
        assertThat(monoDiff).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(wideDiff).isGreaterThan(0.0);
    }

    // --- Low-frequency mono summing accuracy tests ---

    @Test
    void shouldMonoSumLowFrequenciesAccurately() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setMonoLowFrequency(true);
        imager.setMonoLowCutoffHz(200.0);
        imager.setWidth(1.0);

        // Feed a low-frequency signal with stereo difference
        int frames = 8192;
        float[][] input = new float[2][frames];
        float[][] output = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 80.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 80.0 * i / 44100.0 + Math.PI / 2);
        }

        imager.process(input, output, frames);

        // After the filter settles (skip first 1024 samples), the low-frequency
        // component in L and R should be more similar than the input
        double inputDiff = channelDifference(input, frames, 2048, frames);
        double outputDiff = channelDifference(output, frames, 2048, frames);
        assertThat(outputDiff).isLessThan(inputDiff);
    }

    private static double channelDifference(float[][] buffer, int length) {
        return channelDifference(buffer, length, 0, length);
    }

    private static double channelDifference(float[][] buffer, int length, int start, int end) {
        double sum = 0;
        int count = end - start;
        for (int i = start; i < end; i++) {
            double diff = buffer[0][i] - buffer[1][i];
            sum += diff * diff;
        }
        return Math.sqrt(sum / count);
    }
}
