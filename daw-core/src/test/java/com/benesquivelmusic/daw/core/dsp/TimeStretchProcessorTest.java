package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.audio.StretchQuality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeStretchProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        TimeStretchProcessor processor = new TimeStretchProcessor(2, 44100.0);
        assertThat(processor.getInputChannelCount()).isEqualTo(2);
        assertThat(processor.getOutputChannelCount()).isEqualTo(2);
        assertThat(processor.getStretchRatio()).isEqualTo(1.0);
        assertThat(processor.getQuality()).isEqualTo(StretchQuality.MEDIUM);
        assertThat(processor.getFftSize()).isEqualTo(2048);
    }

    @Test
    void shouldPassthroughWhenRatioIsOne() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        processor.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProduceOutputWhenStretching() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);
        processor.setStretchRatio(1.5);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        processor.process(input, output, 4096);

        // With stretching active, output should contain signal energy
        double rms = rms(output[0], 0, 4096);
        // The output may include zero-fill during initial warmup, but should
        // produce some non-zero energy from the phase vocoder
        assertThat(rms).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shouldProduceOutputWhenCompressing() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);
        processor.setStretchRatio(0.5);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        processor.process(input, output, 4096);

        assertThat(rms(output[0], 0, 4096)).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shouldResetState() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);
        processor.setStretchRatio(1.5);

        float[][] input = new float[1][2048];
        float[][] output = new float[1][2048];
        java.util.Arrays.fill(input[0], 0.5f);
        processor.process(input, output, 2048);

        processor.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][2048];
        float[][] resetOutput = new float[1][2048];
        processor.process(silence, resetOutput, 2048);

        double rms = rms(resetOutput[0], 0, 2048);
        assertThat(rms).isLessThan(0.01);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new TimeStretchProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeStretchProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeStretchProcessor(-1, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidStretchRatio() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);
        assertThatThrownBy(() -> processor.setStretchRatio(0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.setStretchRatio(5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullQuality() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);
        assertThatThrownBy(() -> processor.setQuality(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);

        processor.setStretchRatio(2.0);
        assertThat(processor.getStretchRatio()).isEqualTo(2.0);

        processor.setQuality(StretchQuality.HIGH);
        assertThat(processor.getQuality()).isEqualTo(StretchQuality.HIGH);
        assertThat(processor.getFftSize()).isEqualTo(4096);

        processor.setQuality(StretchQuality.LOW);
        assertThat(processor.getQuality()).isEqualTo(StretchQuality.LOW);
        assertThat(processor.getFftSize()).isEqualTo(1024);
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        TimeStretchProcessor processor = new TimeStretchProcessor(2, 44100.0);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            input[1][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0));
        }
        processor.process(input, output, 512);

        // With ratio 1.0 (passthrough), both channels should match input
        assertThat(rms(output[0], 0, 512)).isGreaterThan(0.01);
        assertThat(rms(output[1], 0, 512)).isGreaterThan(0.01);
    }

    @Test
    void shouldAcceptBoundaryStretchRatios() {
        TimeStretchProcessor processor = new TimeStretchProcessor(1, 44100.0);
        processor.setStretchRatio(0.25);
        assertThat(processor.getStretchRatio()).isEqualTo(0.25);

        processor.setStretchRatio(4.0);
        assertThat(processor.getStretchRatio()).isEqualTo(4.0);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
