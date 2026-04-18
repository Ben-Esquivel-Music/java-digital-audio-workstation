package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphicEqProcessorTest {

    // ---- Construction ----

    @Test
    void shouldCreateWithValidParameters() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThat(eq.getInputChannelCount()).isEqualTo(2);
        assertThat(eq.getOutputChannelCount()).isEqualTo(2);
        assertThat(eq.getBandType()).isEqualTo(GraphicEqProcessor.BandType.OCTAVE);
        assertThat(eq.getFilterMode()).isEqualTo(GraphicEqProcessor.FilterMode.MINIMUM_PHASE);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new GraphicEqProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphicEqProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphicEqProcessor(-1, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphicEqProcessor(2, -44100.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Band configuration ----

    @Test
    void shouldHave10OctaveBands() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThat(eq.getBandCount()).isEqualTo(10);
        assertThat(eq.getBandFrequency(0)).isEqualTo(31.5);
        assertThat(eq.getBandFrequency(9)).isEqualTo(16000.0);
    }

    @Test
    void shouldHaveThirdOctaveBands() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandType(GraphicEqProcessor.BandType.THIRD_OCTAVE);
        assertThat(eq.getBandCount()).isEqualTo(31);
        assertThat(eq.getBandFrequency(0)).isEqualTo(20.0);
        assertThat(eq.getBandFrequency(eq.getBandCount() - 1)).isEqualTo(20000.0);
    }

    @Test
    void shouldFilterBandsAboveNyquist() {
        // At 8000 Hz sample rate, Nyquist is 4000 Hz
        GraphicEqProcessor eq = new GraphicEqProcessor(1, 8000.0);
        // Only frequencies < 4000 Hz should be included
        // Octave: 31.5, 63, 125, 250, 500, 1000, 2000 = 7 bands
        assertThat(eq.getBandCount()).isEqualTo(7);
        assertThat(eq.getBandFrequency(eq.getBandCount() - 1)).isEqualTo(2000.0);
    }

    @Test
    void shouldSwitchBandType() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThat(eq.getBandType()).isEqualTo(GraphicEqProcessor.BandType.OCTAVE);
        eq.setBandType(GraphicEqProcessor.BandType.THIRD_OCTAVE);
        assertThat(eq.getBandType()).isEqualTo(GraphicEqProcessor.BandType.THIRD_OCTAVE);
        assertThat(eq.getBandCount()).isEqualTo(31);
    }

    @Test
    void shouldRejectNullBandType() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setBandType(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Gain management ----

    @Test
    void shouldStartWithAllGainsAtZero() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        for (int i = 0; i < eq.getBandCount(); i++) {
            assertThat(eq.getBandGain(i)).isEqualTo(0.0);
        }
    }

    @Test
    void shouldSetAndGetBandGain() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandGain(0, 6.0);
        assertThat(eq.getBandGain(0)).isEqualTo(6.0);
        eq.setBandGain(5, -3.5);
        assertThat(eq.getBandGain(5)).isEqualTo(-3.5);
    }

    @Test
    void shouldClampGainToMaxRange() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandGain(0, 20.0);
        assertThat(eq.getBandGain(0)).isEqualTo(12.0);
        eq.setBandGain(0, -20.0);
        assertThat(eq.getBandGain(0)).isEqualTo(-12.0);
    }

    @Test
    void shouldSetAllBandGains() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        double[] gains = new double[eq.getBandCount()];
        for (int i = 0; i < gains.length; i++) {
            gains[i] = i - 5.0;
        }
        eq.setAllBandGains(gains);
        for (int i = 0; i < gains.length; i++) {
            assertThat(eq.getBandGain(i)).isEqualTo(Math.max(-12.0, Math.min(12.0, i - 5.0)));
        }
    }

    @Test
    void shouldRejectMismatchedGainsArray() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setAllBandGains(new double[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullGainsArray() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setAllBandGains(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldFlattenAllBands() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandGain(0, 6.0);
        eq.setBandGain(5, -3.0);
        eq.flattenAllBands();
        for (int i = 0; i < eq.getBandCount(); i++) {
            assertThat(eq.getBandGain(i)).isEqualTo(0.0);
        }
    }

    @Test
    void shouldReturnCopyOfFrequencies() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        double[] freqs = eq.getFrequencies();
        assertThat(freqs).hasSize(eq.getBandCount());
        // Modifying the returned array should not affect the processor
        freqs[0] = 999.0;
        assertThat(eq.getBandFrequency(0)).isEqualTo(31.5);
    }

    @Test
    void shouldReturnCopyOfGains() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandGain(0, 6.0);
        double[] gains = eq.getAllBandGains();
        assertThat(gains[0]).isEqualTo(6.0);
        // Modifying the returned array should not affect the processor
        gains[0] = 0.0;
        assertThat(eq.getBandGain(0)).isEqualTo(6.0);
    }

    // ---- Q factor ----

    @Test
    void shouldHaveDefaultQForOctave() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThat(eq.getQ()).isEqualTo(1.414);
    }

    @Test
    void shouldHaveDefaultQForThirdOctave() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandType(GraphicEqProcessor.BandType.THIRD_OCTAVE);
        assertThat(eq.getQ()).isEqualTo(4.318);
    }

    @Test
    void shouldSetQ() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setQ(2.0);
        assertThat(eq.getQ()).isEqualTo(2.0);
    }

    @Test
    void shouldRejectInvalidQ() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setQ(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eq.setQ(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Filter mode ----

    @Test
    void shouldDefaultToMinimumPhase() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThat(eq.getFilterMode()).isEqualTo(GraphicEqProcessor.FilterMode.MINIMUM_PHASE);
        assertThat(eq.getLatencySamples()).isEqualTo(0);
    }

    @Test
    void shouldReportLatencyInLinearPhaseMode() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setFilterMode(GraphicEqProcessor.FilterMode.LINEAR_PHASE);
        // Default firOrder is 4095, latency = (4095-1)/2 = 2047
        assertThat(eq.getLatencySamples()).isEqualTo(2047);
    }

    @Test
    void shouldSetAndGetFirOrder() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setFirOrder(1023);
        assertThat(eq.getFirOrder()).isEqualTo(1023);
    }

    @Test
    void shouldRejectInvalidFirOrder() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setFirOrder(2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullFilterMode() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setFilterMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Processing: minimum-phase ----

    @Test
    void shouldPassThroughWithAllGainsAtZero() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);

        float[][] input = {{0.5f, -0.3f, 0.8f}, {0.2f, 0.1f, -0.4f}};
        float[][] output = new float[2][3];
        eq.process(input, output, 3);

        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f);
        assertThat(output[1]).containsExactly(0.2f, 0.1f, -0.4f);
    }

    @Test
    void shouldBoostBandAt1kHz() {
        GraphicEqProcessor eq = new GraphicEqProcessor(1, 44100.0);
        // Band index 5 is 1000 Hz for octave config
        eq.setBandGain(5, 12.0);

        int numFrames = 4096;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        eq.process(input, output, numFrames);

        // After filter settles, output RMS should be greater than input RMS
        double inputRms = rms(input[0], 2048, numFrames);
        double outputRms = rms(output[0], 2048, numFrames);
        assertThat(outputRms).isGreaterThan(inputRms * 1.5);
    }

    @Test
    void shouldCutBandAt1kHz() {
        GraphicEqProcessor eq = new GraphicEqProcessor(1, 44100.0);
        // Band index 5 is 1000 Hz
        eq.setBandGain(5, -12.0);

        int numFrames = 4096;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        eq.process(input, output, numFrames);

        // After filter settles, output RMS should be less than input RMS
        double inputRms = rms(input[0], 2048, numFrames);
        double outputRms = rms(output[0], 2048, numFrames);
        assertThat(outputRms).isLessThan(inputRms * 0.5);
    }

    @Test
    void shouldProduceFiniteOutputWithAllBandsBoosted() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        for (int i = 0; i < eq.getBandCount(); i++) {
            eq.setBandGain(i, 6.0);
        }

        int numFrames = 1024;
        float[][] input = new float[2][numFrames];
        float[][] output = new float[2][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0 + 0.5);
        }
        eq.process(input, output, numFrames);

        for (float v : output[0]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
        for (float v : output[1]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
    }

    // ---- Processing: linear-phase ----

    @Test
    void shouldPassThroughLinearPhaseWithAllGainsAtZero() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setFilterMode(GraphicEqProcessor.FilterMode.LINEAR_PHASE);

        float[][] input = {{0.5f, -0.3f, 0.8f}, {0.2f, 0.1f, -0.4f}};
        float[][] output = new float[2][3];
        eq.process(input, output, 3);

        // No active bands → pass through
        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f);
        assertThat(output[1]).containsExactly(0.2f, 0.1f, -0.4f);
    }

    @Test
    void shouldProcessInLinearPhaseMode() {
        GraphicEqProcessor eq = new GraphicEqProcessor(1, 44100.0);
        eq.setFilterMode(GraphicEqProcessor.FilterMode.LINEAR_PHASE);
        eq.setFirOrder(255);
        eq.setBandGain(5, 6.0); // Boost 1 kHz

        int latency = eq.getLatencySamples();
        int totalSamples = latency + 2048;
        float[][] input = new float[1][totalSamples];
        float[][] output = new float[1][totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        eq.process(input, output, totalSamples);

        // Output should have finite values and boosted signal
        for (float v : output[0]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
        double inputRms = rms(input[0], latency + 512, totalSamples);
        double outputRms = rms(output[0], latency + 512, totalSamples);
        assertThat(outputRms).isGreaterThan(inputRms);
    }

    // ---- Third-octave processing ----

    @Test
    void shouldProcessThirdOctaveBands() {
        GraphicEqProcessor eq = new GraphicEqProcessor(1, 44100.0);
        eq.setBandType(GraphicEqProcessor.BandType.THIRD_OCTAVE);
        // Band index 17 is 1000 Hz for third-octave (31-band starting at 20 Hz)
        eq.setBandGain(17, 12.0);

        int numFrames = 4096;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        eq.process(input, output, numFrames);

        double inputRms = rms(input[0], 2048, numFrames);
        double outputRms = rms(output[0], 2048, numFrames);
        assertThat(outputRms).isGreaterThan(inputRms);
    }

    // ---- Reset ----

    @Test
    void shouldResetAllFilters() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandGain(0, 6.0);
        eq.setBandGain(5, -3.0);

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        eq.process(input, output, 256);

        eq.reset(); // Should not throw
    }

    @Test
    void shouldResetLinearPhaseFilters() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setFilterMode(GraphicEqProcessor.FilterMode.LINEAR_PHASE);
        eq.setFirOrder(127);
        eq.setBandGain(5, 6.0);

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        eq.process(input, output, 256);

        eq.reset(); // Should not throw
    }

    // ---- Band type switching resets gains ----

    @Test
    void shouldResetGainsWhenSwitchingBandType() {
        GraphicEqProcessor eq = new GraphicEqProcessor(2, 44100.0);
        eq.setBandGain(0, 6.0);
        eq.setBandType(GraphicEqProcessor.BandType.THIRD_OCTAVE);

        for (int i = 0; i < eq.getBandCount(); i++) {
            assertThat(eq.getBandGain(i)).isEqualTo(0.0);
        }
    }

    // ---- Real-time safety ----

    @Test
    void shouldBeAnnotatedRealTimeSafe() {
        assertThat(GraphicEqProcessor.class.isAnnotationPresent(RealTimeSafe.class))
                .as("GraphicEqProcessor should be annotated @RealTimeSafe")
                .isTrue();
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
