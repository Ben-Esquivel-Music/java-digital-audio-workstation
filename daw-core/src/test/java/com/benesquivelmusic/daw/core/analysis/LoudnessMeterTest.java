package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoudnessMeterTest {

    private static final double SAMPLE_RATE = 48000.0;
    private static final int BLOCK_SIZE = 480;

    @Test
    void shouldInitializeToSilence() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        assertThat(meter.hasData()).isTrue();
        assertThat(meter.getLatestData()).isEqualTo(LoudnessData.SILENCE);
    }

    @Test
    void shouldMeasureSilenceAsVeryLow() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] silence = new float[BLOCK_SIZE];
        meter.process(silence, silence, BLOCK_SIZE);

        LoudnessData data = meter.getLatestData();
        assertThat(data.momentaryLufs()).isLessThan(-70.0);
    }

    @Test
    void shouldMeasureLoudSignalHigherThanSilence() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        // Process silence
        float[] silence = new float[BLOCK_SIZE];
        meter.process(silence, silence, BLOCK_SIZE);
        double silenceLufs = meter.getLatestData().momentaryLufs();

        // Process a loud sine wave
        meter.reset();
        float[] loud = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 50; i++) { // Process enough blocks
            meter.process(loud, loud, BLOCK_SIZE);
        }
        double loudLufs = meter.getLatestData().momentaryLufs();

        assertThat(loudLufs).isGreaterThan(silenceLufs);
    }

    @Test
    void shouldTrackTruePeak() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] samples = new float[BLOCK_SIZE];
        samples[0] = 0.9f;
        meter.process(samples, samples, BLOCK_SIZE);

        assertThat(meter.getLatestData().truePeakDbfs()).isGreaterThan(-10.0);
    }

    @Test
    void shouldResetAllState() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] loud = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 10; i++) {
            meter.process(loud, loud, BLOCK_SIZE);
        }

        meter.reset();
        assertThat(meter.getLatestData()).isEqualTo(LoudnessData.SILENCE);
    }

    @Test
    void shouldHavePlatformTargetConstants() {
        assertThat(LoudnessMeter.TARGET_SPOTIFY).isEqualTo(-14.0);
        assertThat(LoudnessMeter.TARGET_APPLE_MUSIC).isEqualTo(-16.0);
        assertThat(LoudnessMeter.TARGET_YOUTUBE).isEqualTo(-14.0);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new LoudnessMeter(0, 480))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBlockSize() {
        assertThatThrownBy(() -> new LoudnessMeter(48000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProvideIntegratedLoudnessOverTime() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        // Process many blocks to accumulate integrated loudness
        for (int i = 0; i < 100; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        LoudnessData data = meter.getLatestData();
        // Integrated should be a finite value (not silence floor)
        assertThat(data.integratedLufs()).isGreaterThan(-100.0);
    }

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * frequency * i / sampleRate));
        }
        return samples;
    }
}
