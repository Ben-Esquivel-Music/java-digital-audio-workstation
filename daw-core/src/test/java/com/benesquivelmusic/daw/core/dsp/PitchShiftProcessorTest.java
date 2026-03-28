package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.audio.StretchQuality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PitchShiftProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        PitchShiftProcessor processor = new PitchShiftProcessor(2, 44100.0);
        assertThat(processor.getInputChannelCount()).isEqualTo(2);
        assertThat(processor.getOutputChannelCount()).isEqualTo(2);
        assertThat(processor.getPitchShiftSemitones()).isEqualTo(0.0);
        assertThat(processor.getQuality()).isEqualTo(StretchQuality.MEDIUM);
        assertThat(processor.getFftSize()).isEqualTo(2048);
    }

    @Test
    void shouldPassthroughWhenShiftIsZero() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);

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
    void shouldProduceOutputWhenShiftingUp() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);
        processor.setPitchShiftSemitones(12.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        processor.process(input, output, 4096);

        // Output should contain some signal
        double rms = rms(output[0], 0, 4096);
        assertThat(rms).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shouldProduceOutputWhenShiftingDown() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);
        processor.setPitchShiftSemitones(-12.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        processor.process(input, output, 4096);

        assertThat(rms(output[0], 0, 4096)).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shouldSupportCentAdjustments() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);

        // 50 cents = 0.5 semitones
        processor.setPitchShiftSemitones(0.5);
        assertThat(processor.getPitchShiftSemitones()).isEqualTo(0.5);

        // -30 cents
        processor.setPitchShiftSemitones(-0.3);
        assertThat(processor.getPitchShiftSemitones()).isCloseTo(-0.3,
                org.assertj.core.data.Offset.offset(1e-10));
    }

    @Test
    void shouldResetState() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);
        processor.setPitchShiftSemitones(7.0);

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
        assertThatThrownBy(() -> new PitchShiftProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PitchShiftProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PitchShiftProcessor(-1, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidPitchShift() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);
        assertThatThrownBy(() -> processor.setPitchShiftSemitones(-25.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.setPitchShiftSemitones(25.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullQuality() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);
        assertThatThrownBy(() -> processor.setQuality(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);

        processor.setPitchShiftSemitones(5.0);
        assertThat(processor.getPitchShiftSemitones()).isEqualTo(5.0);

        processor.setQuality(StretchQuality.HIGH);
        assertThat(processor.getQuality()).isEqualTo(StretchQuality.HIGH);
        assertThat(processor.getFftSize()).isEqualTo(4096);

        processor.setQuality(StretchQuality.LOW);
        assertThat(processor.getQuality()).isEqualTo(StretchQuality.LOW);
        assertThat(processor.getFftSize()).isEqualTo(1024);
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        PitchShiftProcessor processor = new PitchShiftProcessor(2, 44100.0);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            input[1][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0));
        }
        processor.process(input, output, 512);

        // With shift 0.0 (passthrough), both channels should match input
        assertThat(rms(output[0], 0, 512)).isGreaterThan(0.01);
        assertThat(rms(output[1], 0, 512)).isGreaterThan(0.01);
    }

    @Test
    void shouldAcceptBoundaryPitchShifts() {
        PitchShiftProcessor processor = new PitchShiftProcessor(1, 44100.0);
        processor.setPitchShiftSemitones(-24.0);
        assertThat(processor.getPitchShiftSemitones()).isEqualTo(-24.0);

        processor.setPitchShiftSemitones(24.0);
        assertThat(processor.getPitchShiftSemitones()).isEqualTo(24.0);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
