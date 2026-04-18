package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HearingLossSimulatorTest {

    private static final double SAMPLE_RATE = 44100.0;
    private static final int NUM_FRAMES = 1024;

    // ---- Construction ----

    @Test
    void shouldCreateWithValidParameters() {
        HearingLossSimulator sim = new HearingLossSimulator(2, SAMPLE_RATE);
        assertThat(sim.getInputChannelCount()).isEqualTo(2);
        assertThat(sim.getOutputChannelCount()).isEqualTo(2);
        assertThat(sim.isBypassed()).isFalse();
        assertThat(sim.getAudiogram()).containsOnly(0.0);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new HearingLossSimulator(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HearingLossSimulator(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HearingLossSimulator(-1, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HearingLossSimulator(2, -SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBeAnnotatedRealTimeSafe() {
        assertThat(HearingLossSimulator.class.isAnnotationPresent(RealTimeSafe.class))
                .isTrue();
    }

    // ---- Audiogram ----

    @Test
    void shouldExposeSixOctaveAudiogramBands() {
        assertThat(HearingLossSimulator.BAND_COUNT).isEqualTo(6);
        assertThat(HearingLossSimulator.AUDIOGRAM_FREQUENCIES)
                .containsExactly(250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0);

        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        assertThat(sim.getBandFrequency(0)).isEqualTo(250.0);
        assertThat(sim.getBandFrequency(5)).isEqualTo(8000.0);
    }

    @Test
    void shouldClampAudiogramValues() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        sim.setBandThresholdDb(0, -10.0);
        sim.setBandThresholdDb(5, 999.0);
        assertThat(sim.getBandThresholdDb(0)).isEqualTo(0.0);
        assertThat(sim.getBandThresholdDb(5))
                .isEqualTo(HearingLossSimulator.MAX_THRESHOLD_DB);
    }

    @Test
    void shouldRejectAudiogramOfWrongLength() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        assertThatThrownBy(() -> sim.setAudiogram(new double[]{0, 0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Processing ----

    @Test
    void shouldPassThroughWhenBypassed() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        sim.applyPreset(HearingLossSimulator.Preset.PRESBYCUSIS);
        sim.setBypassed(true);

        float[][] input = whiteNoise(1, NUM_FRAMES);
        float[][] output = new float[1][NUM_FRAMES];
        sim.process(input, output, NUM_FRAMES);

        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat(output[0][i]).isEqualTo(input[0][i]);
        }
    }

    @Test
    void shouldPassThroughWithFlatAudiogram() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        // Default audiogram is all zeros — the processor should be transparent
        // (recruitment is skipped when threshold == 0, attenuation is skipped
        // when threshold == 0).
        float[][] input = whiteNoise(1, NUM_FRAMES);
        float[][] output = new float[1][NUM_FRAMES];
        sim.process(input, output, NUM_FRAMES);

        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat(output[0][i]).isEqualTo(input[0][i]);
        }
    }

    @Test
    void shouldAttenuateAffectedFrequencies() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        sim.setRecruitmentLevel(0.0); // isolate audiogram attenuation
        sim.setBandThresholdDb(4, 40.0); // heavy 4 kHz loss

        // RMS at 4 kHz vs 250 Hz
        double rmsHighFiltered = rms(processSine(sim, 4000.0));
        sim.reset();
        double rmsLowFiltered = rms(processSine(sim, 250.0));

        HearingLossSimulator flat = new HearingLossSimulator(1, SAMPLE_RATE);
        double rmsHighRef = rms(processSine(flat, 4000.0));

        // 4 kHz band should be noticeably attenuated; 250 Hz band should not be.
        assertThat(rmsHighFiltered).isLessThan(0.5 * rmsHighRef);
        assertThat(rmsLowFiltered).isGreaterThan(0.5); // near the original 1.0 amplitude
    }

    @Test
    void shouldPreserveChannelCountsAndRunStereo() {
        HearingLossSimulator sim = new HearingLossSimulator(2, SAMPLE_RATE);
        sim.applyPreset(HearingLossSimulator.Preset.NOISE_INDUCED);

        float[][] input = whiteNoise(2, NUM_FRAMES);
        float[][] output = new float[2][NUM_FRAMES];
        sim.process(input, output, NUM_FRAMES);

        // Output should contain finite samples on both channels.
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < NUM_FRAMES; i++) {
                assertThat(Float.isFinite(output[ch][i])).isTrue();
            }
        }
    }

    @Test
    void shouldResetFilterState() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        sim.applyPreset(HearingLossSimulator.Preset.PRESBYCUSIS);

        float[][] input = whiteNoise(1, NUM_FRAMES);
        float[][] output = new float[1][NUM_FRAMES];
        sim.process(input, output, NUM_FRAMES);

        // Reset should not throw and should clear state.
        sim.reset();

        float[][] silence = new float[1][NUM_FRAMES];
        float[][] out2 = new float[1][NUM_FRAMES];
        sim.process(silence, out2, NUM_FRAMES);
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat(out2[0][i]).isEqualTo(0.0f);
        }
    }

    // ---- Presets ----

    @Test
    void shouldApplyPresets() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);

        sim.applyPreset(HearingLossSimulator.Preset.NORMAL);
        assertThat(sim.getAudiogram()).containsOnly(0.0);

        sim.applyPreset(HearingLossSimulator.Preset.MILD_HIGH_FREQUENCY);
        // High-frequency thresholds should be larger than low-frequency ones.
        assertThat(sim.getBandThresholdDb(5))
                .isGreaterThan(sim.getBandThresholdDb(0));

        sim.applyPreset(HearingLossSimulator.Preset.NOISE_INDUCED);
        // Classic 4 kHz notch — band 4 (4 kHz) should be the largest threshold.
        double fourK = sim.getBandThresholdDb(4);
        for (int i = 0; i < HearingLossSimulator.BAND_COUNT; i++) {
            if (i == 4) {
                continue;
            }
            assertThat(fourK).isGreaterThanOrEqualTo(sim.getBandThresholdDb(i));
        }

        sim.applyPreset(HearingLossSimulator.Preset.PRESBYCUSIS);
        // Presbycusis slopes downward (increasing loss with frequency).
        for (int i = 1; i < HearingLossSimulator.BAND_COUNT; i++) {
            assertThat(sim.getBandThresholdDb(i))
                    .isGreaterThanOrEqualTo(sim.getBandThresholdDb(i - 1));
        }
    }

    // ---- Recruitment / broadening ----

    @Test
    void shouldClampRecruitmentLevel() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        sim.setRecruitmentLevel(-1.0);
        assertThat(sim.getRecruitmentLevel()).isEqualTo(0.0);
        sim.setRecruitmentLevel(5.0);
        assertThat(sim.getRecruitmentLevel()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectBroadeningBelowOne() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        assertThatThrownBy(() -> sim.setFilterBroadening(0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposePerBandRecruitmentCompressor() {
        HearingLossSimulator sim = new HearingLossSimulator(1, SAMPLE_RATE);
        CompressorProcessor comp = sim.getRecruitmentCompressor(3);
        assertThat(comp).isNotNull();
        assertThat(comp.getRatio()).isGreaterThan(1.0);
    }

    // ---- helpers ----

    private static float[][] whiteNoise(int channels, int frames) {
        float[][] buf = new float[channels][frames];
        java.util.Random rng = new java.util.Random(42);
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                buf[ch][i] = (rng.nextFloat() * 2.0f - 1.0f) * 0.5f;
            }
        }
        return buf;
    }

    private static float[] processSine(HearingLossSimulator sim, double freq) {
        int frames = 8192;
        float[][] input = new float[1][frames];
        float[][] output = new float[1][frames];
        double omega = 2.0 * Math.PI * freq / SAMPLE_RATE;
        for (int i = 0; i < frames; i++) {
            input[0][i] = (float) Math.sin(omega * i);
        }
        sim.process(input, output, frames);
        return output[0];
    }

    private static double rms(float[] signal) {
        // Skip the first 2048 samples so filter transients don't dominate.
        double sum = 0.0;
        int start = Math.min(2048, signal.length / 2);
        int count = signal.length - start;
        for (int i = start; i < signal.length; i++) {
            sum += signal[i] * signal[i];
        }
        return Math.sqrt(sum / count);
    }
}
