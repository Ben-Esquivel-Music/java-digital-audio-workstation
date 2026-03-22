package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LevelMeterTest {

    @Test
    void shouldInitializeToSilence() {
        LevelMeter meter = new LevelMeter();
        assertThat(meter.hasData()).isTrue();
        assertThat(meter.getLatestData()).isEqualTo(LevelData.SILENCE);
    }

    @Test
    void shouldMeasurePeakLevel() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = {0.0f, 0.5f, -0.8f, 0.3f};
        meter.process(samples);

        LevelData data = meter.getLatestData();
        assertThat(data.peakLinear()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void shouldMeasureRmsLevel() {
        LevelMeter meter = new LevelMeter(0.0);
        // All samples at 0.5 → RMS = 0.5
        float[] samples = {0.5f, 0.5f, 0.5f, 0.5f};
        meter.process(samples);

        LevelData data = meter.getLatestData();
        assertThat(data.rmsLinear()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void shouldDetectClipping() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = {0.0f, 1.5f, 0.0f};
        meter.process(samples);

        assertThat(meter.getLatestData().clipping()).isTrue();
    }

    @Test
    void shouldNotClipNormalSignals() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = {0.0f, 0.9f, -0.5f};
        meter.process(samples);

        assertThat(meter.getLatestData().clipping()).isFalse();
    }

    @Test
    void shouldConvertToDbCorrectly() {
        // 1.0 linear = 0 dB
        assertThat(LevelMeter.linearToDb(1.0)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        // 0.5 linear ≈ -6.02 dB
        assertThat(LevelMeter.linearToDb(0.5)).isCloseTo(-6.02, org.assertj.core.data.Offset.offset(0.1));
        // 0 linear = floor
        assertThat(LevelMeter.linearToDb(0.0)).isEqualTo(-120.0);
    }

    @Test
    void shouldResetToSilence() {
        LevelMeter meter = new LevelMeter();
        meter.process(new float[]{0.9f, 0.8f});
        meter.reset();

        assertThat(meter.getLatestData()).isEqualTo(LevelData.SILENCE);
    }

    @Test
    void shouldSupportOffsetProcessing() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = {0.1f, 0.9f, 0.2f, 0.1f};
        // Process only samples[1] and samples[2]
        meter.process(samples, 1, 2);

        LevelData data = meter.getLatestData();
        assertThat(data.peakLinear()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void shouldRejectInvalidDecayRate() {
        assertThatThrownBy(() -> new LevelMeter(1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LevelMeter(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProvideDbValues() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = {1.0f}; // Full scale
        meter.process(samples);

        LevelData data = meter.getLatestData();
        assertThat(data.peakDb()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.1));
    }

    // --- True Peak Metering Tests ---

    @Test
    void shouldReportTruePeakLinear() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = new float[256];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.8f;
        }
        meter.process(samples);

        LevelData data = meter.getLatestData();
        assertThat(data.truePeakLinear()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void shouldReportTruePeakDbtp() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = new float[256];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.8f;
        }
        meter.process(samples);

        LevelData data = meter.getLatestData();
        // 0.8 linear ≈ -1.94 dB
        assertThat(data.truePeakDbtp()).isGreaterThan(-120.0);
    }

    @Test
    void truePeakShouldBeAtLeastAsBigAsSamplePeak() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = new float[512];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (Math.sin(2 * Math.PI * i / 10.0) * 0.9);
        }
        meter.process(samples);

        LevelData data = meter.getLatestData();
        assertThat(data.truePeakLinear()).isGreaterThanOrEqualTo(data.peakLinear());
    }

    @Test
    void shouldResetTruePeakOnReset() {
        LevelMeter meter = new LevelMeter(0.0);
        meter.process(new float[]{0.9f, 0.8f, 0.7f});

        LevelData dataBeforeReset = meter.getLatestData();
        assertThat(dataBeforeReset.truePeakLinear()).isGreaterThan(0.0);

        meter.reset();
        assertThat(meter.getLatestData().truePeakLinear()).isEqualTo(0.0);
        assertThat(meter.getLatestData().truePeakDbtp()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void silenceShouldHaveZeroTruePeak() {
        LevelMeter meter = new LevelMeter(0.0);
        float[] samples = new float[64]; // All zeros
        meter.process(samples);

        LevelData data = meter.getLatestData();
        assertThat(data.truePeakLinear()).isEqualTo(0.0);
    }
}
