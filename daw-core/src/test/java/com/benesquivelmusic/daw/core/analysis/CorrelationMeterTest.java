package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationMeterTest {

    @Test
    void shouldInitializeToSilence() {
        var meter = new CorrelationMeter();
        assertThat(meter.hasData()).isTrue();
        assertThat(meter.getLatestData()).isEqualTo(CorrelationData.SILENCE);
    }

    @Test
    void shouldDetectMonoSignalAsFullyCorrelated() {
        var meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        assertThat(meter.getLatestData().correlation()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldDetectInvertedSignalAsAntiCorrelated() {
        var meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[left.length];
        for (int i = 0; i < left.length; i++) {
            right[i] = -left[i]; // Phase-inverted
        }
        meter.process(left, right, 1024);

        assertThat(meter.getLatestData().correlation()).isCloseTo(-1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldDetectCenteredBalance() {
        var meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        assertThat(meter.getLatestData().stereoBalance()).isCloseTo(0.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldDetectLeftBalance() {
        var meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[1024]; // Silence on right
        meter.process(left, right, 1024);

        assertThat(meter.getLatestData().stereoBalance()).isLessThan(0.0);
    }

    @Test
    void shouldDetectRightBalance() {
        var meter = new CorrelationMeter(0.0);
        float[] left = new float[1024]; // Silence on left
        float[] right = generateSineWave(440.0, 44100.0, 1024);
        meter.process(left, right, 1024);

        assertThat(meter.getLatestData().stereoBalance()).isGreaterThan(0.0);
    }

    @Test
    void shouldMeasureMidAndSideLevels() {
        var meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        CorrelationData data = meter.getLatestData();
        // Mono signal → high mid, low side
        assertThat(data.midLevel()).isGreaterThan(data.sideLevel());
    }

    @Test
    void shouldResetToSilence() {
        var meter = new CorrelationMeter();
        meter.process(generateSineWave(440, 44100, 512),
                generateSineWave(440, 44100, 512), 512);
        meter.reset();

        assertThat(meter.getLatestData()).isEqualTo(CorrelationData.SILENCE);
    }

    @Test
    void shouldRejectInvalidSmoothingFactor() {
        assertThatThrownBy(() -> new CorrelationMeter(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }
}
